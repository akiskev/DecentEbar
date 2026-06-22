# Decent E-Bar

**Version 0.0.5.0**

Android AccessibilityService controller for the Decent Espresso E-Bar — automates pressure profiling by reading live weight and flow from the E-Bar screen and commanding pressure via gesture swipes on the pressure slider, targeting a calibrated LUT. Optionally connects directly to a **Bookoo Mini scale over BLE** for ultra-fast weight and flow readings.

## Features

- **Shot controller** — state machine: `IDLE → ARMED → RUNNING → STAGE_TRANSITION → STOPPING → STOPPED / ERROR`
- **Pressure profiles** — multi-stage profiles with seven stage types:
  - Fixed pressure
  - Time-based pressure ramp
  - Weight-based pressure ramp
  - Flow-limited pressure (PID-like feedback)
  - Yield/time trajectory (output-driven — see below)
  - Hand-drawn pressure curve (direct command vs time or weight — see below)
  - Stop
- **Output-based yield/time profiling** — declare the intent ("30 g out in 30 s on a sweet declining curve") instead of programming pressure: a trajectory planner builds a flow-shape curve (flat / declining / ramp-then-decline / blooming / custom — the **custom curve is drawn by hand** in a full-screen editor, freehand + tap/drag handles, with the target weight computed automatically as the area under it) normalized so its integral equals the target yield, then each tick blends the planned flow with a catch-up flow to stay on the yield/time trajectory — gently raising flow when behind and easing when ahead. The recipe is measured **from first drop**: a pressure-driven pre-infusion phase saturates the puck first (flow is unobservable while the scale reads ~0 g), and only when the puck yields does the 30 s / 30 g clock start, so pre-infusion time isn't charged against the recipe. An **extraction-pressure floor** keeps the back of the shot from coasting at near-zero pressure (released on a confirmed gush), avoiding a thin, under-extracted tail. Late-shot taste protection ramps the correction down near the end (Strict / Balanced / Taste-safe modes) so the final seconds are never rescued with a violent flow spike. The computed target flow is handed to the existing flow→pressure controller (resistance feed-forward or incremental-P, selectable per profile) and the commanded pressure is clamped to a per-second rise/fall envelope — pressure stays inside all the usual safety limits. See [docs/yield-time-trajectory.md](docs/yield-time-trajectory.md)
- **Hand-drawn pressure curve stage** — draw pressure-vs-time or pressure-vs-weight by hand in the same full-screen curve editor and the stage commands it directly (no feedback controller — pressure is the actuator). Each tick the X fraction is computed from elapsed stage time or absolute cup weight, the drawn curve is interpolated (clamped to its endpoints outside the range) and commanded within the stage's min/max pressure; max pressure is both the editor's Y scale and the command cap. A new stage seeds the classic 9-bar-hold → decline-to-6 shape, and the default exit follows the drawn axis: stage time at the drawn duration, or weight at the drawn max weight
- **Curve editor** — shared full-screen editor for both the custom flow curve and the pressure curve: freehand swipe to rough in the shape, then tap to add and drag to refine handles, with axis tick labels and a live value bubble on the active point; the profile editor shows an inline preview of every curve type (the analytic flow shapes are previewed via the planner's normalized output, so what you see is what will be followed)
- **Per-stage exit conditions** — ANY/ALL mode; triggers on weight, stage time, flow >=, flow <=, first drop detected, manual skip, or safety timeout
- **First-drop detection** — threshold-based, optional two-consecutive-reading confirmation
- **Pressure LUT** — built-in 0-12 bar template stored as ratios of the reference 3120×1440 layout, scaled to the device's actual screen size at runtime; nearest-point lookup, linear interpolation, command throttling, and min/max pressure clamping
- **Auto-anchored pressure bar** — at runtime the controller locates the pressure bar in the live accessibility tree (a tall, thin vertical control on the right half of the screen, exposed as a `SeekBar` in 3.0.x or a scrollable `View` in 3.1.0+) and builds the LUT from its actual on-screen bounds, so it follows the bar when an e-bar update moves or resizes it and works on any screen resolution. Each bar value is stored as a **fraction of the bar node's height** (measured per-bar for the 3.1.0 layout, reference-derived for the 3.0.x `SeekBar`), translated to device pixels from the detected bounds; falls back to the static built-in LUT when no bar is found. Detected bounds and derived target points are shown in the Developer Mode Debug tab
- **Slider drag input** — the 3.1.0 bar is an *absolute scroller*: a plain tap opens a manual-entry popup, so pressure is set by **sliding to the target Y and releasing there**. Each command slides from a short hop toward the bar centre (so touch-down clears the dead zones at the ends) down/up to the target; swipe duration scales with distance to hold a constant drag velocity (a fixed-time long jump reads as a fling and is ignored). The 0-bar release extends just past the node bottom — between the +/- buttons — to force a true zero
- **Closed-loop pressure with stall detection** — each command reads the bar's live value back (parsed from the bar node's content-desc) and re-slides until it confirms it landed, recovering the first slide or two the e-bar drops right after Start; it stops once within the closed-loop deadband (wider than the slider's ~±0.2 bar landing jitter, so it settles instead of oscillating). If repeated slides to the same target stop moving the bar while already near it (an unreachable target such as true 0), it holds instead of jittering
- **Pressure bar calibration** — a sweep (Developer Mode LUT tab → Calibrate Bar) slides the bar across its range, reads the value shown at each position, and builds an exact measured pressure→Y LUT, applied for the session; per-bar fractions are logged so a new device's layout can be baked in. The 3.1.0 default fractions were produced this way
- **e-bar version support** — recognises both the `com.g472631889.stf` (3.1.0+ release) and `com.g472631889.stfbeta` (3.0.x beta) packages
- **Bookoo Mini scale BLE integration** — connects directly to a Bookoo Mini scale and uses its native weight and flow data instead of accessibility-parsed screen text; the scale notifies at ~10–20 Hz so the control loop responds much faster than the ~2 Hz accessibility polling; flow rate is read from the scale hardware (no software estimation); when not connected, the app falls back to the accessibility-based path automatically; the software flow estimator still runs in parallel for comparison logging (`altFlowGps` in each sample) and is visible in the Developer Mode Debug tab
- **Flow estimator** (fallback / comparison) — used as the primary flow source when no BLE scale is connected; computes delta-weight / delta-time only when the scale reports a new reading, using the full interval since the last scale update as the denominator; `0.75 prev + 0.25 raw` exponential smoothing; holds the last estimate while the scale reading is unchanged (no artificial decay between updates)
- **Flow-limited pressure control** — PD-like controller with auto-tuned parameters: deadband defaults to 0.1 g/s; correction interval auto-selects 250 ms with BLE scale or 600 ms without; pressure step scales proportionally to the interval so the maximum rate of change in bar/s stays constant regardless of mode; a derivative guard reduces the correction multiplier to 30% when the previous correction is already moving flow toward target (dead-time over-correction prevention); all three parameters can be overridden via JSON profile import; profile editor exposes only Target flow and Pressure cap — everything else is auto-tuned; a global 250 ms hardware floor in `PressureLutManager` prevents commanding the machine faster than it can respond; the correction timer only advances on LUT-accepted commands so rejected commands don't silently waste the correction budget. A stage can opt in to the **resistance feed-forward controller** instead (JSON `feedForward`): it estimates live puck resistance `R = P / flow` online and commands pressure feed-forward toward the target flow, with a per-tick rise cap, conditional pressure floor, and gusher-safe recovery for the loose/channeling pucks that limit-cycle the incremental law — see [docs/puck-resistance-feedforward.md](docs/puck-resistance-feedforward.md)
- **Weight parser** — handles `Wt. ... g` formats including split-line and split-node decimals, graph-axis rejection, and max-weight guard (fallback path only)
- **Shot log** — timestamped samples (weight, flow, pressure, stage, optional `altFlowGps` for scale vs software comparison, and per-sample trajectory telemetry — target weight/flow, corrected target flow, errors, planner mode — on yield/time stages) and events (state transitions, pressure commands, stops, safety errors); stage-exit events include the specific exit condition that fired; flow-cap diagnostic events logged once per stage; saved logs include required beans/grind/dose metadata, optional notes, app version, flow source, scale battery, and the exact profile snapshot for reproducibility; JSON export, self-contained HTML report with interactive chart, and MP4 video export; exported logs can be analyzed offline with [tools/puck_probe_analysis.py](tools/puck_probe_analysis.py) (per-shot `R_index` / `R_main` / α puck-resistance metrics)
- **Shot HTML report** — Chart.js chart with a linear time axis so stage widths are proportional to real time (not sample count); shows flow, pressure, weight, stage bands, first-drop marker, and dashed target-flow lines for flow-limited stages; for yield/time-trajectory stages it overlays the planned target flow, corrected target flow, and target weight curves, and the meta line shows final yield/time error; optional dashed purple "Calc Flow" overlay when `altFlowGps` data is present; responsive and correctly grows back after the browser window is enlarged; event table with colour-coded badges
- **Shot video export** — frame-by-frame H.264 MP4 (30 fps) rendered with Android Canvas; Material Design dark-theme palette: Blue A200 flow, Deep Orange A200 pressure, Teal A200 weight, Amber 500 dashed target-flow lines, MD 900-series stage bands; yield/time-trajectory shots overlay the planned target flow, corrected target flow, and target weight curves in the video too; three aspect-ratio presets (16:9, 1:1, 9:16)
- **Safety** — missing-weight timeout (applies to both BLE and accessibility paths), per-stage max time, accessibility service watchdog, emergency stop with fallback tap coordinates
- **Service lifecycle** — accessibility polling (~20 Hz) only runs while armed and the E-Bar app is foreground; idle otherwise, so the service has no background overhead when not in use

## UI

Landscape-only. NavigationRail on the left. `0.0.5.0` keeps the day-to-day workflow focused on Control, Profile, Log, and About; **Developer Mode** in About reveals the advanced LUT/Debug tools and raw JSON panes.

| Rail item | Contents |
|-----|----------|
| **Control** | Arm / Disarm / E-Stop / Skip Stage / Scale connect button, 17-metric live status grid including scale connection state and battery |
| **Profile** | Full-width stage editor with a collapsible profile panel; sliders for numeric fields; yield/time and pressure-curve stages show an inline preview and open the full-screen curve editor; profile CRUD plus Save/Load JSON file actions; raw JSON import/export fields are shown in Developer Mode |
| **Log** | Shot events, sample counts, and "Save to File" export; saving requires beans, grind, and dose so JSON/HTML logs are useful for analysis; aspect-ratio selector and "Save Video" button for MP4 export with progress bar; Developer Mode adds raw JSON export, log import, imported-log re-export, and recent-sample inspection |
| **About** | Version, safety notice, feedback/support links, donation links, and the Developer Mode switch |
| **LUT** *(Developer Mode)* | LUT status (auto-anchored to the live bar), pressure test slider, "Calibrate Bar" sweep, read-only JSON export |
| **Debug** *(Developer Mode)* | Accessibility snapshot metrics, pressure-bar anchor (detected bar bounds + derived 0/12-bar tap points), per-node bounds list and raw text values; live "Scale vs Calc flow" comparison panel when BLE scale is connected |
| **Support** | NavigationRail menu with PayPal and Ko-fi links |

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

## Default profiles

One built-in profile is bundled with the app. It is seeded on first run and re-seeded after an app update that changes the bundle (a stored profile with the same name is overwritten by the bundled version; profiles under other names are untouched):

**Lever sim 17g in 39g out** — single-stage pressure curve, target 39 g, stop offset 0.9 g, max shot time 120 s:

1. **Main** — hand-drawn pressure-vs-weight curve: starts around 7.5 bar at 0 g, rises to 9 bar by about 5.3 g, holds 9 bar until about 20 g, then declines through about 7.5 bar at 25 g to about 6.5 bar at 39 g. The stage exits at 39 g or after about 60 s; the profile-level 0.9 g stop offset starts stopping at 38.1 g to coast into target.

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
3. Select the bundled **Lever sim 17g in 39g out** profile or create/import a profile in the **Profile** tab, then tap **Save**. The pressure LUT is built-in and auto-anchors to the live bar — no import needed.
4. *(Optional)* In the **Control** tab, tap **Scale** to connect a Bookoo Mini scale over BLE for faster weight and flow readings.
5. *(Optional)* Open **About** and enable **Developer Mode** if you need LUT calibration, Debug, raw profile JSON, or full shot-log import/export tools.
6. Tap **Arm**, then start a shot in the E-Bar app — the controller takes over automatically.

## License

Source-available under the PolyForm Noncommercial License 1.0.0. Noncommercial use is free, including personal, hobby, research, educational, charitable, government, and public-interest use. Paid or otherwise commercial projects require separate permission from the author.

If you use, copy, modify, or share this software, mention `akiskev <akiskev@gmail.com>` and keep the required notice from [LICENSE](LICENSE) with the software.
