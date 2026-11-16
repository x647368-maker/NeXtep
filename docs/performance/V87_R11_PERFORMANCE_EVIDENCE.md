# V87 R11 性能证据与最终真机验收（测试专用）

生产基线 R11 27f7845e5f9419359170dcf9e15c5fccfbcffcae；PR120 118e122 冻结。仅 androidTest、独立 CI、分析工具与报告，不改生产、Gradle 参数、包名、签名、GPU 默认开关。不做 PR117 的 GPU flight/close-replace 测试。

## 已取得的原始历史证据（2026-11-16）

从实际 GitHub artifact 解压 real-task-migration.txt、exchange-profile-probe.txt、render-results.txt；全部 48 次旧迁移样本无删除，见 v87-historical-migrations.csv。统计 p50 用中位数，p90/p95 用 nearest-rank。旧 HWUI 的 p50 是排序后取 index n/2，p95 是 index floor(n*.95) 限制于末项；n=5 时 p95 实为最大值。

|版本|run / artifact|迁移 n|p50 ms|p90 ms|p95 / max ms|向主屏 p50|回虚拟屏 p50|最大 frame gap / n|旧 HWUI n/p50/p95|
|---|---|---:|---:|---:|---:|---:|---:|---|---|
|R9|43775829631 / 14186044874|16|94.953876|160.320398|161.741361|81.706106|102.953812|116 / 49|5 / 39.93608 / 67.428554|
|R10|43776749280 / 14187426036|16|102.860118|178.257174|189.195661|80.313659|137.407156|149 / 53|5 / 33.482113 / 71.439093|
|R11|43778048189 / 14186879279|16|100.916574|236.328840|272.014800|76.482877|124.370703|166 / 59|5 / 63.563392 / 117.584095|

历史运行独立 runner，并非受控重复。R11 272.0148ms 是 round=0 slot=2 task=11 display=12（第6次 move，返回虚拟屏）；236.32884ms 是 round=0 slot=0 返回显示10。全部16次迁移保留任务ID、8次返回有fresh buffer，但不可把ACK当可见像素。旧 TaskMigrationRegression.kt blob03e99137（R9/R10/R11完全相同）第41–56行：墙钟从runOnMainSync前开始，包含主线程派发排队、moveTaskToDisplay Binder、700ms guard内getRunningTasks/30ms sleep轮询及最后断言、同步返回等待。第35–40及107–119行的20ms轮询鲜buffer等待在move墙钟之外。旧 exchange-profile-probe 只有最后一次 #16（R9 binder11.1/observe6.1/total17.2；R10 4.1/10.3/14.5；R11 8.6/2.5/11.1ms），并非第6次样本，不可反推272ms真实细分。R11第16次外层墙钟119.90ms而末次内部telemetry11.1ms，表明外层等待/测量边界可能显著，仍不能把差额都归为排队。旧迁移区间未直接调用截图，但紧接前置渲染测试、线程与模拟器负载；不估算截图具体占比。

旧 RefinementRegression.kt blobce50e5d8 行100–103仅监听 activity.window；行110–170遍历top/left/right PopupWindow、物理触点和等待，独立popup窗口可能不计入该主窗口；行132截图PNG，行19 waitForIdleSync+sleep180；行172–194主动对比主线程与worker截图，行195–220故障注入；行221 snapshot到行224移除监听，未按布局采样、未做warm-up或计数门禁、忽略listener drop。旧报告无法判定5样本具体时间/布局，也不能把少样直接归因PNG/截图。不能用于FPS保证。

## 新取证入口（真实Android运行，非OEM）

- 单独 performanceOnly mode，与原 nativeMirrorOnly/diagnosticOnly/nativeFlightOnly/pairedFlightOnly 互斥。四个生产 TaskSwitcherView 的实际 TextureView；统一720x1120 producer；1272x2772/512dpi、3.2density，原生产 StageLiveCardLayout/StageAdaptiveLayout，保持同一 SurfaceTexture；连续30Hz目标软件canvas producer线程更新，主窗口vsync小标记强制HWUI采样。这是明确的fixture负载，不是第三方app/GPU exchange或手机中的实际producer帧率。
- 每轮布局先30有效warm-up，再至少120唯一有效稳定FrameMetrics；3轮顺序top/side、side/top、top/side；另一次生产3→4 count reflow，独立30有效帧阶段（包含前后持续负载，不声称全部30帧都在190ms动画内）。timestamp唯一、TOTAL>0、非first-draw、采样边界有效；保留无效/掉样行，不补数。每阶段60s上限，失败保留partial证据。实际回调与目标区分，四producer必须均有实际新buffer；当前仅采样完成且处理线程终止后才写raw，截图发生在全部采样结束之后；不在采样窗口内截图、PNG、同步文件IO。
- raw: frames.csv（intended/actual VSYNC、TOTAL/DRAW/SYNC/COMMAND/SWAP/DEADLINE ns、first-draw、drop、valid）、phases.csv（card posts/updates、main CPU、wall、callback count、完成/丢样）、metadata.txt（API、fingerprint、root/显示/density/refresh）、布局截图；迁移分段 wall/queue/main/Binder/observation/query count/query ns/sleep/main CPU/dispatch return、8次buffer计数及等待（新鲜ImageReader buffer不等于任务内容可见）。保留原700ms/30ms、4真实task/2轮/ID及buffer断言，无生产改动。
- 独立 Actions API35：同runner/同一启动后的emulator，R9/R10/R11逐个固定源SHA编译、同测试overlay、fresh app install、相同设置及工作负载。已公开固定顺序、系统缓存/背景负载非完全一致，严禁伪称严格控制的因果A/B。API37单独runner R11同分辨率/density/CPU/内存/动画配置，系统镜像/runner不同。所有原始记录、设备信息、前后dumpsys power/SurfaceFlinger list/memory、退出状态与summary.json/md留artifact；不上传签名APK。新CI结果未完成前不填新数字。任何样本不足、缺失、断言失败保留原始数据并fail；没有未经基线支持的速度阈值。

## R10→R11低功耗定向复核

生产tree实际仅7文件新增/变化：StageIssueDiagnosticActivity、StageSettingsScreen、StageGpuMirrorOcclusion、StageLiveSurfaceExchange、StageLiveSurfaceFlightGeometry、StagePairedRailCandidate、StageSmoothPairedRailCandidate。SystemUiRuntime 91c831e0、StageLockPowerPolicy3366ff83、controller19e13db2、VirtualDisplaySlot52cf016e、SlotTaskCoordinator bf03710f、StageMediaSignals ea55ec56、StageIssueAutoStop652b53b7、StageIssueSession d0d13fe6、StageIssueEventObserver f5a1e230、WorkspaceEnergyContract02b07422 均逐blob不变。GPU默认开关不变。

SystemUiRuntime 119–160: active含Suspended，单链remove旧lockStateObserver后安排2s亮屏/4s锁屏/15s物理屏off；inactive同时取消resume+预算。192–201 Keyguard/Display.DEFAULT_DISPLAY事件即时重读；454–473 SCREEN_OFF取消resume、清理交换、断开输出；SCREEN_ON/USER_PRESENT begin5备用，162–190锁住立即取消/真实可亮解锁恢复，未准备每次1s且take预算限制。Handler以uptime排队、在CPU运行时执行；回调会消耗CPU时间，不能推定它单独将CPU从suspend唤醒，WakeLock是独立电源约束；无证据证明零系统唤醒。非唤醒ELAPSED_REALTIME诊断alarm可能延期，不为5min门禁改wake策略。实际ROM系统CPU wake/WakeLock归因仍需设备dumpsys/batterystats或受控trace，普通应用端点报告无法证明。

7文件定向新增/删除检查：仅 StageLiveSurfaceExchange 新增有限movePost 64ms、终帧deadline位置重排、初始提交48ms的postAtTime、reveal terminal64ms；原1.8s watchdog及ACK后480/600ms/16ms有限handoff保留；stop()移除各callback，幂等隐藏并释放自有mirror/coverage/Transaction；获取失败finally独立释放，保留真实任务leash所有权；普通默认false。其余6文件无新增postDelayed/postAtTime/while/线程/AlarmManager/WakeLock/capture API关键调用；没有发现新后台截图/循环/wake alarm/WakeLock。静态路径并非运行资源全释放证据。测试独立runner收集前后power/Surface名单，仅普通app fixture，不证明SystemUI物理锁屏行为；专用GPU flight runtime交PR117不重复。

## 真机最终验收（对照同原签名版本，测试员无需命令）

Ace7Pro 主基准 20次交换+20次取消/关闭、10轮锁屏唤醒、屏幕录制短段；重点首次响应/视觉方向与轨迹/黑屏/变形/输入。N5/N3 外内屏↔顶部/左右/主窗优先、小红书页面、白竖条恢复，Y700横竖/微信/游戏冷启动，普通手机API35+、一加13补充。跨设备暂停不恢复播放、播放不被擅自修改、页面进度不被工作台无故重建，保持全部冻结功能。N3优先以诊断30/60min三组关闭/0窗/4窗断电同条件重复、夜间独立2–4h同环境系统电池记录；记录实际间隔/电量/温度/整机CPU同boot PID/slot帧/查询/截取，异常热或掉电立刻停止，勿用亮屏实时监控污染锁屏试验。此处不设置伪造的耗电阈值，也不替OEM验收。原签名87候选、开关配置与发包由总窗口统一。
