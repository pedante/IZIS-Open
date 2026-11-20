package cn.izis.izisbridge;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

/** Emulator fixture only: independently encodes the inspected wire contract. */
public final class MockSerialService extends Service {
    static final String SERVICE = "com.example.lxf.laucher2.ISerialService";
    static final String LISTENER = "com.example.lxf.laucher2.ISerialServiceListener";
    private IBinder listener;
    private int registrations, removals, forbidden, writes;
    private boolean acknowledge = true;
    private String lastCommand = "";
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private String snapshot = "~SDA" + new String(new char[361]).replace('\0', '0') + "#";
    private final Binder binder = new Binder() {
        { attachInterface(null, SERVICE); }
        @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(SERVICE); return true; }
            if (code == 100) {
                reply.writeNoException();
                reply.writeInt(registrations); reply.writeInt(removals); reply.writeInt(forbidden);
                return true;
            }
            if (code == 105) { acknowledge = data.readInt() != 0; reply.writeNoException(); return true; }
            if (code == 106) { reply.writeNoException(); reply.writeStringList(history); return true; }
            if (code == 107) { frame(data.readString()); reply.writeNoException(); return true; }
            if (code == 104) { snapshot = data.readString(); frame(snapshot); reply.writeNoException(); return true; }
            if (code == 103) { reply.writeNoException(); reply.writeInt(writes); reply.writeString(lastCommand); return true; }
            if (code == 101) {
                emit(data.readInt()); reply.writeNoException(); return true;
            }
            if (code == 102) {
                reply.writeNoException();
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        android.os.Process.killProcess(android.os.Process.myPid()), 100);
                return true;
            }
            data.enforceInterface(SERVICE);
            if (code == 1) {
                listener = data.readStrongBinder(); registrations++;
                emit(4); emit(1);
            } else if (code == 2) {
                IBinder departing = data.readStrongBinder();
                if (listener == null || !listener.equals(departing)) throw new SecurityException("Wrong listener");
                listener = null; removals++;
            } else if (code == 3) {
                String command = data.readString();
                if (!(command.matches("~SHP[0-9]{3},r(?:000|008)g(?:000|008)b(?:000|008),1#") || command.equals("~STA#") || command.equals("~RGC#") || command.matches("~HOT(?:[A-D][0-9]{3}r(?:000|008)g(?:000|008)b(?:000|008)){4}N[1-4]#"))) throw new SecurityException("Unapproved command");
                writes++; lastCommand = command;
                history.add(android.os.SystemClock.elapsedRealtime() + "\t" + command);
                new Handler(Looper.getMainLooper()).post(() -> {
                    try { if (command.equals("~STA#") || acknowledge) frame(command.equals("~STA#") ? snapshot : command.equals("~RGC#") ? "~ALC#" : command.startsWith("~SHP") ? "~HCS#" : "~HSM#"); }
                    catch (RemoteException ignored) { }
                });
            } else {
                forbidden++;
                throw new SecurityException("Forbidden global operation " + code);
            }
            reply.writeNoException();
            return true;
        }
    };

    private void frame(String frame) throws RemoteException {
        if (listener == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(LISTENER); data.writeString(frame);
            listener.transact(1, data, reply, 0); reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }
    private void emit(int code) throws RemoteException {
        if (listener == null) throw new IllegalStateException("No listener");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(LISTENER);
            if (code == 1 || code == 2 || code == 3) data.writeString("~SDA012#");
            if (code == 3) data.writeInt(1);
            if (code == 5) data.writeInt(3);
            if (!listener.transact(code, data, reply, 0)) throw new AssertionError("Unknown callback " + code);
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}
