# Puck Resistance Feed-Forward Control — Design & Rollout Plan

Status: **Phase 0 complete; Phase 1 controller implemented (behind a per-stage flag) — see §0, §5**
Owner: akiskev
Last updated: 2026-06-05

## 0. TL;DR — what Phase 0 changed

We set out to seed a feed-forward controller from a **preinfusion probe** (`R_index`,
the pressure–time integral to first drop) and adapt online. Phase 0 (8 logged shots,
incl. a controlled grind×preinfusion experiment and same-condition replicates) gave two
decisive results:

1. **Preinfusion pressure is a *treatment*, not just a measurement.** Lowering
   preinfusion pressure physically loosens the puck (confirmed — see §3). So `R_index`
   is "effective resistance under *this* preinfusion," not an intrinsic property, and the
   probe→Main mapping constant **α depends on preinfusion pressure** (calibrate per
   profile, which fixes preinfusion).

2. **The probe predicts the *grind*, not the *shot*.** Across grinds, `R_index` tracks
   resistance beautifully. But at **identical conditions**, `R_index` is rock-steady
   (CoV ~2%) while the Main resistance `R_main` scatters **~30% (clean) to 3× (with a
   channel)**. Shot-to-shot puck prep — distribution, tamp, mid-shot channels — swamps
   the probe, and that prep variation is exactly what causes the gushers we want to fix.
   A mid-shot channel (shot 06-05 09:38) was *invisible* to every pre-Main signal.

**Consequence — the plan is re-weighted:**

- **Live feedback (online `R = P/Q`) + gusher-safe recovery is the project.** This is
  where robustness comes from, because only the live loop sees the real puck.
- **The preinfusion probe is demoted** to a *weak grind-level seed* (useful mainly when
  grind changes) and a *diagnostic* (grind-drift / "this grind can't reach target"). Its
  2% repeatability makes it excellent for that — and useless as a per-shot R predictor.
- **Prevention** (consolidating preinfusion, adequate/conditional Wait) reduces prep
  variation upstream but cannot eliminate it.

The rest of this doc is updated to that reality. The original probe hypothesis and its
(now-superseded) optimism are preserved in the appendix for the record.

## 1. Problem

The flow-limited Main stage ([`ShotController.runFlowLimitedStage`](../app/src/main/java/dev/akiskev/decentebar/ui/ShotController.kt)) uses an
incremental proportional controller:

```
ΔP = ±step · min(|flow − target| / deadband, maxMult)   every ~250 ms
   = ±0.2 · min(|err|/0.1, 8)  →  up to ±1.6 bar per correction
clamped to [minPressureBar=0, cap]
```

On a **well-prepped, resistive puck** this is stable: flow stays at/below target,
pressure pins high then eases down to a steady value, done.

On a **loose / channeling puck** it limit-cycles (`Flow 29 dark` 11:03): pressure slams
0↔6 bar while flow swings 1↔4 g/s, repeatedly. Root contributors:

- **Dead time** between a pressure change and the flow response (~1–1.5 s observed), far
  longer than the 250 ms correction interval → corrections stack before the effect shows.
- **Steep dQ/dP near breakthrough** — once the puck "lets go", flow runs away.
- **Symmetric 1.6 bar slams** + a **0 bar floor**: after a runaway the loop crashes to 0,
  loses authority, the puck reseals, flow drops below target, and it slams back up —
  re-triggering the next breakthrough.

## 2. Goal

Make Main flow control robust to puck variability **without degrading already-good
shots**. The good shot lives in a benign regime (small errors, pressure high then
easing); any fix must leave that regime untouched and only engage on the large-error /
fast-change conditions the good shot never hits.

## 3. What Phase 0 established (the evidence)

8 shots, exported HTML in `shotlogs/`, analysed with
[`tools/puck_probe_analysis.py`](../tools/puck_probe_analysis.py).

### 3.1 Preinfusion pressure changes the puck (treatment, not just measurement)

Controlled 2×2: same coffee / dose (14.5 g) / profile, vary only preinfusion pressure.

| | preinf 6.9 bar | preinf 3.0 bar | effect |
|---|---|---|---|
| **grind 11** (coarse) | t_fd 6.1 s / R_idx 41.4 / R_main 2.97 | t_fd 7.8 s / R_idx 23.1 / R_main 2.27 | R_idx −44% |
| **grind 9.5** (fine) | t_fd 15.1 s / R_idx 103 / R_main 8.26\* | **t_fd 10.3 s** / R_idx 30.6 / R_main 5.77\* | R_idx −70% |

\* cap-limited (never reached target 1.85 g/s).

**Keystone proof:** grind 9.5 reached first drop *faster* at *lower* pressure
(15.1 s → 10.3 s). Impossible for a fixed-resistance medium — less pressure must mean
slower. So the bed itself was far softer at 3 bar (less consolidation / fines packing).
This is **wait-independent** (t_fd is measured during preinfusion) and **prep-robust**
(R_idx repeats to ~2%, see §3.3), so it holds even at n=1 per cell.

Implication: **α is preinfusion-dependent** (grind 11: 0.072 at 6.9 bar → 0.098 at
3 bar). Calibrate α per profile.

### 3.2 The probe predicts the grind, not the shot — same-condition replicates

Grind 11, 6.9 bar, same coffee/dose, nominally identical:

| shot | R_idx | R_main | α | flow_cov | note |
|---|---|---|---|---|---|
| 06-04 09:24 | 41.4 | 2.97 | 0.072 | 0.04 | clean |
| 06-05 09:29 | 40.1 | 3.88 | 0.097 | 0.03 | clean |
| 06-05 09:38 | 41.4 | **1.18** | 0.029 | 0.16 | mid-shot channel |
| 06-05 09:42 | 40.1 | 2.23 | 0.056 | 0.03 | clean |

- **R_idx: 40.1–41.4 (CoV ~2%)** — the probe is highly repeatable.
- **R_main: 1.18–3.88 (CoV ~44%; ~30% even among the three clean shots)** — the outcome
  is not.

The earlier apparent "α CoV ~3%" was a small-n / across-grind artifact: varying grind
moves R_idx a lot, so the correlation *looked* tight; within a fixed grind the residual
variance is enormous. Worse, the prior gives the **same** prediction for all three
06-05 shots (R_idx identical → ~5.3 bar) while reality wanted 7.2 / 2.2 / 4.1 bar.

### 3.3 Channels are invisible before Main

Shot 06-05 09:38 (R_main 1.18, a channel) looked **normal pre-Main**: R_idx 41.4
(identical to its siblings) and zeroP-flow 1.35 (identical to the clean shots). The
channel formed *during* the pull. No pre-shot probe — `R_index` or Wait-flow — can
predict this. It can only be caught live.

### 3.4 Cap-limiting is real and diagnosable

Fine grind (9.5) needed a back-projected ~15 bar to hit 1.85 g/s — above the 9 bar
ceiling — so it pinned at the cap below target at both preinfusion pressures. The probe
flags this upfront (`R_index` high → predicted pressure > cap → "this grind can't reach
target"). The analysis tool detects it (flow stays under target, or mildly under with
pressure pinned at its peak) and excludes such shots from α calibration.

## 4. Revised design philosophy

**The live feedback loop is the controller. The probe is a hint.**

1. **Online resistance feed-forward** — `P = Q_target · R_est`, where `R_est = P/Q` is
   measured and adapted *during the shot*. This is the structural fix and the core of
   the work.
2. **Gusher-safe recovery** — because ~30% of the puck behaviour, and 100% of mid-shot
   channels, are unpredictable beforehand, the loop must detect and recover from
   overspeed live.
3. **Prevention upstream** — consolidating preinfusion and an adequate/conditional Wait
   reduce (not eliminate) prep-driven gushers.
4. **Probe as seed + diagnostic** — `R_index` gives a grind-level starting ballpark
   (valuable when grind changes) and powers diagnostics; it is never trusted as a
   per-shot R.

## 5. Controller design (Phase 1)

A new path for `StageType.FLOW_LIMITED_PRESSURE`, behind a per-profile flag so it can be
A/B'd against the current incremental-P law. Sketch:

```
on each control tick (interval matched to dead time, ~ see §5.4):
  q  = flow (filtered, see §5.5)
  p  = current commanded pressure

  # --- 5.1 Online resistance estimate (the model) -----------------
  if sample_is_stable(q, p, dq/dt):           # §5.2 gating
      R_obs = p / q
      # asymmetric adaptation (§5.3): trust drops fast, rises slow
      a = (R_obs < R_est) ? A_FAST : A_SLOW
      R_est = (1-a)*R_est + a*R_obs

  # --- 5.6 Feed-forward command + trim ----------------------------
  P_ff   = q_target * R_est
  P_trim = k_p * (q_target - q)               # small, bounded
  P_cmd  = P_ff + P_trim

  # --- 5.7 Overspeed handling ------------------------------------
  if q > q_target + overspeed_band:           # gusher in progress
      P_cmd = min(P_cmd, P_recover)           # allow ~0 (conditional floor, §5.8)
      freeze R_est                            # don't learn from the gush (§5.2)

  # --- 5.8 Conditional floor + slew ------------------------------
  floor = (q > q_target) ? 0 : P_FLOOR        # floor in normal control, lift on overspeed
  P_cmd = clamp(P_cmd, floor, cap)
  P_cmd = slew_limit_rises(P_cmd)             # rises capped; drops fast (or fold into §5.3)
  command(P_cmd)
```

**5.1 Online R model** — the plant inverse. Seeded at Main entry from the probe (§6) or
a profile default; thereafter driven by live `P/Q`.

**5.2 Stable-sample gating** — only update `R_est` when flow and pressure are in range
and `|dq/dt|` is small (not a transient, early-Main, overspeed, or channel sample). This
is what keeps the model from being poisoned by the very events we're reacting to.

**5.3 Asymmetric adaptation** — adapt fast when `R_est` *drops* (puck loosening →
channel risk → cut pressure quickly) and slow when it *rises* (likely a transient flow
dip; don't spike pressure). Implement the up/down asymmetry **in one place** — here, in
the estimator — rather than also adding a separate pressure slew limiter.

**5.4 Dead-time awareness** — the correction interval must respect the ~1–1.5 s puck lag
so corrections don't stack (the current 250 ms is far too fast). Optionally control on
*predicted* flow `q + (dq/dt)·τ` so the loop backs off before an overshoot.

**5.5 Flow filtering** — light smoothing / median to reject spikes, but short enough not
to add lag that worsens the dead-time problem. (Compare scale-flow vs the calc-flow we
already log.)

**5.7 Overspeed hold** — at Main entry, or any time flow exceeds target, hold pressure
low/near-zero until flow returns toward target, **with a timeout fallback** (a puck that
gushes at ~0 bar forever must eventually settle to controlled FF at clamped-low R, and
flag "compromised"). Note: §5.2 freezes the model during overspeed, so this hold *is* the
behaviour that handles that regime.

**5.8 Conditional floor** — keep a floor (~2–3 bar) during normal control so the loop
never crashes to 0 and jitters; **lift the floor to ~0 during overspeed** so a genuine
gusher can be arrested.

### Implementation status & refinements (Phase 1, done)

Built as the pure, JVM-testable [`FlowFeedForwardController`](../app/src/main/java/dev/akiskev/decentebar/engine/FlowFeedForwardController.kt),
wired into [`ShotController.runFeedForwardStage`](../app/src/main/java/dev/akiskev/decentebar/ui/ShotController.kt),
and selected per stage by `ProfileStage.feedForward` (a `FeedForwardConfig`); the legacy
incremental-P law stays the default. Tests + a lagged-puck A/B harness:
[`FlowFeedForwardControllerTest`](../app/src/test/java/dev/akiskev/decentebar/engine/FlowFeedForwardControllerTest.kt).

Three things changed versus the first sketch, each forced by the simulation:

- **Learn R *from the gush*, not just settled samples.** A pure "only adapt when flow is
  steady" gate (§5.2) locks adaptation out during an oscillation — the catch-22. Fix: keep
  the gush's pushing pressure (the command that's still driving the puck after the live
  command is cut to recovery) and adapt R *down* from `pushingPressure / flow` even when the
  flow slope is high. A resistance *drop* is trusted fast and ungated; a *rise* stays slow and
  gated. This is what actually breaks the limit cycle.
- **Overspeed hold is for a real gush, not a mild overshoot** (`overspeedBandGps` default
  1.0 g/s). Slamming to 0 over a few tenths g/s is its own limit cycle; small overshoots are
  handled by normal tracking with the floor lifted.
- **The floor never exceeds the FF target pressure** (`min(pressureFloorBar, targetFlow·R)`),
  so a very loose puck — where target needs less than the nominal 2 bar floor — is not pushed
  past target by the floor itself.

Honest limit found in sim: a single-secant `R` cannot perfectly hold an *extreme* steep puck
(`Q∝(P/R)³`) at target — it settles a bit low rather than limit-cycling. It is far steadier
than the legacy law there, and tracks well on the realistic (≈linear, R-level-change) channel
and gusher scenarios.

**Activation (UI):** a per-stage **"Resistance feed-forward"** toggle, shown only on
FLOW_LIMITED_PRESSURE stages in the profile editor
([`ProfileScreen`](../app/src/main/java/dev/akiskev/decentebar/ui/ProfileScreen.kt)). Off by
default (toggling on sets `feedForward = FeedForwardConfig()`, off clears it back to the legacy
law), so the two laws can be A/B'd per profile. Deeper tuning of the `FeedForwardConfig` fields
is still JSON-only.

### Prevention track (profile-level, partly free today)

- **Conditional Wait** — exit Wait on "min time **OR** weight/flow cap, whichever first".
  Already expressible via the existing exit engine (`weightGte` + `stageTimeGteMs` +
  `ExitMode.ANY`). Set the threshold from data so it only trips on real channels (a flat
  2.5 g cap would clip a good bloom — see §8 caveats). The original 11:03 disaster was
  likely its 1 s wait, not its 3 bar preinfusion.
- **Consolidating preinfusion** — higher preinfusion pressure produces a denser, more
  stable puck (§3.1). A real upstream lever for fragile pucks.

## 6. The preinfusion probe — right-sized role

`R_index = ∫ P dt` to first drop (trapezoid, null P → 0); captured at the `FIRST_DROP`
moment in `ShotController`. Given §3, its uses are:

- **Cold-start seed (weak):** at Main entry set `R_est ≈ α(profile) · R_index` so the
  first command is in the right *grind* ballpark — most valuable when grind changed since
  the last shot. Online adaptation immediately takes over; never weight it heavily.
- **Diagnostics (strong, thanks to ~2% repeatability):** grind-drift detection ("R_index
  moved → your grind/dose changed") and a pre-shot **"this grind can't reach target"**
  warning when `Q_target · α · R_index > cap`.
- **Not** a per-shot R predictor (§3.2).

α is calibrated **per profile** (which bundles preinfusion pressure). Use a known-good
reference shot ratio `R_est_seed = R_ref · (R_index_now / R_index_ref)` to avoid needing
an absolute constant.

## 7. Metrics / analysis tool

The Phase 0 tool reports, per shot:

| field | meaning |
|---|---|
| `R_index` | `∫ P dt` to first drop (bar·s) — preinfusion probe |
| `R_main` | median `P/Q` over the settled back-portion of each flow-limited stage |
| `alpha` | `R_main / R_index` |
| `flow_cov`, `reversals` | oscillation indicators |
| `zeroP_med/max` | flow at ~0 bar after first drop (looseness) |
| `clean` | `flow_cov < 0.25` and `reversals < 6` |
| `capped` | never reached target (flow well under, or mildly under with pressure pinned at peak) |
| `valid_for_alpha` | `clean AND not capped` — only these calibrate α |

Flow-limited stages are detected from `stageTargetFlows` keys (profile-agnostic). A
`--group-by preP` view (alpha + mean stability per preinfusion level) is a useful add as
more replicates arrive.

**Shots now self-record their context** (so analysis isn't detective work):
- **`algo`** — `ff`/`legacy` read from a per-stage control-law event the controller emits;
  falls back to `ff?`/`legacy?` inferred from pressure-command cadence on older logs
  (FF ticks ~700 ms, legacy ~250–300 ms).
- **User metadata** (forced on save): `beans`, `grind`, `dose` (+ optional `notes`).
- **Auto context (Tier 1):** `app_version`, `flow_source` (BLE `scale` vs `accessibility`
  estimate), `scale_batt`, and a **full embedded profile snapshot** (exact stage params +
  any `FeedForwardConfig` used) for reproducibility. The tool surfaces `algo`/`grind` in the
  table and the rest in `--csv`.

## 8. Risks / caveats

- **Prep noise dominates (~30% on R_main at fixed conditions).** The feed-forward prior
  is therefore a ballpark, and the *feedback* must absorb ±30% shot-to-shot plus mid-shot
  drift. The loop must be robust and reasonably quick — but not so quick it re-creates
  the dead-time limit cycle.
- **Mid-shot channels are unpredictable** (§3.3) — only recovery (§5.7/5.8) helps.
- **Conditional-Wait threshold must come from data** — a flat weight cap clips good
  blooms (a good puck also accumulates ~3.6 g over a 5 s wait; the bad one front-loads
  it). Use flow-rate / weight-in-first-second, not total.
- **α is per-profile / per-preinfusion**, not universal (§3.1).
- **Don't double the asymmetry** — put the fast-down/slow-up logic only in the estimator
  (§5.3), not also in a separate slew limiter.

## 9. Revised phased rollout

- **Phase 0 — Offline validation. ✅ Complete.** Conclusion: probe = grind-level seed +
  diagnostic; prep noise dominates; live feedback is the core. (Tool + 8 shots in repo.)
- **Phase 1 — Online feed-forward + gusher-safe Main (THE core). ✅ Implemented** (see §5
  "Implementation status"). Behind the per-stage `feedForward` flag; unit + lagged-puck A/B
  tests pass. Still to do: expose in the profile editor UI, and validate on the machine
  (and/or against the real recorded shots once a puck-model replay exists, §11).
- **Phase 2 — Probe seed + diagnostics (light).** §6 cold-start seed and grind-drift /
  cap warnings. Small; depends on per-profile α.
- **Phase 3 — Prevention.** Conditional Wait (data-set threshold); document the
  consolidating-preinfusion lever.

## 10. Success criteria

- Bad-puck / gusher shots show **no sustained limit cycle** (flow_cov and reversals drop
  materially) while good-puck shots are **unchanged** vs the current controller.
- The new law, replayed/run against 06-05 09:38 and 06-03 11:03, does not slam 0↔cap.
- Cap-limited grinds get a pre-shot warning instead of a silent under-target pull.

## 11. Open questions

- Closed-loop **replay harness**: replaying recorded flow shows "would it have slammed",
  but true closed-loop needs a puck model (we only recorded flow under the actual
  pressure path). Worth a crude model to A/B candidate laws offline before the machine?
- Where to persist per-profile α / the reference shot.
- Profiles with multiple flow-limited stages — per-stage R or one shared puck R?
- Dose/grind/beans + algo + app version/flow source/profile snapshot are now logged (Tier 1,
  done). Still open: a subjective **rating + roast date** (Tier 2) to close the quality loop and
  de-confound degassing; water temperature if the e-bar exposes it.

---

## Appendix A — original probe hypothesis (superseded by §0/§3)

The initial bet was that `R_index` would predict `R_main` shot-to-shot, enabling a
strong preinfusion-seeded feed-forward. Early small-n data looked very promising:

| | P_preinf | t_first_drop | R_index = P·t | settled R_main | α |
|---|---|---|---|---|---|
| Shot 1 (tight) | 6.90 bar | 6.4 s | 44.2 | ≈ 2.9 | 0.066 |
| Shot 2 (loose) | 3.00 bar | 6.6 s | 19.8 | ≈ 1.5\* | 0.076 |

…and a few more clean shots clustered at α ≈ 0.067. Phase 0 replicates (§3.2) showed
that tightness was an across-grind artifact; within a fixed grind, α scatters ~30%. The
probe is real and repeatable, but it measures the **grind**, not the **pull**.

## Appendix B — physics of the probe

Driving a wetting front through a dry puck, Darcy gives `t_to_first_drop ∝ R / P`, so
`R_index = ∫P dt ∝ R` — *if* the puck were a fixed medium. §3.1 shows it is not: P also
*consolidates* the bed (and migrates fines), so `R_index` reflects resistance **under
that preinfusion**. Higher P → denser bed → disproportionately longer t_fd → larger
R_index *and* larger R_main, but not in a P-invariant ratio — hence α(preinfusion).
