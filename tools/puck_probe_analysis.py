#!/usr/bin/env python3
"""Phase 0 offline analysis for the puck-resistance feed-forward plan.

See docs/puck-resistance-feedforward.md. Parses DecentEbar shot logs (the HTML
files the app exports, or raw shot-log JSON) and computes, per shot:

  R_index   = integral of commanded pressure up to first drop  (bar*s)
              -- the preinfusion "probe": proportional to puck resistance.
  R_main    = median(P / flow) over the settled (back) part of each flow-limited
              stage (bar per g/s) -- the resistance Main actually saw.
  alpha     = R_main / R_index   -- the constant mapping probe -> feed-forward R.

It then reports whether alpha is stable across *clean* (non-oscillating) shots.
If it is, the preinfusion probe is predictive and Phase 2 is worth building.

Stdlib only. Usage:
  python tools/puck_probe_analysis.py [PATH ...] [--csv out.csv]
  python tools/puck_probe_analysis.py --selftest

PATH may be HTML/JSON files or directories (scanned recursively for *.html and
*.json). With no PATH, ./shotlogs is used if it exists, else the current dir.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import os
import re
import sys
from datetime import datetime
from statistics import mean, median, pstdev

# --- Tunable analysis constants (refine once real data is in) -----------------
SETTLE_FROM_FRACTION = 0.4   # settled window = last (1-frac) of each flow-limited stage
FLOW_MIN_GPS = 0.3           # ignore near-zero flow when forming P/Q (avoids blow-ups)
PRESSURE_MIN_BAR = 0.3       # ignore near-zero pressure when forming P/Q
ZERO_P_BAR = 0.3             # "near zero" commanded pressure for the looseness probe
FIRST_DROP_WEIGHT_G = 0.1    # fallback first-drop threshold if no FIRST_DROP event
CLEAN_FLOW_COV_MAX = 0.25    # a shot is "clean" if settled flow CoV is below this ...
CLEAN_MAX_REVERSALS = 6      # ... and commanded-pressure reversals are below this
TARGET_REACH_TOLERANCE = 0.15  # settled flow this far below target => "flow-starved"
MILD_UNDER_GPS = 0.05        # ... or only mildly under target but pressure pinned high
PINNED_TOL_BAR = 0.3         # settled pressure within this of its peak => "pinned" (at cap)
ALPHA_STABLE_COV_MAX = 0.25  # valid-shot alpha CoV below this => probe looks predictive
ALGO_CADENCE_MS = 500        # min flow-stage pressure-command gap above this => feed-forward (fallback)

SHOTLOG_RE = re.compile(r'<script[^>]*id="shotlog-data"[^>]*>(.*?)</script>', re.S)


# --- Loading ------------------------------------------------------------------
def extract_shotlog(text: str):
    """Return the parsed shot-log dict from an HTML or raw-JSON string, or None."""
    m = SHOTLOG_RE.search(text)
    raw = m.group(1) if m else text
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) and "samples" in data else None


def iter_input_files(paths):
    for p in paths:
        if os.path.isdir(p):
            for pat in ("**/*.html", "**/*.json"):
                yield from sorted(glob.glob(os.path.join(p, pat), recursive=True))
        else:
            yield p


def load_shots(paths):
    shots = []
    for path in iter_input_files(paths):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = extract_shotlog(fh.read())
        except (OSError, UnicodeDecodeError):
            continue
        if data is not None:
            shots.append((path, data))
    return shots


# --- Metrics ------------------------------------------------------------------
def first_drop_ms(data):
    for e in data.get("events", []):
        if e.get("type") == "FIRST_DROP":
            return e.get("timeMs")
    for s in data.get("samples", []):
        w = s.get("weightG")
        if w is not None and w >= FIRST_DROP_WEIGHT_G:
            return s.get("timeMs")
    return None


def pressure_integral(samples, t_end_ms):
    """Trapezoidal integral of commanded pressure up to t_end_ms (null P -> 0)."""
    pts = sorted(
        (s["timeMs"], s.get("commandedPressureBar") or 0.0)
        for s in samples
        if s.get("timeMs") is not None and s["timeMs"] <= t_end_ms
    )
    area = 0.0
    for (t0, p0), (t1, p1) in zip(pts, pts[1:]):
        area += (p0 + p1) / 2.0 * (t1 - t0) / 1000.0
    return area


def pressure_reversals(rows):
    """Count direction changes in the commanded-pressure command sequence."""
    seq = []
    for s in rows:
        p = s.get("commandedPressureBar")
        if p is None:
            continue
        if not seq or abs(p - seq[-1]) > 1e-6:
            seq.append(p)
    deltas = [b - a for a, b in zip(seq, seq[1:])]
    return sum(1 for a, b in zip(deltas, deltas[1:]) if (a > 0) != (b > 0))


def flow_limited_stage_metrics(samples, stage_name, target):
    rows = [s for s in samples if s.get("stageName") == stage_name]
    if not rows:
        return None
    times = [s["timeMs"] for s in rows]
    t0, t1 = min(times), max(times)
    cutoff = t0 + (t1 - t0) * SETTLE_FROM_FRACTION
    settled = [s for s in rows if s["timeMs"] >= cutoff] or rows

    ratios = []
    for s in settled:
        p, q = s.get("commandedPressureBar"), s.get("flowGps")
        if p is not None and q is not None and q > FLOW_MIN_GPS and p > PRESSURE_MIN_BAR:
            ratios.append(p / q)
    flows = [s.get("flowGps") for s in settled if s.get("flowGps") is not None]
    flow_cov = (pstdev(flows) / mean(flows)) if len(flows) > 1 and mean(flows) > 0 else None
    flow_med = median(flows) if flows else None

    # "Cap/ceiling-limited": the puck never reached target flow, so the controller pinned
    # pressure high and R_main = P/Q overstates the true resistance-at-target. Such shots
    # look "clean" (flat) but must not calibrate alpha. Flag when flow is clearly under
    # target, OR only mildly under but pressure sat pinned near its own peak (cap) — the
    # controller wanted more flow and couldn't get it.
    settled_p = [s.get("commandedPressureBar") for s in settled
                 if s.get("commandedPressureBar") is not None]
    all_p = [s.get("commandedPressureBar") for s in rows
             if s.get("commandedPressureBar") is not None]
    p_med = median(settled_p) if settled_p else None
    p_max = max(all_p) if all_p else None
    pinned = p_med is not None and p_max is not None and p_med >= p_max - PINNED_TOL_BAR
    capped = bool(
        target is not None and flow_med is not None and (
            flow_med < target - TARGET_REACH_TOLERANCE
            or (flow_med < target - MILD_UNDER_GPS and pinned)
        )
    )

    return {
        "stage": stage_name,
        "target": target,
        "r_main": median(ratios) if ratios else None,
        "n_ratio": len(ratios),
        "flow_cov": flow_cov,
        "flow_med": flow_med,
        "capped": capped,
        "reversals": pressure_reversals(rows),
    }


def zero_pressure_flow(samples, t_fd):
    if t_fd is None:
        return None, None
    vals = [
        s["flowGps"]
        for s in samples
        if s.get("timeMs") is not None and s["timeMs"] >= t_fd
        and s.get("flowGps") is not None
        and (s.get("commandedPressureBar") or 0.0) < ZERO_P_BAR
    ]
    return (median(vals), max(vals)) if vals else (None, None)


def detect_algo(data):
    """Which Main control law ran. 'ff' / 'legacy' from the logged stage-entry event; for older
    logs without it, infer 'ff?' / 'legacy?' from pressure-command cadence (FF ticks ~700 ms,
    legacy ~250-300 ms)."""
    for e in data.get("events", []):
        m = str(e.get("message", ""))
        if "feed-forward control" in m:
            return "ff"
        if "incremental-P control" in m:
            return "legacy"
    flow_stages = set((data.get("stageTargetFlows") or {}).keys())
    cmds = sorted(
        e["timeMs"] for e in data.get("events", [])
        if e.get("type") == "PRESSURE_COMMAND" and e.get("timeMs") is not None
        and any(str(e.get("message", "")).startswith(s) for s in flow_stages)
    )
    gaps = [b - a for a, b in zip(cmds, cmds[1:])]
    if not gaps:
        return "?"
    return "ff?" if min(gaps) >= ALGO_CADENCE_MS else "legacy?"


def analyze_shot(path, data):
    samples = data.get("samples", [])
    targets = data.get("stageTargetFlows", {}) or {}
    t_fd = first_drop_ms(data)

    r_index = pressure_integral(samples, t_fd) if t_fd is not None else None
    preinf_p_avg = (r_index / (t_fd / 1000.0)) if (r_index and t_fd) else None
    zp_med, zp_max = zero_pressure_flow(samples, t_fd)

    stages = []
    for name, tgt in targets.items():
        m = flow_limited_stage_metrics(samples, name, tgt)
        if m:
            m["n_samples"] = sum(1 for s in samples if s.get("stageName") == name)
            stages.append(m)
    # Primary flow-limited stage = the one with the most samples (usually "Main").
    primary = max(stages, key=lambda m: m["n_samples"]) if stages else None

    alpha = None
    clean = False
    capped = bool(primary and primary["capped"])
    if primary and primary["r_main"] and r_index:
        alpha = primary["r_main"] / r_index
        clean = (
            primary["flow_cov"] is not None
            and primary["flow_cov"] < CLEAN_FLOW_COV_MAX
            and primary["reversals"] < CLEAN_MAX_REVERSALS
        )
    # Only target-reaching clean shots have a valid resistance-at-target; these are the
    # ones allowed to calibrate alpha. Cap-limited shots are kept in the table (with the
    # `cap` flag) but excluded from the alpha stats.
    valid_for_alpha = bool(clean and not capped and alpha)

    started = data.get("startedAtMs")
    try:
        date = datetime.fromtimestamp(started / 1000.0).strftime("%Y-%m-%d %H:%M") if started else "?"
    except (OverflowError, OSError, TypeError):
        date = "?"

    return {
        "file": os.path.basename(path),
        "profile": data.get("profileName", "?"),
        "date": date,
        "preinf_p": preinf_p_avg,
        "t_fd_s": (t_fd / 1000.0) if t_fd is not None else None,
        "r_index": r_index,
        "stage": primary["stage"] if primary else None,
        "target": primary["target"] if primary else None,
        "r_main": primary["r_main"] if primary else None,
        "alpha": alpha,
        "flow_cov": primary["flow_cov"] if primary else None,
        "reversals": primary["reversals"] if primary else None,
        "flow_med": primary["flow_med"] if primary else None,
        "zeroP_med": zp_med,
        "zeroP_max": zp_max,
        "clean": clean,
        "capped": capped,
        "valid_for_alpha": valid_for_alpha,
        "algo": detect_algo(data),
        "beans": data.get("beansName"),
        "grind": data.get("grindSetting"),
        "dose": data.get("doseG"),
        "notes": data.get("notes"),
        "app_version": data.get("appVersion"),
        "flow_source": data.get("flowSource"),
        "scale_batt": data.get("scaleBatteryPercent"),
    }


# --- Output -------------------------------------------------------------------
def fmt(v, spec="6.2f"):
    width = int(re.match(r"(\d+)", spec).group(1))
    return ("{:" + spec + "}").format(v) if isinstance(v, (int, float)) else " " * width


def print_table(rows):
    hdr = (
        f"{'date':<16} {'profile':<16} {'preP':>5} {'t_fd':>5} {'R_idx':>6} "
        f"{'tgt':>4} {'R_main':>6} {'alpha':>6} {'fCoV':>5} {'rev':>4} {'zPmax':>5} "
        f"{'clean':>5} {'cap':>4} {'use':>4} {'algo':>8} {'grind':>6}"
    )
    print(hdr)
    print("-" * len(hdr))
    for r in rows:
        print(
            f"{r['date']:<16} {r['profile'][:16]:<16} "
            f"{fmt(r['preinf_p'],'5.1f')} {fmt(r['t_fd_s'],'5.1f')} {fmt(r['r_index'],'6.1f')} "
            f"{fmt(r['target'],'4.2f')} {fmt(r['r_main'],'6.2f')} {fmt(r['alpha'],'6.4f')} "
            f"{fmt(r['flow_cov'],'5.2f')} {fmt(r['reversals'],'4.0f')} {fmt(r['zeroP_max'],'5.2f')} "
            f"{'yes' if r['clean'] else 'no':>5} {'yes' if r['capped'] else 'no':>4} "
            f"{'yes' if r['valid_for_alpha'] else 'no':>4} {(r['algo'] or '?'):>8} {str(r['grind'] or ''):>6}"
        )


def print_summary(rows):
    valid_alphas = [r["alpha"] for r in rows if r["valid_for_alpha"]]
    clean_alphas = [r["alpha"] for r in rows if r["clean"] and r["alpha"]]
    all_alphas = [r["alpha"] for r in rows if r["alpha"]]
    n_capped = sum(1 for r in rows if r["capped"])
    print("\n=== Summary ===")
    print(f"shots parsed: {len(rows)}   with alpha: {len(all_alphas)}   "
          f"clean: {len(clean_alphas)}   cap-limited: {n_capped}   valid-for-alpha: {len(valid_alphas)}")

    def stats(name, xs):
        if not xs:
            print(f"  {name}: (none)")
            return None
        m = median(xs)
        cov = (pstdev(xs) / mean(xs)) if len(xs) > 1 and mean(xs) else 0.0
        print(f"  {name}: median={m:.4f}  mean={mean(xs):.4f}  CoV={cov:.0%}  n={len(xs)}")
        return m, cov

    stats("alpha (all shots)      ", all_alphas)
    stats("alpha (clean, incl cap)", clean_alphas)
    valid_stat = stats("alpha (valid only)     ", valid_alphas)

    if valid_stat and len(valid_alphas) >= 2:
        a_med, a_cov = valid_stat
        print()
        if a_cov < ALPHA_STABLE_COV_MAX:
            print(f"  => PROBE LOOKS PREDICTIVE (valid alpha CoV {a_cov:.0%} < {ALPHA_STABLE_COV_MAX:.0%}). "
                  "Green-light Phase 2.")
        else:
            print(f"  => alpha is noisy (valid CoV {a_cov:.0%}). Collect more shots before trusting the probe.")
        print("\n  Predicted vs actual Main-entry pressure using median valid alpha")
        print("  (* = cap-limited: never reached target, excluded from calibration):")
        print(f"  {'date':<16} {'pred_bar':>8} {'actual_bar':>10}")
        for r in rows:
            if r["alpha"] and r["target"] and r["r_index"] and r["r_main"]:
                pred = r["target"] * a_med * r["r_index"]
                actual = r["target"] * r["r_main"]
                flag = " *" if r["capped"] else ""
                print(f"  {r['date']:<16} {pred:>8.2f} {actual:>10.2f}{flag}")
    else:
        print("\n  Need >=2 valid (clean, target-reaching) shots to assess alpha. Collect more data.")
    print(
        "\n  (clean = flow CoV < {:.0%} and < {} reversals; cap = never reached target flow\n"
        "   (>{:.2f} g/s under, or mildly under with pressure pinned at its peak); valid =\n"
        "   clean AND not cap-limited -- only valid shots calibrate alpha.)".format(
            CLEAN_FLOW_COV_MAX, CLEAN_MAX_REVERSALS, TARGET_REACH_TOLERANCE
        )
    )


def write_csv(rows, path):
    cols = ["file", "profile", "date", "algo", "beans", "grind", "dose",
            "preinf_p", "t_fd_s", "r_index", "stage", "target", "r_main", "alpha",
            "flow_cov", "flow_med", "reversals", "zeroP_med", "zeroP_max",
            "clean", "capped", "valid_for_alpha", "flow_source", "scale_batt",
            "app_version", "notes"]
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k) for k in cols})
    print(f"\nwrote {len(rows)} rows -> {path}")


# --- Self-test ----------------------------------------------------------------
def _synthetic_shot(preinf_p, t_fd_s, main_p, main_q, target, profile):
    samples, t = [], 100
    while t <= int(t_fd_s * 1000):
        samples.append({"timeMs": t, "weightG": 0.0, "flowGps": 0.02,
                        "commandedPressureBar": preinf_p, "stageName": "Preinfusion"})
        t += 100
    t0 = int(t_fd_s * 1000) + 100
    t = t0
    while t <= t0 + 10000:
        samples.append({"timeMs": t, "weightG": 10.0, "flowGps": main_q,
                        "commandedPressureBar": main_p, "stageName": "Main"})
        t += 100
    return {
        "profileName": profile, "startedAtMs": 1780000000000,
        "samples": samples,
        "events": [{"timeMs": int(t_fd_s * 1000), "type": "FIRST_DROP",
                    "message": "", "weightG": 0.1}],
        "stageTargetFlows": {"Main": target},
    }


def selftest():
    a = analyze_shot("shotA", _synthetic_shot(6.9, 6.4, 4.9, 1.7, 1.45, "tight"))
    b = analyze_shot("shotB", _synthetic_shot(3.0, 6.6, 3.0, 2.0, 1.50, "loose"))
    # Cap-limited: flow (1.1) stays well below target (1.85) at a pinned-high pressure.
    c = analyze_shot("shotC", _synthetic_shot(6.9, 15.1, 9.0, 1.1, 1.85, "capped"))
    # Borderline cap: flow (1.78) only mildly under target (1.9) but pressure pinned at peak.
    d = analyze_shot("shotD", _synthetic_shot(6.9, 7.5, 9.0, 1.78, 1.9, "mildcap"))
    ok = True

    def check(name, got, exp, tol):
        nonlocal ok
        good = got is not None and abs(got - exp) <= tol
        ok = ok and good
        print(f"  [{'OK ' if good else 'FAIL'}] {name}: got={got!r} expected~{exp} (+-{tol})")

    # constant preinf pressure from 100ms..t_fd => integral ~ P*(t_fd-0.1)
    check("A R_index", a["r_index"], 6.9 * (6.4 - 0.1), 0.2)
    check("B R_index", b["r_index"], 3.0 * (6.6 - 0.1), 0.2)
    check("A R_main", a["r_main"], 4.9 / 1.7, 0.01)
    check("B R_main", b["r_main"], 3.0 / 2.0, 0.01)
    check("A alpha", a["alpha"], (4.9 / 1.7) / (6.9 * (6.4 - 0.1)), 0.001)
    check("A clean", 1.0 if a["clean"] else 0.0, 1.0, 0.0)  # constant flow => clean
    check("A valid", 1.0 if a["valid_for_alpha"] else 0.0, 1.0, 0.0)
    # C looks clean (flat) but is cap-limited => flagged capped and NOT valid for alpha.
    check("C clean", 1.0 if c["clean"] else 0.0, 1.0, 0.0)
    check("C capped", 1.0 if c["capped"] else 0.0, 1.0, 0.0)
    check("C valid", 1.0 if c["valid_for_alpha"] else 0.0, 0.0, 0.0)
    # D: looks clean, only mildly under target, but pinned => capped, not valid.
    check("D capped", 1.0 if d["capped"] else 0.0, 1.0, 0.0)
    check("D valid", 1.0 if d["valid_for_alpha"] else 0.0, 0.0, 0.0)
    print("\nself-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


# --- Main ---------------------------------------------------------------------
def main(argv):
    ap = argparse.ArgumentParser(description="Phase 0 puck-resistance probe analysis.")
    ap.add_argument("paths", nargs="*", help="HTML/JSON shot logs or directories.")
    ap.add_argument("--csv", help="write per-shot metrics to this CSV file.")
    ap.add_argument("--selftest", action="store_true", help="run built-in checks and exit.")
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    paths = args.paths
    if not paths:
        paths = ["shotlogs"] if os.path.isdir("shotlogs") else ["."]

    shots = load_shots(paths)
    if not shots:
        print(f"No shot logs with embedded data found under: {', '.join(paths)}")
        print("Export shots from the app and drop the .html files into ./shotlogs, then re-run.")
        return 1

    rows = [analyze_shot(p, d) for p, d in shots]
    rows.sort(key=lambda r: r["date"])
    print_table(rows)
    print_summary(rows)
    if args.csv:
        write_csv(rows, args.csv)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
