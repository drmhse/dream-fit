# 02 — Baseline (2026-09-05, live)

## Devices
- Phone: Pixel 9 tokay, Android 17 (SDK 37), USB `4A020DLAQ0063T`, BT `XX:XX:XX:XX:EE:B1`
- Watch: Pixel Watch 4 (kenari_btwifi), Android 17 (SDK 37), build `CP2A.260603.001.S1`, 73%, WiFi ON, BT `64:9D:38:22:A9:3F`
- iPhone: 16 Pro Max, iOS 26.6.1, paired to Xcode
- adb over WiFi: paired `192.168.100.7:36267`, TLS device `adb-59031WRBNW40EW-enY6Bw`

## Transport (why not a pairing clone)
- Phone dumpsys: `ConnectionConfiguration[Name=64:9D:38:22:A9:3F, Type=BTD, IsConnected=true, PeerNodeId=db397e60, BtlePriority=true, peerSupportsBle=false]`
- Bonded UUID `df21fe2c-2515-4fdb-8886-f12c4d67927c` (Wear RFCOMM) + HFP/HSP/A2DP roles
- iOS has no BR/EDR RFCOMM for non-MFi + no GMS → clone is dead. Side-channel it is.

## Watch capabilities (for bridge)
- Sensors: heartrate, ecg, accelerometer, gyro, barometer, stepcounter/stepdetector, compass, light
- Health: `healthservices` v1491535 (targetSdk 34), FitbitMobile, fitness, Health Connect controller
- BT: `bluetooth` + `bluetooth_le` + `channel_sounding`, GATT advertiser present (empty now)
- Node: `db397e60` ↔ phone `95dad3a3`, cloud route alive

## Captures in `capture/`
- phone: wearable-dumpsys, bt-dumpsys, getprop
- watch: watch-getprop, watch-bt-dumpsys, watch-packages

Next: `03-power.md` for the advertising budget, `01-protocol.md` for the wire format.
