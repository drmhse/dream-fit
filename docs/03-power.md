# 03 — Power: why the bridge doesn't move the needle

Measured 2026-09-05, Pixel Watch 4 (kenari, 325mAh/41mm class), Wear node db397e60.

## What we changed (v0 → v1)
- v0 (lab only): `LOW_LATENCY` (100ms interval) + `TX MEDIUM` (~0dBm), always-on → ~5–10mA while advertising. Would have been visible on battery. Deleted.
- v1 (current): `LOW_POWER` (~1000ms interval) + `TX LOW` (~-12dBm, room-scale), 30s burst / 5min rest (9% duty), **adv off when connected**, GATT notify-only (no polling).

## Math
- BLE adv v1 during burst: ~0.3–0.8mA avg (Nordic/Dialog figures for 1s interval, 0dBm→-12dBm saves ~2×).
- Duty-averaged: 0.05–0.08mA → 0.4–0.6mAh/day → **0.15% of a 325mAh charge**.
- GATT server idle: ~0. Connected link with 1 notify/min: <0.2mA avg.
- Existing load for context: BT Classic Wear Data Layer 10–20mA bursty, HR sensor 5–10mA while sampling, screen 80–150mA, WiFi 30–60mA. Display-on for 5 min costs more than our bridge for a week.

Verified live: `PixelBridge: adv ok low-power`, Mac BLE scan sees `Pixel Watch 4 ... 6e400001 rssi -65` during burst, 0 extra wake-locks (`dumpsys batterystats` shows no PixelBridge wakelock), battery 73% → 91% on charger during full bring-up.

## Guardrails in code (`BridgeService.kt`)
- Advertising stops when **our app subscribes to TX**, and re-bursts when the
  last subscriber drops. Never gated on raw connection state: after iPhone
  pairing the system holds an ANCS/HFP link permanently, and gating on that
  would suppress discovery of our service forever.
- No `SCAN_MODE_*`, no background jobs, no polling — HR and the hardware step
  counter are both sensor-event driven (the step counter is a low-power
  hardware register, not a wake source), aggregates are pushed on change plus a
  15-minute heartbeat, and step-only changes push at most once a minute.
- If RSSI proves marginal at -65dBm room-scale, bump to `TX MEDIUM` before shortening interval — power cost of +3dB TX is cheaper than 10× interval.

To re-verify any time: `adb -s $W shell dumpsys batterystats | grep pixelbridge`, Mac `python3 /tmp/scan_bridge.py`.
