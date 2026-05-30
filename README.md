# Decent E-Bar

Android AccessibilityService controller for the Decent Espresso E-Bar — automates pressure profiling by reading live weight and flow from the E-Bar screen and commanding pressure via gesture taps on a calibrated LUT. Optionally connects directly to a **Bookoo Mini scale over BLE** for ultra-fast weight and flow readings.

## Features

- **Shot controller** — state machine: `IDLE → ARMED → RUNNING → STAGE_TRANSITION → STOPPING → STOPPED / ERROR`
- **Pressure profiles** — multi-stage profiles with five stage types:
  - Fixed pressure
  - Time-based pressure ramp
  - Weight-based pressure ramp
  - Flow-limited pressure (PID-like feedback)
  - Stop
- **Per-stage exit conditions** — ANY/ALL mode; triggers on weight, stage time, flow >=, flow <=, first drop detected, manual skip, or safety timeout
- **First-drop detection** — threshold-based, optional two-consecutive-reading confirmation
- **Pressure LUT** — built-in 0-12 bar template stored as ratios of the reference 3120×1440 layout, scaled to the device's actual screen size at runtime; nearest-point lookup, linear interpolation, command throttling, and min/max pressure clamping
- **Bookoo Mini scale BLE integration** — connects directly to a Bookoo Mini scale and uses its native weight and flow data instead of accessibility-parsed screen text; the scale notifies at ~10–20 Hz so the control loop responds much faster than the ~2 Hz accessibility polling; flow rate is read from the scale hardware (no software estimation); when not connected, the app falls back to the accessibility-based path automatically
- **Flow estimator** (fallback) — used when no BLE scale is connected; computes delta-weight / delta-time only when the scale reports a new reading, using the full interval since the last scale update as the denominator; `0.75 prev + 0.25 raw` exponential smoothing; holds the last estimate while the scale reading is unchanged (no artificial decay between updates)
- **Flow-limited pressure control** — PD-like controller: correction size scales linearly with how far flow is from target (`step × clamp(error / deadband, 0, maxMultiplier)`), so the controller is gentle near the setpoint and aggressive during runaway shots (e.g. coarse grind); a derivative guard checks whether the previous correction is already moving flow toward the target — if so, the multiplier is reduced to 30% to prevent stacking large corrections faster than the system can respond (dead-time over-correction); `pressureStepBar` sets the base step at the deadband edge; `pressureStepMultiplierMax` (default 8×, not exposed in UI) caps the maximum single correction; `correctionIntervalMs` (default 600 ms, not exposed in UI) ensures each decision uses a fresh scale reading; a global `pressureCommandIntervalMs` (400 ms) in `PressureLutManager` acts as a hard hardware floor
- **Weight parser** — handles `Wt. ... g` formats including split-line and split-node decimals, graph-axis rejection, and max-weight guard (fallback path only)
- **Shot log** — timestamped samples (weight, flow, pressure, stage) and events (state transitions, pressure commands, stops, safety errors); stage-exit events include the specific exit condition that fired (e.g. "weight 27.2 g ≥ 27.0 g", "first drop detected", "time limit 1500 ms reached"); flow-cap diagnostic events logged once per stage when target flow cannot be reached due to pressure cap; JSON export, self-contained HTML report with interactive chart, and MP4 video export
- **Shot HTML report** — Chart.js chart (flow/pressure/weight over time, stage bands, first-drop marker) plus a full event table with colour-coded badges; saved alongside the JSON in a single "Save to File" action
- **Shot video export** — frame-by-frame H.264 MP4 (30 fps) rendered with Android Canvas: building graph lines, coloured stage bands with labels, live value overlay, first-drop marker; three aspect-ratio presets (16:9, 1:1, 9:16) selectable in the Log tab; progress bar during encoding
- **Safety** — missing-weight timeout (applies to both BLE and accessibility paths), per-stage max time, accessibility service watchdog, emergency stop with fallback tap coordinates
- **Service lifecycle** — accessibility polling (~20 Hz) only runs while armed and the E-Bar app is foreground; idle otherwise, so the service has no background overhead when not in use

## UI

Landscape-only. NavigationRail on the left, five tabs:

| Tab | Contents |
|-----|----------|
| **Control** | Arm / Disarm / E-Stop / Skip Stage / Scale connect button, 17-metric live status grid including scale connection state and battery |
| **Profile** | Full-width collapsible stage editor with sliders for all numeric fields; profile CRUD and JSON import/export in a side panel |
| **LUT** | LUT status (auto-scaled to screen), pressure test slider, read-only JSON export |
| **Debug** | Accessibility snapshot metrics, raw content-desc and text values |
| **Log** | Shot events and recent samples; "Save to File" saves both JSON log and HTML report; aspect-ratio selector and "Save Video" button for MP4 export with progress bar |

All numeric parameters use a slider with an inline editable text field for precise entry. Optional fields (nullable) are enabled/disabled with a toggle switch.

## Bookoo Mini scale

Tap **Scale** in the Control tab to scan and connect. The app requests the necessary Bluetooth permissions on first use. Once connected:

- Weight and flow are sourced directly from the scale at hardware notification rate (~10–20 Hz) rather than from the E-Bar screen (~2 Hz accessibility polling).
- Flow smoothing is enabled on the scale automatically at connect time.
- The scale's shot timer is started when the controller transitions to RUNNING, which activates the scale's native flow computation.
- The accessibility service remains active for foreground detection and pressure tap dispatch — only the weight/flow source changes.
- If the scale disconnects mid-shot, the 2-second missing-data watchdog triggers a safety stop.
- Tap the button again (shown in the secondary colour when connected) to disconnect.

No pairing is required. The app scans for BLE devices advertising service UUID `0x0FFE` (the Bookoo Mini service).

## Default profile

**Flow 34** — five stages, target 33 g, max shot time 45 s:

1. **Preinfusion** — 7 bar fixed, exits on first drop (two consecutive readings required) or after 15 s
2. **Wait** — 0 bar (pump off), exits when weight ≥ 6 g or after 5 s
3. **Main** — flow-limited at 1.9 g/s ± 0.1 g/s deadband, 9 bar cap, corrects every 600 ms, exits at 27 g; pressure ramps up naturally from 0 bar via the P controller rather than a fixed time ramp
4. **Fade** — flow-limited at 1.6 g/s ± 0.1 g/s deadband, 8 bar cap, corrects every 600 ms, pressure ramps 8 → 5 bar from 28 g to 35 g, exits at 32 g
5. **Stop**

## Build

| Setting | Value |
|---------|-------|
| `compileSdk` | 35 |
| `minSdk` | 26 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |

## Setup

1. Open the project in Android Studio and build/install the app.
2. On the device, go to **Settings → Accessibility** and enable *Decent E-Bar*.
3. Select or create a shot profile in the **Profile** tab and tap **Save**. The pressure LUT is built-in and auto-scales to your screen — no import needed.
4. *(Optional)* In the **Control** tab, tap **Scale** to connect a Bookoo Mini scale over BLE for faster weight and flow readings.
5. Tap **Arm**, then start a shot in the E-Bar app — the controller takes over automatically.

## License

Source-available under the PolyForm Noncommercial License 1.0.0. Noncommercial use is free, including personal, hobby, research, educational, charitable, government, and public-interest use. Paid or otherwise commercial projects require separate permission from the author.

If you use, copy, modify, or share this software, mention `akiskev <akiskev@gmail.com>` and keep the required notice from [LICENSE](LICENSE) with the software.
