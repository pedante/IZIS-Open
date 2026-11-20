package cn.izis.izisbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.example.lxf.laucher2.ISerialService;
import com.example.lxf.laucher2.ISerialServiceListener;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manufacturer Binder client. All public lifecycle methods run on the main thread. */
final class BoardServiceClient {
    static final String PACKAGE = "com.example.lxf.laucher2";
    static final String ACTION = "com.lxf.aidl.SerialServer";
    static final long CONNECT_TIMEOUT_MS = 10000;

    interface Observer {
        void status(String text, boolean busy);
        void message(String text);
    }

    private final Context context;
    private final Intent intent;
    private final Observer observer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService calls = Executors.newSingleThreadExecutor();
    private Session session;
    private boolean destroyed;

    BoardServiceClient(Context context, Observer observer) {
        this(context, new Intent(ACTION).setPackage(PACKAGE), observer);
    }

    // The alternative endpoint is package-private and used only by instrumentation.
    BoardServiceClient(Context context, Intent intent, Observer observer) {
        this.context = context;
        this.intent = new Intent(intent);
        this.observer = observer;
    }

    private volatile int commandGeneration;
    private long lastWrite;
    boolean ready() { return session != null && active(session) && session.reportedStatus && session.registered; }
    void cancelCommands() { commandGeneration++; }
    void send(String command) {
        if (!LedController.allowed(command)) throw new IllegalArgumentException("Invalid board command");
        Session current = session;
        if (current == null || !ready()) { observer.message("TX failed: service not ready"); return; }
        int generation = commandGeneration;
        calls.execute(() -> {
            try {
                long delay = 240 - (android.os.SystemClock.elapsedRealtime() - lastWrite);
                if (delay > 0) Thread.sleep(delay);
                if (generation != commandGeneration || current.cancelled) return;
                lastWrite = android.os.SystemClock.elapsedRealtime();
                current.service.writeAndFlush(command);
                current.event("TX IPC completed (not MCU acknowledgement): " + command);
            } catch (Exception error) { current.failure("Command failed: " + error.getClass().getSimpleName()); }
        });
    }

    void connect() {
        if (destroyed || session != null) return;
        Session current = new Session();
        session = current;
        observer.status("Binding to manufacturer service…", true);
        main.postDelayed(current.timeout, CONNECT_TIMEOUT_MS);
        try {
            current.bindingAttempted = true;
            if (!context.bindService(intent, current, Context.BIND_AUTO_CREATE)) {
                detach(current, "Service unavailable — no automatic retry");
            }
        } catch (RuntimeException error) {
            detach(current, "Bind failed: " + error.getClass().getSimpleName());
        }
    }

    void disconnect() {
        if (session != null) detach(session, "Disconnected — local binding released");
    }

    void destroy() {
        disconnect();
        destroyed = true;
        calls.shutdown();
    }

    private boolean active(Session current) {
        return session == current && !current.cancelled && !destroyed;
    }

    private void detach(Session current, String result) {
        if (session != current || current.cancelled) return;
        cancelCommands();
        current.cancelled = true;
        main.removeCallbacks(current.timeout);
        if (!destroyed) observer.status("Disconnecting our listener…", true);
        // Serialize with register so even a partially successful registration is removed.
        calls.execute(() -> {
            String problem = "";
            try {
                if (current.registrationAttempted && current.service != null) {
                    current.service.unRegisterListener(current.listener);
                }
            } catch (Exception error) {
                problem = " (remote listener removal unconfirmed: " + error.getClass().getSimpleName() + ")";
            } finally {
                if (current.binder != null) {
                    try { current.binder.unlinkToDeath(current.death, 0); }
                    catch (RuntimeException ignored) { /* Already dead/unlinked. */ }
                }
                final String finalResult = result + problem;
                main.post(() -> {
                    String unbindProblem = "";
                    if (current.bindingAttempted) {
                        try { context.unbindService(current); }
                        catch (IllegalArgumentException ignored) { /* False/failed bind had no record. */ }
                        catch (RuntimeException error) {
                            unbindProblem = " (unbind failed: " + error.getClass().getSimpleName() + ")";
                        }
                    }
                    if (unbindProblem.isEmpty()) {
                        if (session == current) session = null;
                        if (!destroyed) observer.status(finalResult, false);
                    } else if (!destroyed) {
                        observer.status("Cleanup incomplete" + unbindProblem + " — restart this app before retrying", true);
                    }
                });
            }
        });
    }

    static String bounded(String data) {
        if (data == null) return "<null>";
        return data.length() <= 2048 ? data : data.substring(0, 2048) + " [truncated]";
    }

    private final class Session implements ServiceConnection {
        volatile boolean cancelled;
        boolean bindingAttempted;
        boolean registrationAttempted;
        boolean reportedStatus;
        boolean registered;
        IBinder binder;
        ISerialService service;
        final Runnable timeout = () -> detach(this, "Connection timed out — no automatic retry");
        final IBinder.DeathRecipient death = () -> main.post(() ->
                detach(this, "Service died — press Connect for a new session"));

        private void event(String text) {
            String safe = bounded(text);
            main.post(() -> { if (active(this)) observer.message(safe); });
        }

        private void failure(String text) {
            main.post(() -> { if (active(this)) detach(this, text); });
        }

        final ISerialServiceListener listener = new ISerialServiceListener.Stub() {
            public void onReceiveNormalData(String data) { event("RX: " + bounded(data)); }
            public void onReceiveErrorData(String data) { event("RX error: " + bounded(data)); }
            public void onSendData(String data, boolean success) {
                event("Manufacturer shared send notification, success="
                        + success + ": " + bounded(data));
            }
            public void onConnectSuccess() {
                main.post(() -> {
                    if (active(Session.this)) {
                        reportedStatus = true;
                        observer.status("Manufacturer reports board connected — awaiting/receiving data", true);
                    }
                });
            }
            public void onConnectFail(int attempts) {
                failure("Manufacturer connection failed (attempt " + attempts + ") — no client retry");
            }
            public void onConnectError() { failure("Manufacturer reports connection error"); }
            public void onClose() { failure("Manufacturer closed the shared connection"); }
        };

        @Override public void onServiceConnected(ComponentName name, IBinder remote) {
            if (!active(this)) return;
            binder = remote;
            service = ISerialService.Stub.asInterface(remote);
            observer.status("Service bound — registering our listener…", true);
            calls.execute(() -> {
                try {
                    if (cancelled) return;
                    if (!"com.example.lxf.laucher2.ISerialService".equals(remote.getInterfaceDescriptor())) {
                        throw new SecurityException("Unexpected Binder interface");
                    }
                    remote.linkToDeath(death, 0);
                    if (cancelled) return;
                    registrationAttempted = true;
                    service.registerListener(listener);
                    main.post(() -> {
                        if (active(this)) {
                            registered = true;
                            main.removeCallbacks(timeout);
                            observer.message("Listener registration IPC completed; this is not an MCU acknowledgement.");
                            if (!reportedStatus) observer.status("Listener registered — waiting for manufacturer status/data", true);
                        }
                    });
                } catch (Exception error) {
                    failure("Registration failed: " + error.getClass().getSimpleName());
                }
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            detach(this, "Service disconnected — no automatic retry");
        }

        @Override public void onBindingDied(ComponentName name) {
            detach(this, "Service binding died — no automatic retry");
        }

        @Override public void onNullBinding(ComponentName name) {
            detach(this, "Service returned no Binder");
        }
    }
}
