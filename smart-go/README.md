# Smart Go 0.3.2

Smart Go runs on the IZIS touchscreen. For **Local Computer** games it sends board observations over paired TLS to the desktop companion. KaTrain owns game history and uses a local KataGo process. The Android app talks to the installed IZIS launcher for sensors, buttons and LEDs.

## Build and install Android

Use JDK 25, Android SDK Platform 37, Build Tools 36.0.0 and platform-tools. Set `JAVA_HOME` and `ANDROID_HOME` to your installations. From `smart-go/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb devices -l
SERIAL='YOUR_BOARD_ADB_SERIAL'
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -n cn.izis.local.smartgo.dev/cn.izis.izisbridge.MainActivity
```

The APK supports API 25, version code 5 and application ID `cn.izis.local.smartgo.dev`. The stock launcher must be enabled. Use the board's existing authorized ADB connection on a trusted LAN (if enabled on the board, `adb connect BOARD_IP:5555`) or sideload from USB storage and open **Smart Go 0.3.2**. An in-place upgrade requires the original APK signing key. If signers differ, stop and preserve the installed app and its games, pairing and credentials. On an existing development workstation, use its original debug signing configuration.

## Run the desktop companion

The tested setup is Linux x86-64, Python 3.13, a desktop display and OpenSSL on PATH. From `smart-go/desktop/` on a fresh checkout:

```sh
git clone https://github.com/sanderland/katrain.git vendor/katrain
git -C vendor/katrain checkout f4981cf905cece90085ce4e3967415d0c16d525f
python3.13 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt

mkdir -p runtime/katago
curl -fL https://github.com/lightvector/KataGo/releases/download/v1.18.1/katago-v1.18.1-eigen-linux-x64.zip -o runtime/katago.zip
echo '993b642601e806037003d11e43775e7b4fc65281aed9b9469b7122f18fc16811  runtime/katago.zip' | sha256sum -c -
.venv/bin/python -m zipfile -e runtime/katago.zip runtime/katago
chmod +x runtime/katago/katago
(cd runtime/katago && ./katago --appimage-extract)
runtime/katago/squashfs-root/AppRun version

.venv/bin/python -m izis_katrain.launch --size 9 --control-port 8767 --runtime runtime/local-9
```

The pinned KaTrain checkout provides the model. To use another compatible engine executable, pass `--katago /path/to/katago`. Keep a separate `--runtime` per initial board size and engine profile. Keep the runtime, certificate, pairing key and Read19 settings private. [KaTrain documentation](https://github.com/sanderland/katrain) · [KataGo releases](https://github.com/lightvector/KataGo/releases)

## Connect and play

1. Save and exit any stock board game. Clear the grid. Keep the board and computer on the same trusted LAN and allow TCP 8767 to the desktop from the board. Discovery also uses UDP 8767 and is optional.
2. Open **Android pairing** in the desktop window. On the board open **Settings → Configure / Replace desktop pairing**. Enter the computer's LAN IP, port, displayed SHA-256 fingerprint and private pairing key. Use **Test connection (no analysis)** if needed.
3. On the board choose **Match → Start Game → Local Computer**, select a size, and use the default automatic placement mode. Place the opponent's stone at the indicated point. Hold the white timer button for on-demand suggestions. View the screen for correction and capture-removal instructions.
4. To stop, save or finish the game and use **Back → Exit**. Wait for the board to go dark and the hardware session to release before opening a stock board app. Close the desktop window after the board has released its session.

If discovery fails, enter the computer's IP manually. For TLS failures check the host/port, fingerprint and pairing key against the same desktop runtime. For stale sensors, check the stock launcher service and close competing board apps. For unconfirmed lighting cleanup, pause the handoff and inspect the board. On a debug build read `adb -s "$SERIAL" exec-out run-as cn.izis.local.smartgo.dev cat files/lighting-diagnostics.txt`. The expected shutdown trace includes `RGC`, `ALC` and local unbind.

Read19 is optional. Enter its credential in the Android Settings form. The desktop launcher also supports a private entry prompt with `--engine read19 --setup-read19`. Live Read19 and 0.3.2 physical lighting and stock-app handoff remain unverified for this source snapshot.

## Test without hardware

Use the [browser simulator and TCP/WebSocket bridge](../hardware/README.md#simulator-and-bridge) to try wire commands. Android instrumentation uses a mock manufacturer service. The desktop uses a separate synthetic full-grid fixture to check the paired TLS path with local KaTrain/KataGo. From `smart-go/desktop/` after setup:

```sh
.venv/bin/python -m pip install pytest
.venv/bin/python -m pytest -q tests
# Optional Linux integration check with Xvfb and a configured KataGo executable
xvfb-run -a .venv/bin/python -u tests/service_virtual_proof.py
```
