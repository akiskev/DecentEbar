# Output-Based Yield/Time Flow Profiling — Design

Status: **Implemented**
Owner: akiskev
Last updated: 2026-06-09

## 1. TL;DR

A new stage type, `YIELD_TIME_TRAJECTORY`, lets a profile express an *extraction intent*
("30 g out in 30 s on a sweet declining curve") instead of programming pressure or a fixed
target flow. A trajectory **planner** computes the required target flow in real time and
hands it to the existing flow→pressure controller, which commands pressure under all the
usual safety limits. Pressure is never commanded directly to "rescue" the target — the
planner only ever produces a *target flow*, and the lower-level controller + its safety
envelope stay in charge of pressure.

```
Yield/time stage config
    → YieldTimeTrajectoryPlanner        (target flow, pure)
    → flow→pressure controller          (feed-forward OR incremental-P)
    → per-second pressure rate envelope (rise/fall clamp)
    → pressure LUT / slider
    → E-Bar
```

## 2. The planner — `engine/YieldTimeTrajectoryPlanner.kt`

Pure JVM, no Android deps, so it is unit-tested and offline-replayable (like
`FlowFeedForwardController`). `configure(config)` once per stage entry; `evaluate(...)` is
pure and may be called repeatedly per tick.

### 2.1 Curve generation & normalization

The flow shape is a piecewise-linear curve over `p = t / duration`:

- **FLAT** — constant.
- **DECLINING** — start → end.
- **RAMP_THEN_DECLINE** — start → peak (at `peakAtPct`) → end. *Sweet/rich default.*
- **BLOOMING_DECLINE** — low flat bloom, then ramp to peak, then decline.
- **CUSTOM_POINTS** — interpolated user points, authored in-app with the **graphical curve
  editor** (`ui/FlowCurveEditor.kt`): the user draws flow-vs-time (freehand swipe to rough in, then
  tap/drag handles to refine) and the **target weight is computed automatically** as the area under
  the curve (`CurveMath.areaG`, shared with the planner so the drawn weight == the planner's yield).
  On save the editor sets `targetYieldG = area`, which makes the normalization below a no-op — so a
  hand-drawn curve is followed **verbatim** as the planned flow (with `correctionStrength` still
  nudging toward that same yield if non-zero).

Whatever the shape, it is **normalized so its integral equals `targetYieldG`**: the raw
shape's area over the duration is computed (trapezoid), and every knot flow is scaled by
`targetYieldG / rawArea`. So `startFlowGps`/`peakFlowGps`/`endFlowGps` describe the *shape*,
not absolute values — `plannedWeightAt(targetDurationS) == targetYieldG`.

### 2.2 First-drop anchoring (pre-infusion vs extraction)

Flow is **unobservable until first drop** — the scale reads ~0 g while the puck saturates, so
there is no flow to track and no yield to count. The stage therefore has two phases (handled in
`runYieldTimeTrajectoryStage`):

- **Pre-infusion (before first drop):** hold `preInfusionPressureBar` to saturate the puck. The
  recipe clock and flow control are paused. `preInfusionMaxS` force-starts extraction if first
  drop never arrives. If first drop was already detected upstream (e.g. a separate pre-infusion
  stage), this phase is skipped.
- **Extraction (first drop onward):** the trajectory clocks from the first-drop instant and yield
  is counted from the first-drop weight (`yieldExtractionStart*`). So `targetYieldG` /
  `targetDurationS` are measured **from first drop**, and the pre-infusion dead time is *not*
  charged against the recipe — without this the planner spends the whole shot "catching up" from
  a deficit it can never recover.

The extraction phase hands off from the pre-infusion pressure (not 0 or the cap), which also
gives both controllers a sensible starting pressure. Each extraction tick:

```
plannedFlow, plannedWeight = curve(elapsed)
catchupFlow  = (targetYield − yieldGained) / max(floor, duration − elapsed)
effStrength  = correctionStrength * lateFactor * modeFactor
corrected    = plannedFlow + effStrength * (catchupFlow − plannedFlow)
corrected    = corrected.coerceIn(minFlow, maxFlow)
```

- Behind schedule → `catchupFlow > plannedFlow` → corrected rises (within limits).
- Ahead of schedule → corrected eases below planned.

### 2.3 Late-shot taste protection

Inside `lateShotCorrectionLimitS` of the end, `lateFactor = remainingTime / lateLimit`
ramps the correction toward 0 so the final seconds can't be rescued with a violent flow
spike. `TastePriorityMode` modulates this:

- **STRICT_TARGET** — keeps a correction floor (still chases late).
- **BALANCED** — linear late ramp-down.
- **TASTE_SAFE** — late ramp-down **plus** a hard cap: inside the late window an increase
  may not exceed 25 % over the planned flow. Prefers a slightly short/late shot over a
  violent pressure rise.

`evaluate` reports a `mode` string (`PLANNED` / `CATCHUP` / `EASING` / `LATE_LIMIT` /
`TASTE_CAP`) for logging.

## 3. Controller wiring — `ui/ShotController.kt`

`runYieldTimeTrajectoryStage` evaluates the planner, then feeds `correctedTargetFlowGps`
to the selected flow→pressure controller:

- **Feed-forward** (`yieldTime.feedForward != null`) — reuses `FlowFeedForwardController`
  (cap, per-tick rise cap, conditional floor, gusher recovery for free). Same convention
  as `FLOW_LIMITED_PRESSURE`.
- **Incremental-P** (default) — reuses the shared `incrementalPNextPressure` step, the same
  deadband/step law `FLOW_LIMITED_PRESSURE` uses.

The controller's proposed pressure is then clamped to the stage's **per-second rise/fall
rate envelope** (`applyPressureRateEnvelope`), applied uniformly regardless of which
controller produced it. The fall clamp is **bypassed only on a confirmed gush**
(`detectGush`): flow must exceed the corrected target by a margin (`GUSH_MARGIN_GPS`,
0.6 g/s), be *rising* vs the previous tick, with weight *at/ahead of plan*, sustained for
two consecutive control ticks. A single high-flow reading therefore can't trigger an
aggressive (self-oscillating) pressure drop — and even without the bypass, pressure still
falls, just at the normal rate limit.

The result is finally clamped to `[floor, cap]`. The **extraction floor** is
`max(minPressureBar, minExtractionPressureBar)` in normal control, but drops to the hard
`minPressureBar` during a confirmed gush. `minExtractionPressureBar` exists because a flow
trajectory alone doesn't guarantee extraction: on a loose puck the feed-forward law eases
pressure toward ~0 to keep flow on the (declining) target, and the back third of the shot
then coasts at near-zero pressure — adding weight without extraction, i.e. a thin, sour,
under-extracted tail. The floor holds real extraction force through the shot; unlike
`minPressureBar` it does **not** raise the pre-infusion pressure (pre-infusion is commanded
before the floor applies) and it is released on a gush so a channeling puck can still be
arrested.

The planner never bypasses the pressure-safety layer: every `SafetyConfig` limit,
missing-weight watchdog, scale-disconnect stop, stage/shot max-time stop, and the global
target-stop (`targetWeightG − stopOffsetG`) remain in force.

## 4. Logging & reports

Each `ShotSample` on a yield/time stage carries `targetWeightG` (absolute),
`targetFlowGps` (planned), `correctedTargetFlowGps`, `weightErrorG`, `flowErrorGps`,
`trajectoryProgressPct`, and `plannerMode`. The HTML report (`ShotHtmlExporter`) and MP4
frames (`ShotFrameRenderer`) overlay the planned target flow, corrected target flow, and
target weight curves; the HTML meta line shows final yield/time error. Non-yield shots are
unchanged (the fields are null and the overlays are presence-gated).

Events: stage-entry intent (`yield/time 30g / 30s, ramp then decline curve (…control)`),
pressure-capped, late-shot correction limiting, taste-safe cap engaged.

## 5. Backward compatibility

All changes are additive: a new `StageType` value, a default-null `ProfileStage.yieldTime`
config, and default-null `ShotSample` fields. Existing fixed-pressure, ramp, flow-limited,
and stop stages and their logs are untouched. A built-in `DefaultProfiles.sweet30in30`
profile ships the spec's 30 g / 30 s ramp-then-decline example.
