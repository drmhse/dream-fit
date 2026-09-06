# 00 — Preparing the watch for development

The watch has no direct adb connection in normal use — it is tethered through
the companion phone over BT Classic. For development, put the watch on Wi-Fi
debugging to get a direct shell.

**On the Pixel Watch:**
1. Settings > System > About > Versions > tap **Build number 7x** until developer mode is enabled
2. Settings > Developer options > turn ON:
   - **ADB debugging**
   - **Debug over Wi-Fi** (on some builds called Wireless debugging) — keep the screen on and note the `IP:port` shown
   - Optional but useful: Stay awake when charging, Bluetooth HCI snoop log (for baseline captures)
3. Put the watch on the **same Wi-Fi as the dev machine** (Settings > Connectivity > WiFi)
4. Keep it on charger with the screen awake while pairing

**On the companion phone:**
- Developer options > USB debugging ON
- No need to unpair the watch. Keep the Pixel Watch app running.

**On the iPhone:**
1. Settings > Privacy & Security > **Developer Mode > ON** (reboot if asked)
2. Settings > General > Device Management — trust the dev certificate when Xcode installs
3. Keep Bluetooth ON, and install **nRF Connect** (free) — used to verify the GATT service before/while the iOS app is involved

**Connecting from the dev machine:**
```
adb connect <watch-ip>:5555
adb devices
```
From there: `dumpsys bluetooth_manager`, Wear capability checks, and installing the Wear app (`capture/` holds redacted reference dumps — see `capture/README.md`).
