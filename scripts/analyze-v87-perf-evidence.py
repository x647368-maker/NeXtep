#!/usr/bin/env python3
"""Summarize original sample rows; never generate, repeat or impute frames."""
import csv
import json
import math
import statistics
import sys
from pathlib import Path

def read(p):
    with p.open(newline="") as f:
        return list(csv.DictReader(f))
def stats(values):
    x=sorted(v/1e6 for v in values if v>=0)
    if not x:
        return {"n":0}
    return {"n":len(x),"p50_ms":statistics.median(x),
            "p90_ms":x[math.ceil(len(x)*.90)-1],
            "p95_ms":x[math.ceil(len(x)*.95)-1],"max_ms":x[-1]}
def summarize(rows):
    valid=[r for r in rows if r["valid"]=="1"]
    result={k:stats([int(r[k]) for r in valid]) for k in
            ("total_ns","draw_ns","sync_ns","command_ns","swap_ns")}
    deadlines=[r for r in valid if int(r["deadline_ns"])>0]
    result.update({"callbacks":len(rows),"valid":len(valid),
        "invalid":len(rows)-len(valid),"listener_drops":sum(int(r["dropped"]) for r in rows),
        "deadline_available":len(deadlines),
        "deadline_missed":sum(int(r["total_ns"])>int(r["deadline_ns"]) for r in deadlines)})
    return result

def main(root):
    report={"scope":"Android HWUI fixture / AOSP framework migration, not OEM production or FPS guarantee",
            "candidates":{},"errors":[]}
    for d in sorted(root.iterdir()):
        if not d.is_dir() or d.name not in {"R9","R10","R11"}:
            continue
        out=report["candidates"][d.name]={}
        try:
            frames=read(d/"frames.csv")
            phases=read(d/"phases.csv")
            out["metadata"]=(d/"metadata.txt").read_text()
            out["phases"]=phases
            for layout in ("top","side"):
                rows=[r for r in frames if r["layout"]==layout and r["phase"]=="stable"]
                out[layout]={"combined":summarize(rows),"rounds":{}}
                for n in (1,2,3):
                    s=summarize([r for r in rows if int(r["round"])==n])
                    out[layout]["rounds"][str(n)]=s
                    if s["valid"]<120:
                        report["errors"].append(f"{d.name} {layout} round {n}: fewer than 120 real frames")
                    phase=[p for p in phases if p["layout"]==layout and p["phase"]=="stable" and int(p["round"])==n]
                    if len(phase)!=1 or phase[0]["completed"]!="1" or int(phase[0]["valid"])!=s["valid"]:
                        report["errors"].append(f"{d.name} {layout} round {n}: incomplete/inconsistent phase")
                    elif any(int(phase[0][f"updates{i}"])<=0 for i in range(4)):
                        report["errors"].append(f"{d.name} {layout} round {n}: producer update missing")
            out["reflow"]=summarize([r for r in frames if r["phase"]=="reflow_3to4"])
            moves=read(d/"migration-phases.csv")
            buffers=read(d/"migration-buffer-waits.csv")
            out["migration"]={k:stats([int(r[k]) for r in moves]) for k in
                ("wall_ns","queue_ns","main_ns","binder_ns","observation_ns","query_ns","sleep_ns","main_cpu_ns","dispatch_return_ns")}
            out["migration"]["max_sample"]=max(moves,key=lambda r:int(r["wall_ns"]))
            out["migration"]["return_buffer_wait"]=stats([int(r["wait_ns"]) for r in buffers])
            if len(moves)!=16 or any(r["ok"]!="1" for r in moves) or len(buffers)!=8 or any(r["ok"]!="1" for r in buffers):
                report["errors"].append(f"{d.name}: incomplete actual 16-move/8-buffer probe")
        except Exception as e:
            report["errors"].append(f"{d.name}: {type(e).__name__}: {e}")
    if not report["candidates"]:
        report["errors"].append("No candidate runtime data")
    (root/"summary.json").write_text(json.dumps(report,indent=2)+"\n")
    lines=["# V87 performance evidence", "",report["scope"],"",
           "| Candidate | Top valid frames | Side valid frames | Top p95 ms | Side p95 ms | Migration p50 ms | Migration max ms |",
           "|---|---:|---:|---:|---:|---:|---:|"]
    for name,r in report["candidates"].items():
        t=r.get("top",{}).get("combined",{});s=r.get("side",{}).get("combined",{})
        m=r.get("migration",{}).get("wall_ns",{})
        def fmt(x): return f"{x:.3f}" if isinstance(x,(int,float)) else "missing"
        lines.append("| "+" | ".join([name,str(t.get("valid","missing")),str(s.get("valid","missing")),
                    fmt(t.get("total_ns",{}).get("p95_ms")),fmt(s.get("total_ns",{}).get("p95_ms")),
                    fmt(m.get("p50_ms")),fmt(m.get("max_ms"))])+" |")
    lines+=["","Fixed sequential same-runner API35 comparison; fresh app installs, residual system cache/load/order effects remain.",
            "API35 vs API37 uses separate runners/system images; no claim of identical platform or OEM.", "",
            "## Incomplete or invalid evidence",*(report["errors"] or ["None"])]
    (root/"summary.md").write_text("\n".join(lines)+"\n")
    print("\n".join(lines))
    return bool(report["errors"])
if __name__=="__main__":
    sys.exit(main(Path(sys.argv[1])))
