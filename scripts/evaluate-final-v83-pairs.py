#!/usr/bin/env python3
"""Matched A/B screening; numeric evidence is NOT a release authorization."""
import argparse
import copy
import json
import math
import re
import statistics
import sys

BASE = "0e0733fe88efe9ceae82990873f960ab279c05c0"
METRICS = ("first_present_ms", "interactive_ms", "handoff_ms")

def number(x):
    return type(x) in (int, float) and math.isfinite(x) and x >= 0

def quantile(xs):
    xs = sorted(xs)
    return {"p50": statistics.median(xs), "p95": xs[math.ceil(.95 * len(xs)) - 1], "max": xs[-1]}

def evaluate(data):
    if not isinstance(data, dict) or data.get("baseline_sha") != BASE:
        raise ValueError("exact v83 baseline required")
    sha = data.get("candidate_sha")
    if not isinstance(sha, str) or not re.fullmatch("[0-9a-f]{40}", sha) or sha == BASE:
        raise ValueError("distinct candidate Git SHA required")
    groups = data.get("groups")
    if not isinstance(groups, list) or not groups:
        raise ValueError("no evidence groups")
    out = []
    seen = set()
    for g in groups:
        profile = g["profile"]
        required = ("device", "rom_build", "display_state", "refresh_hz", "scenario",
                    "test_protocol", "conditions", "raw_evidence")
        if any(k not in profile or not profile[k] for k in required):
            raise ValueError("incomplete matched profile or evidence reference")
        if not number(profile["refresh_hz"]) or profile["refresh_hz"] < 1:
            raise ValueError("bad refresh rate")
        if g.get("source") not in ("physical", "emulator"):
            raise ValueError("missing source")
        key = json.dumps([g["source"], profile["device"], profile["rom_build"],
                          profile["display_state"], profile["refresh_hz"], profile["scenario"]])
        if key in seen:
            raise ValueError("duplicate scenario")
        seen.add(key)
        pairs = g["pairs"]
        if len(pairs) < 30:
            raise ValueError("at least 30 matched pairs per scenario")
        orders = [p["order"] for p in pairs]
        if any(x not in ("AB", "BA") for x in orders) or abs(orders.count("AB") - orders.count("BA")) > 1:
            raise ValueError("balanced alternating A/B order required")
        if any(x == y for x, y in zip(orders, orders[1:])):
            raise ValueError("order must alternate")
        ids = [p["id"] for p in pairs]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate pairs")
        failures = {"baseline": 0, "candidate": 0}
        values = {side: {m: [] for m in METRICS} for side in failures}
        frames = {side: [0, 0] for side in failures}
        for pair in pairs:
            for side in failures:
                row = pair[side]
                if type(row.get("ok")) is not bool:
                    raise ValueError("explicit result required for every attempt")
                if not row["ok"]:
                    failures[side] += 1
                    continue
                for m in METRICS:
                    v = row.get(m)
                    if not number(v):
                        raise ValueError("invalid/missing " + m)
                    values[side][m].append(v)
                if row["first_present_ms"] > row["interactive_ms"] or row["handoff_ms"] > row["interactive_ms"]:
                    raise ValueError("inconsistent measured sequence")
                total, missed = row.get("frames"), row.get("missed_frames")
                if type(total) is not int or type(missed) is not int or not 0 <= missed <= total or total == 0:
                    raise ValueError("invalid actual visible-frame counts")
                frames[side][0] += total
                frames[side][1] += missed
        if any(failures.values()):
            out.append({"profile": profile, "source": g["source"], "pairs": len(pairs),
                        "failures": failures, "no_regression": False, "speed_benefit": False,
                        "reason": "critical failure; diagnose, do not compare only successful samples"})
            continue
        stats = {side: {m: quantile(vals) for m, vals in ms.items()} for side, ms in values.items()}
        a, b = stats["baseline"], stats["candidate"]
        regressions = []
        for m in METRICS:
            for q, ratio, floor in (("p50", .05, 8), ("p95", .05, 8), ("max", .10, 16)):
                if b[m][q] - a[m][q] > max(a[m][q] * ratio, floor):
                    regressions.append(m + ":" + q)
        missed = {side: f[1] / f[0] for side, f in frames.items()}
        if missed["candidate"] - missed["baseline"] > .01 + 1e-12:
            regressions.append("visible_missed_frame_rate")
        p50, p95 = a["interactive_ms"]["p50"], a["interactive_ms"]["p95"]
        speed = (p50 - b["interactive_ms"]["p50"] >= max(8, p50 * .05)
                 or p95 - b["interactive_ms"]["p95"] >= max(8, p95 * .10))
        out.append({"profile": profile, "source": g["source"], "pairs": len(pairs),
                    "failures": failures, "stats_ms": stats, "missed_frame_rate": missed,
                    "regressions": regressions, "no_regression": not regressions,
                    "speed_benefit": speed and not regressions})
    return {"groups": out, "all_no_regression": all(x["no_regression"] for x in out),
            "physical_evidence_present": any(x["source"] == "physical" for x in out),
            "physical_speed_benefit_present": any(x["source"] == "physical" and x["speed_benefit"] for x in out),
            "release_authorized": False,
            "notice": "screening only; independently verify conditions, evidence, required scenarios, correctness and power"}

def self_test():
    a = dict(ok=True, first_present_ms=40, interactive_ms=500, handoff_ms=120, frames=60, missed_frames=2)
    b = dict(a, first_present_ms=35, interactive_ms=430, handoff_ms=80, missed_frames=1)
    sample = {"baseline_sha": BASE, "candidate_sha": "1" * 40, "groups": [
        {"source": "emulator", "profile": {"device": "fixture", "rom_build": "fixture",
         "display_state": "portrait", "refresh_hz": 120, "scenario": "four slots",
         "test_protocol": "fixture", "conditions": "fixture", "raw_evidence": "synthetic"},
         "pairs": [{"id": i, "order": "AB" if i % 2 == 0 else "BA", "baseline": dict(a),
                    "candidate": dict(b)} for i in range(30)]}]}
    result = evaluate(sample)
    assert result["all_no_regression"] and result["groups"][0]["speed_benefit"]
    assert not result["physical_evidence_present"] and not result["release_authorized"]
    s = copy.deepcopy(sample)
    s["groups"][0]["pairs"][-1]["candidate"]["interactive_ms"] = 800
    assert not evaluate(s)["all_no_regression"]
    s = copy.deepcopy(sample)
    s["groups"][0]["pairs"][0]["candidate"]["ok"] = False
    assert not evaluate(s)["all_no_regression"]
    for edit in (lambda t: t["groups"][0]["pairs"].pop(),
                 lambda t: t["groups"][0]["pairs"][0].update(order="BA"),
                 lambda t: t.update(baseline_sha="bad"),
                 lambda t: t["groups"][0]["pairs"][0]["candidate"].update(interactive_ms=float("nan"))):
        t = copy.deepcopy(sample)
        edit(t)
        try:
            evaluate(t)
        except ValueError:
            continue
        raise AssertionError("invalid evidence accepted")
    print("matched A/B evaluator self-test passed (synthetic only)")

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("evidence", nargs="?")
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args()
    if args.self_test:
        self_test()
    elif args.evidence:
        try:
            with open(args.evidence, encoding="utf-8") as f:
                result = evaluate(json.load(f))
            print(json.dumps(result, indent=2, ensure_ascii=False))
            sys.exit(0 if result["all_no_regression"] else 1)
        except (ValueError, KeyError, TypeError, OSError) as exc:
            print("INVALID EVIDENCE: " + str(exc), file=sys.stderr)
            sys.exit(2)
    else:
        p.error("evidence JSON or --self-test required")
