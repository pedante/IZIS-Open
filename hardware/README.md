# Develop for the IZIS board

The tested board has a UniWin M339 controller (RK3399, Android API 25, 4 GB RAM). A separate MCU handles the Hall sensors, LED grid and clock buttons. Android and MCU firmware versions are independent.

| Method | When to use it | Evidence on this board |
| --- | --- | --- |
| Manufacturer Android service | Android apps on the stock launcher. The launcher owns the UART and the app binds through AIDL. | Smart Go used this path on the physical board for sensor reads, play and the white timer event. The 0.3.2 lighting changes have emulator coverage and await physical acceptance. |
| Direct serial | A controlled deployment where the client has lawful OS access to the internal UART and can keep sole ownership. | The audited SDK and board-specific Fox app use this transport. Access from an ordinary third-party app UID remains unverified. |

Use one board client at a time. Save and exit the manufacturer game before opening Smart Go or the UART. Use the simulator first. Start hardware testing with `STA` in existing 19×19 mode. Leave calibration, thresholds, firmware transfer, board-size changes and dense or bright lighting outside the initial test.

## Manufacturer Android service

Keep the stock launcher installed and enabled. Bind with `BIND_AUTO_CREATE` using action `com.lxf.aidl.SerialServer` and package `com.example.lxf.laucher2` (including the original spelling). The installed launcher 1.1.4 (version code 14) exports `cn.izis.desktop.services.SerialConnectService` without a service permission and owns the serial backend. The 1.1.2 factory launcher (code 12) exposes the same action with an older interface tail. The service loads the manufacturer serial implementation in its own process. The client needs the Binder descriptors and method signatures to build.

The descriptors are `com.example.lxf.laucher2.ISerialService` and `com.example.lxf.laucher2.ISerialServiceListener`. Preserve transaction order when building the client:

- Service transactions 1–3 are `registerListener(listener)`, `unRegisterListener(listener)`, and `writeAndFlush(String)`. The manufacturer SDK also exposes queued commands and shared transport settings.
- Listener transactions 1–6 receive normal data, receive errors, send status, connect success, connect failure and connect error. Launcher 1.1.4 adds callback 7 `onClose()` and service transaction 14 `close(boolean)`.
- Wait for the connection-success callback, then send `~STA#` and parse the `~SDA…#` callback. Binder write completion confirms IPC delivery. Match MCU acknowledgements separately. The server broadcasts shared events and responses, so overlapping clients can interfere.
- After clearing your lighting, unregister your listener and unbind. A remote `close` stops the shared connection. Queue and pacing settings are shared too.

Smart Go is a working bridge example. Its [AIDL declarations](../smart-go/app/src/main/aidl/com/example/lxf/laucher2/) define the client subset, [BoardServiceClient](../smart-go/app/src/main/java/cn/izis/izisbridge/BoardServiceClient.java) handles connection, worker-thread sends and release, and [BoardHardware](../smart-go/app/src/main/java/cn/izis/izisbridge/BoardHardware.kt) polls sensors and dispatches button/LED events. The app forwards full-grid observations and move proposals to the paired desktop controller over TLS. It keeps raw UART commands on Android.

## Direct serial

The SDK recommends `SerialConnectDirect` for the 3 Plus 5G generation. It uses JNI (`libSerialPort.so`) to open `/dev/ttyS1` at **115200 baud, 8N1**, in raw mode. The audited SDK's ARM64 serial library matched the one in the backed-up installed launcher. Confirm the exact path. The audited implementation matches a substring and can attempt to run `su` and change the device node mode if access fails. Keep permissions restricted and check read/write access as the actual client UID. Android permissions, Linux device-node access and SELinux are distinct. Opening the port does not establish exclusive ownership across stock and third-party clients. [Audited SDK source](https://github.com/yzkj2213/CodeLibSerialPort/blob/e53f21a20d90e2bdc925d3b3c72300971230191e/serialport/src/main/java/com/izis/serialport/connect/SerialConnectDirect.java)

With Python and `pyserial` available **on the UART host**, this example opens the port once and queries firmware and sensors. Use it only after confirming permissions and that the launcher and other apps have released the UART. The queries do not change MCU settings.

```python
import re
import time

import serial

with serial.Serial("/dev/ttyS1", 115200, timeout=0.2, write_timeout=1,
                   exclusive=True) as port:
    buffer = bytearray()
    for command, expected in [(b"~GVE#", rb"~VER[^~#]+#"),
                              (b"~STA#", rb"~SDA[012]{361}#")]:
        port.write(command)
        port.flush()
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            buffer.extend(port.read_until(b"#", size=512))
            match = re.search(expected, buffer)
            if match:
                print(match.group().decode("ascii"))
                del buffer[:match.end()]
                break
            if len(buffer) > 1024:
                del buffer[:-512]
        else:
            raise TimeoutError(f"No expected response to {command!r}")
        time.sleep(0.08)
```

The pySerial exclusive flag only coordinates clients that honor the same lock. The direct example has no root fallback. On Android, use a compatible JNI serial library or the installed service when the app has no UART access. ProtocolDemo's older PL2303 USB transport is useful as a protocol reference. [ProtocolDemo](https://github.com/xieren58/ProtocolDemo)

## Commands and coordinates

Commands and reports are ASCII frames delimited by `~` and `#`. A serial or TCP read may contain a partial frame or several frames. Buffer and extract complete frames. Allow at least **80 ms after the previous transmission finishes**. For initial work use **240 ms** spacing for LED clears and heavy renders. Smart Go uses 240 ms between Binder writes and waits for LED acknowledgements before advancing the display.

| Send or receive | Meaning |
| --- | --- |
| `~GVE#` → `~VERxxx#` | Query the MCU version. |
| `~STA#` → `~SDA<data>#` | Read the full snapshot. In 19×19 mode require 361 digits: `0` empty, `1` black, `2` white. Compare consecutive snapshots to derive changes. |
| `~CTS1#` / `~CTS0#` → `~UDS1#` / `~UDS0#` | Enable/disable change snapshots globally. Smart Go polls `STA` and leaves the setting intact. |
| `~BKY#`, `~WKY#` | Black/white timer short presses. These arrive asynchronously. |
| `~BTK#`, `~WTK#` | Black/white timer long presses, documented on release. White long press was observed in Smart Go. Older SDK names describe double clicks. |
| `~SHP181,r000g008b000,1#` → `~HCS#` | Light the center in dim green. A new SHP replaces the previous SHP. Send `~SHP181,r000g000b000,1#` for targeted off. |
| `~HOTA181r000g008b000B182r008g000b000C001r000g000b000D001r000g000b000N2#` → `~HSM#` | Add two dim lights. Every batch requires slots A–D. `N1`–`N4` selects how many slots to render. Coordinates and RGB channels are three digits. |
| `~RGC#` → `~ALC#` | Clear the entire matrix, including any frame. Wait for `ALC` before releasing an owned display. |

Wire points run from **001 to 361** in the full grid. Smart Go's screen uses row-major zero-based indices from the opposite corner, so `wire = 361 - (row * 19 + column)`. Center is 181. Confirm orientation with single stones at the four corners and center, then confirm black/white encoding before enabling move submission. Smart Go keeps the full sensor grid and projects centered 9×9/13×13 games onto it with offsets of five/three intersections. The simulator also models logical `BOD` modes, which can change coordinate and snapshot rules.

HOTA adds to the existing display. Use `RGC` to reliably clear batch lighting before handoff. The targeted SHP-off interaction with HOTA on this MCU remains unverified for 0.3.2. The older demo's low-brightness cutoff is a heuristic. No electrical limit for the number of simultaneous LEDs was measured. [Additional protocol notes](protocol-reference.md) · [Manufacturer protocol PDF](https://github.com/yzkj2213/CodeLibSerialPort/blob/e53f21a20d90e2bdc925d3b3c72300971230191e/protocol/隐智第三代电子棋盘应用开发通信协议20230615.pdf)

## What the stock apps showed

These are findings from installed-app and factory-backup inspection.

- The board-specific Fox Go client (`com.foxwq.yhznqp`, v1.7) constructs `SerialConnectDirect` in `YZTool.InitYZ`. The general Fox Go client is a separate package.
- Original Go (`cn.izis.chessdesk2.factory`, v1.5.6) has service integration in its connection activities. Inspection established those call sites and does not identify the transport used by every screen.
- The stock launcher can prompt to force-stop a configured conflicting package when switching apps. That package list is not a UART lock or a graceful handoff. Close the current board app explicitly and confirm the next app receives fresh sensor input.

## Simulator and bridge

From the repository root:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install 'websockets==17.4'
.venv/bin/python hardware/simulator/izis_simulator_bridge.py
```

Open [izis_simulator.html](simulator/izis_simulator.html) in a browser, click **Connect**, and then run `.venv/bin/python hardware/simulator/izis_cmd_test.py` in another terminal. Choose **00** for `STA`. Place a stone in the browser and query again. Use **M** for manual SHP, HOTA and RGC commands from the table. Enter `HCS`, `HSM`, or `ALC` as the expected response prefix. Use **Q** to leave the CLI and **Ctrl+C** to stop the bridge.

The bridge broadcasts between TCP **5000** (all interfaces) and WebSocket **8080** (loopback). Use a trusted development network and restrict inbound TCP 5000 if working locally. The menu includes commands for simulator experimentation, including calibration and dense lighting. Smart Go 0.3.2 uses the installed Binder service for raw board access, so the browser bridge is a separate protocol test setup. Smart Go's Android mock-service and desktop tests exercise its current interfaces.
