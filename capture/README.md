# capture/ — reference dumps (redacted)

Baseline `dumpsys`/`getprop` output from the original Pixel 9 + Pixel Watch 4
bring-up, kept so transport and capability claims in `docs/` are checkable.

**Redactions** (applied throughout, placeholders in angle/`XX` form):
- Bluetooth MACs → `XX:XX:XX:XX:…` (last octet kept so log lines stay joinable)
- USB/Wi-Fi-adb serials → `<phone-serial>`, `<watch-serial>`, `<adb-tls-name>`
- Wear node IDs → `<watch-node>`, `<phone-node>`, `<old-node>` (stale pairings)
- LAN addresses → `192.168.x.x`
- Watch BLE device-name serial suffix dropped

Firmware build strings and dates are intentionally kept — they are public
release identifiers, not device identity.

To re-capture live (see `docs/00-watch-setup.md` for watch Wi-Fi debugging):
```
adb -s <phone> shell dumpsys bluetooth_manager > capture/bt-dumpsys.txt
adb -s <phone> shell getprop > capture/phone-getprop.txt
adb -s <watch> shell dumpsys bluetooth_manager > capture/watch-bt-dumpsys.txt
adb -s <watch> shell getprop > capture/watch-getprop.txt
adb -s <watch> shell cmd package list packages > capture/watch-packages.txt
```
Re-apply the redactions above before committing.
