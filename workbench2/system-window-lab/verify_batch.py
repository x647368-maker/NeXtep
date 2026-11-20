#!/usr/bin/env python3
"""Only for the fresh isolated Desktop emulator; no production package operations."""
import json, pathlib, re, subprocess, sys, time
d=pathlib.Path(sys.argv[1])
target="io.github.x647368maker.atomicworkbench.batchtarget"
controller="io.github.x647368maker.atomicworkbench.batchcontroller"
def adb(*args, check=True):
    return subprocess.run(["adb",*args],capture_output=True,text=True,check=check,timeout=25).stdout.strip()
def raw(tag):
    return adb("logcat","-d","-v","raw","-s",tag+":I")
def latest():
    result={}
    for s in raw("WB2BatchTarget").splitlines():
        if s.startswith("{"):
            j=json.loads(s)
            if j.get("slot") in ("A","B"): result[j["slot"]]=j
    return result
def act_record(dump,j):
    p=rf"^\s*\* Hist\s+#\d+:\s+ActivityRecord\{{{re.escape(j['activity_record'])}\s+u\d+\s+[^\r\n]*\bt{j['task']}\}}"
    matches=list(re.finditer(p,dump,re.M))
    assert len(matches)==1 and target in matches[0].group() and "BatchTargetActivity" in matches[0].group(), "record lost or changed"
    tail=dump[matches[0].end():]
    tail=re.split(r"^\s*(?:\* Hist\s+#\d+:|\*?\s*Task\{)",tail,1,flags=re.M)[0]
    b=re.search(r"taskBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)",tail)
    if not b: b=re.search(r"CurrentConfiguration=.*?mBounds=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)",tail)
    assert b and ",".join(b.groups())==j["bounds"], "system bounds lost or changed"
def snapshot(name, expected, counts):
    assert adb("shell","pidof",controller,check=False)=="", "controller survived"
    state=latest()
    dump=adb("shell","dumpsys","activity","activities")
    (d/(name+"-activities.txt")).write_text(dump)
    (d/(name+"-targets.json")).write_text(json.dumps(state,indent=2))
    for slot in ("A","B"):
        now=state[slot]; old=expected[slot]
        for key in ("task","pid","instance","marker","bounds","display"):
            assert now[key]==old[key], (slot,key,old[key],now[key])
        assert now["clicks"]==counts[slot] and now["multi"] and now["shown"] and now["event"]!="DESTROY"
        assert str(now["pid"]) in adb("shell","pidof",target).split()
        act_record(dump,old)
    return state
with (d/"instrumentation.txt").open("w") as out:
    p=subprocess.Popen(["adb","shell","am","instrument","-w","-r","-e","class","workbench2.field.SystemWindowBatchTest#batchSwapAndAwaitIndependentControllerStop",controller+".test/androidx.test.runner.AndroidJUnitRunner"],stdout=out,stderr=subprocess.STDOUT)
    try:
        end=time.monotonic()+100
        ready=None
        while time.monotonic()<end:
            log=raw("WB2Batch")
            hits=[line for line in log.splitlines() if line.startswith("READY_FOR_CONTROLLER_DEATH ")]
            if hits:
                assert len(hits)==1
                ready=json.loads(hits[0].split(" ",1)[1]); break
            if p.poll() is not None: raise RuntimeError("instrumentation ended before death checkpoint")
            time.sleep(1)
        assert ready is not None, "NO_GO no predeath checkpoint"
        (d/"predeath.json").write_text(json.dumps(ready,indent=2))
        assert ready["source_sha"]==(d/"checkout-sha.txt").read_text().strip()
        before=adb("shell","dumpsys","activity","activities")
        (d/"predeath-activities.txt").write_text(before)
        for slot in ("A","B"): act_record(before,ready[slot])
        assert str(ready["controller_pid"]) in adb("shell","pidof",controller).split()
        assert ready["controller_pid"] not in [ready["A"]["pid"],ready["B"]["pid"]]
        assert ready["A"]["task"]!=ready["B"]["task"]
        adb("shell","am","force-stop",controller)
        p.wait(timeout=20)
        counts={s:ready[s]["clicks"] for s in ("A","B")}
        snapshot("postdeath",ready,counts)
        for slot in ("A","B"):
            current=snapshot("before-tap-"+slot,ready,counts)[slot]
            x,y=current["x"],current["y"]
            l,t,r,b=map(int,current["bounds"].split(","))
            assert l<=x<r and t<=y<b
            (d/("postdeath-input-"+slot+".txt")).write_text(adb("shell","dumpsys","input"))
            adb("shell","input","touchscreen","-d","0","tap",str(x),str(y))
            counts[slot]+=1
            end=time.monotonic()+8
            while time.monotonic()<end and latest()[slot]["clicks"]!=counts[slot]: time.sleep(.2)
            snapshot("after-tap-"+slot,ready,counts)
        adb("shell","screencap","-p","/data/local/tmp/wb2-batch-postdeath.png")
        for phase in ("before","swapped","postdeath"):
            adb("pull","/data/local/tmp/wb2-batch-"+phase+".png",str(d/(phase+".png")))
            assert (d/(phase+".png")).stat().st_size>0
        (d/"result.txt").write_text("DUAL_OWN_TASK_BATCH_GEOMETRY_INPUT_AND_CONTROLLER_STOP_PASSED_NOT_OEM_ADMISSION\n")
    finally:
        if p.poll() is None:
            adb("shell","am","force-stop",controller,check=False)
            p.terminate()
