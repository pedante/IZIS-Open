# IZIS Open — 隐智电子棋盘

Unofficial community project for the IZIS electronic Go board. The board documented here is the UniWin M339 with a Rockchip RK3399, Android 7.1 (API 25), and 4 GB RAM.

- [Hardware developer guide](hardware/README.md) covers the internal UART, the installed Android Binder service, sensor snapshots, LEDs and clock buttons. It includes a read-only UART example and links to the working Smart Go service client.
- [Smart Go 0.3.2](smart-go/README.md) covers building the Android app and connecting it to local KaTrain/KataGo.
- [Browser simulator and TCP/WebSocket bridge](hardware/README.md#simulator-and-bridge) let you exercise protocol commands without a board.

## Credits

- **yzkj2213** published the Chinese IZIS serial SDK, including `SerialConnectDirect`, the service client and the manufacturer's protocol PDF. [CodeLibSerialPort](https://github.com/yzkj2213/CodeLibSerialPort)
- **xieren58** published the Chinese IZIS protocol demo for the older PL2303 USB transport. [ProtocolDemo](https://github.com/xieren58/ProtocolDemo)
- **wjchen-vlsi** published the reverse-engineered protocol notes, browser simulator, command CLI and original Android TCP bridge. [izis_go_board_reng](https://github.com/wjchen-vlsi/izis_go_board_reng) · [IZISBridge](https://github.com/wjchen-vlsi/IZISBridge)
- **SaberZan** published an independent Unity integration that uses the direct serial SDK. [yinzhi_qipan_weiqi](https://github.com/SaberZan/yinzhi_qipan_weiqi)
- **sanderland**, **lightvector** and their contributors maintain the desktop app and analysis engine used by Smart Go. [KaTrain](https://github.com/sanderland/katrain) · [KataGo](https://github.com/lightvector/KataGo)

The project retains its [MIT license](LICENSE) and the [simulator's original MIT notice](hardware/simulator/LICENSE). The Android source includes minimal AIDL declarations for interoperability with the installed launcher. The manufacturer APKs, native libraries, Chinese SDK and proprietary dependencies are external to this repository. Public source access alone does not grant redistribution rights for those components.
