#!/usr/bin/env python3
"""Run the classifier (and probe) over gold-labeled transcript scenarios.

    python run_harness.py              # phrase + repeat tiers only
    python run_harness.py --llm        # add the Claude tier (needs credentials)
    python run_harness.py -v 03        # per-segment detail for matching scenarios
"""

import argparse
import json
import random
import sys
from pathlib import Path

from harness.classifier import ALERT_STATES, AlertTracker, Classifier, Segment
from harness.probe import ProbeController

SCENARIO_DIR = Path(__file__).resolve().parent.parent / "scenarios"


def run_scenario(path: Path, llm, seed: int, verbose: bool) -> dict:
    spec = json.loads(path.read_text())
    fp = spec.get("known_fingerprint", {})
    clf = Classifier(llm=llm, known_hold=tuple(fp.get("hold", ())), known_menu=tuple(fp.get("ivr_menu", ())))
    probe = ProbeController(enabled=spec.get("probe", False), rng=random.Random(seed))
    tracker = AlertTracker()

    stats = {"n": 0, "strict": 0, "alert_ok": 0, "false_alarms": 0, "missed": 0, "alerts": 0, "latency": None}
    first_human_t = next((s["t"] for s in spec["segments"] if s["label"] == "human"), None)
    clock = 0.0

    for raw in spec["segments"]:
        seg = Segment(raw["t"], raw["text"].replace("{keyword}", probe.keyword))
        while clock + 1 <= seg.t:  # 1 s ticks so probe timers can fire between segments
            clock += 1
            for ev in probe.tick(clock):
                verbose and print(f"         probe {ev.t:6.1f}s {ev.kind}: {ev.detail}")
        clock = seg.t

        d = clf.classify(seg)
        gold = raw["label"]
        fired = tracker.update(d.state)
        stats["n"] += 1
        stats["strict"] += d.state == gold
        stats["alert_ok"] += d.alerts == (gold in ALERT_STATES)
        stats["false_alarms"] += d.alerts and gold not in ALERT_STATES
        stats["missed"] += gold == "human" and not d.alerts
        stats["alerts"] += fired
        if first_human_t is not None and stats["latency"] is None and seg.t >= first_human_t and d.alerts:
            stats["latency"] = seg.t - first_human_t

        if verbose:
            mark = "  " if d.state == gold else ("~ " if d.alerts == (gold in ALERT_STATES) else "XX")
            alert = " ALERT" if fired else ""
            print(f"  {mark} {seg.t:6.1f}s gold={gold:<8} got={d.state:<8} [{d.tier}] {d.reason}"
                  f"{' flags=' + ','.join(d.flags) if d.flags else ''}{alert}")
            print(f"           {seg.text[:100]!r}")
        for ev in probe.observe(seg, d):
            verbose and print(f"         probe {ev.t:6.1f}s {ev.kind}: {ev.detail}")

    for _ in range(15):  # let trailing probe timers expire
        clock += 1
        for ev in probe.tick(clock):
            verbose and print(f"         probe {ev.t:6.1f}s {ev.kind}: {ev.detail}")

    stats["llm_calls"] = clf.llm_calls
    outcomes = [e.kind for e in probe.events if e.kind != "speak"]
    stats["probe"] = outcomes[-1] if outcomes else ("-" if not probe.enabled else "never fired")
    stats["probe_ok"] = spec.get("expect_probe") in (None, stats["probe"])
    stats["has_human"] = first_human_t is not None
    return stats


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("filter", nargs="?", default="", help="substring of scenario file names")
    ap.add_argument("--llm", action="store_true", help="enable the Claude tier")
    ap.add_argument("--model", default="claude-haiku-5-5")
    ap.add_argument("--seed", type=int, default=7, help="seed for the probe keyword")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    llm = None
    if args.llm:
        from harness.llm import ClaudeTier
        llm = ClaudeTier(model=args.model)

    paths = sorted(p for p in SCENARIO_DIR.glob("*.json") if args.filter in p.name)
    print(f"{'scenario':<32} {'strict':>7} {'alert-ok':>8} {'false':>5} {'missed':>6} "
          f"{'alerts':>6} {'latency':>7} {'llm':>4}  probe")
    totals = {"n": 0, "strict": 0, "alert_ok": 0, "false_alarms": 0, "missed": 0}
    failures = 0
    for p in paths:
        if args.verbose:
            print(f"\n{p.stem}")
        s = run_scenario(p, llm, args.seed, args.verbose)
        for k in totals:
            totals[k] += s[k]
        latency = "-" if not s["has_human"] else ("MISS" if s["latency"] is None else f"{s['latency']:.0f}s")
        bad = s["missed"] or (s["has_human"] and s["latency"] is None) or not s["probe_ok"]
        failures += bool(bad)
        print(f"{p.stem:<32} {s['strict']:>3}/{s['n']:<3} {s['alert_ok']:>4}/{s['n']:<3} "
              f"{s['false_alarms']:>5} {s['missed']:>6} {s['alerts']:>6} {latency:>7} {s['llm_calls']:>4}  "
              f"{s['probe']}{'' if s['probe_ok'] else ' (UNEXPECTED)'}{'  <-- FAIL' if bad else ''}")
    n = totals["n"] or 1
    print(f"\nsegments={totals['n']} strict={totals['strict'] / n:.0%} alert-correct={totals['alert_ok'] / n:.0%} "
          f"false-alarms={totals['false_alarms']} missed-humans={totals['missed']}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
