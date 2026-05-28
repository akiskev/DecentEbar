# Decent E-Bar

Android AccessibilityService controller for the Decent Espresso E-Bar — automates pressure profiling by reading live weight and flow from the E-Bar screen and commanding pressure via gesture taps on a calibrated LUT.

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
- **Flow estimator** — delta-weight / delta-time with `0.75 prev + 0.25 raw` exponential smoothing
- **Weight parser** — handles `Wt. ... g` formats including split-line and split-node decimals, graph-axis rejection, and max-weight guard
- **Shot log** — timestamped samples (weight, flow, pressure, stage) and events (state transitions, pressure commands, stops, safety errors), JSON export
- **Safety** — missing-weight timeout, per-stage max time, accessibility service watchdog, emergency stop with fallback tap coordinates
- **Service lifecycle** — accessibility polling (~20 Hz) only runs while armed and the E-Bar app is foreground; idle otherwise, so the service has no background overhead when not in use

## UI

Landscape-only. NavigationRail on the left, five tabs:

| Tab | Contents |
|-----|----------|
| **Control** | Arm / Disarm / E-Stop / Skip Stage, 15-metric live status grid |
| **Profile** | Full-width collapsible stage editor with sliders for all numeric fields; profile CRUD and JSON import/export in a side panel |
| **LUT** | LUT status (auto-scaled to screen), pressure test slider, read-only JSON export |
| **Debug** | Accessibility snapshot metrics, raw content-desc and text values |
| **Log** | Shot events, recent samples, JSON export |

All numeric parameters use a slider with an inline editable text field for precise entry. Optional fields (nullable) are enabled/disabled with a toggle switch.

## Default profile

"First Drop PI + Flow Control + Fade" — five stages:

1. **Preinfusion** — 2 bar fixed, exits at first drop (0.1 g) or 20 s
2. **Ramp** — 2 → 8 bar over 4 s
3. **Main** — flow-limited at 1.5 g/s, 8.5 bar cap, exits at 28 g
4. **Fade** — weight ramp 8 → 5 bar from 28 g to 35 g
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
4. In the **Control** tab, tap **Arm**, then start a shot in the E-Bar app — the controller takes over automatically.

## License

Source-available under the PolyForm Noncommercial License 1.0.0. Noncommercial use is free, including personal, hobby, research, educational, charitable, government, and public-interest use. Paid or otherwise commercial projects require separate permission from the author.

If you use, copy, modify, or share this software, mention `akiskev <akiskev@gmail.com>` and keep the required notice from [LICENSE](LICENSE) with the software.
