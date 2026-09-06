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
3. Keep Bluetooth ON. **nRF Connect** (free) is still useful for confirming the
   watch is advertising, but it can no longer read the service: the
   characteristics require an encrypted, MITM-protected link, so a scanner that
   is not the bonded phone gets refused. Seeing `6E400001` in the advertisement
   and being rejected on connect is the hardening working, not a fault.

**Connecting from the dev machine:**

Wireless debugging hands out a one-shot pairing port and a separate connect
port; both are shown on the watch, and they are different numbers.
```
adb pair <watch-ip>:<pairing-port> <code>
adb connect <watch-ip>:<connect-port>
adb devices
```
From there: `dumpsys bluetooth_manager`, Wear capability checks, and installing the Wear app (`capture/` holds redacted reference dumps — see `capture/README.md`).
