# 00 — What to enable on the watch (you must do this, I can't via adb yet)

The watch isn't on adb — it's tethered through the phone over BT Classic. To get a direct shell, put the watch on WiFi debugging:

**On the Pixel Watch 4:**
1. Settings > System > About > Versions > tap **Build number 7x** until "You are a developer"
2. Settings > Developer options > turn ON:
   - **ADB debugging**
   - **Debug over Wi-Fi** (on some builds called Wireless debugging) — leave the screen on, note the `IP:port` shown, e.g. `192.168.1.42:5555`
   - Optional but useful: Stay awake when charging, Bluetooth HCI snoop log (for baseline)
3. Put watch on the **same WiFi as this Mac** (Settings > Connectivity > WiFi)
4. Keep it on charger, screen awake while we pair

**On the Pixel 9 (already have):**
- Developer options > USB debugging is ON (confirmed — `4A020DLAQ0063T device`)
- No need to unpair the watch. Keep Pixel Watch app running.

**On the iPhone 16 Pro Max (already paired to Xcode):**
1. Settings > Privacy & Security > **Developer Mode > ON** (reboot if asked)
2. Settings > General > Device Management — trust your dev cert when Xcode installs
3. Keep Bluetooth ON, and install **nRF Connect** (free) — we'll use it to verify our GATT service before the iOS app exists

Once you have the watch `IP:port`, tell me and I run:
```
~/Library/Android/sdk/platform-tools/adb connect 192.168.x.x:5555
adb devices
```
then I can pull `dumpsys bluetooth_manager`, Wear capabilities, and install our Wear stub.
