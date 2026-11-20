# Additional IZIS protocol notes

Adapted from wjchen-vlsi's [reverse-engineered protocol guide](https://github.com/wjchen-vlsi/izis_go_board_reng/blob/main/izis_protocol_spec.md). The [hardware guide](README.md#commands-and-coordinates) covers the main verified path, framing and practical LED examples. The following are manufacturer-document or older implementation observations. Semantics vary by firmware and the simulator models only its own implementation.

| Frame | Response | Notes |
| --- | --- | --- |
| `~BOD09#`, `~BOD13#`, `~BOD19#` | `~GBS09#`, `~GBS13#`, `~GBS19#` | Changes logical dimensions globally. The older simulator models 81, 169 and 361 sensor digits. The published protocol also lists 15. Smaller sizes may draw a green frame. Clear it with `RGC` after returning to 19. Confirm the actual MCU's payload and indexing before using these modes. |
| `~LED11#`, `~LED21#` | `~LOS#` | Turn on the black/white side indicator. The observed older implementation treats the two indicators as mutually exclusive here. `LED10` or `LED20` turns them off. |
| `~TLO181,r000g008b000,1#` | `~TLS#` | Updates a single point with turn indicators. The older implementation uses modes 0 off, 1 top, 2 bottom, 3 both. |
| `~RGF#` | `~SIC#` (older implementation) | Clear interior while preserving the boundary. Verify acknowledgement and timing for the MCU. |
| `~SAL…#`, `~SAM…#`, `~SAW…#` | `~ALS#` | High/medium/low matrix modes. The manufacturer PDF defines 0 as preserve, 1 green, 2 white. The older simulator rewrites the matrix, so a zero-filled simulation does not verify hardware clear. |
| `~SAR…#` | `~ALS#` | Full matrix, richer palette, and 0 off in the manufacturer PDF. Older tests observed 1/3/8 green, 2/4/9 white, 5 red, 6 magenta and 7 peach. Keep dense or high-brightness tests off hardware until the power budget is established. |
| `~RLO#`, `~RLT#`, `~RLW#` | `~DSC#` (documented) | Circle, check and cross patterns. The older application could send these without waiting for the reply. |
| `~AWO#` | No reply expected | Prompt buzzer. Older `AWS` and `AWT` warnings were silent on tested revisions. |

The SDK's inspected pacing table extends `RGC` and `FLL` to three times its normal 80 ms delay. Older local tools treat `RGF` as heavy too. Older transports used roughly 500 ms reply timeouts and finite retries. Avoid blanket retries: a late acknowledgement is not correlated by a transaction ID and retries can repeat a real effect.

Legacy material mentions `GSV` for reading a possible Hall sensor threshold, `SEP` for changing it and `ADJ` for calibration. Their exact semantics and persistence remain unverified here. Leave calibration and threshold changes to the manufacturer workflow.
