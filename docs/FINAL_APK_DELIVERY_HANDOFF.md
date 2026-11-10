# 台前调度｜最终 APK 集成交付契约（2026-11-10）

> 唯一目标：交付比 v0.9.14 更稳定、流畅、尽量省电和低发热的 **原签名、原包名、可覆盖安装 APK**，保留全部已验收的功能。此文件是最后工作模式的可执行接续契约，不是新研究题目。

## 0. 必须使用的现成成果（不要重新研究、重做）

- Repo `https://github.com/x647368-maker/NeXtep`
- 工作模式暂停时的 Android 集成：`atomic-workbench/android-exchange-session`，基准 `ff041691ab32a546ba293a0ae3c4fc89dbb2fb5c`
- **此刻优先使用的最新集成分支**：`atomic-workbench/assistant-verified-patches`，以实际 HEAD 为准（PR #4）。它是前者的直系后代，不是平行的另一版 App。
- PR #2 的 VD 资源保留修复已包含在这条提交链内，不需重复 cherry-pick。草稿 PR 保持不合并到稳定版。
- v0.9.8 真机确认布局基线：`atomic-workbench/v0.9.8`，`273c4d61d3a70b16ec7bdc5a266d19e416a7be41`。**不得 checkout 这个旧提交替换当前成品功能**。
- 被证明无效且已撤销的三轮优化：`d871e1cd`、`c4965dd`、`7cb91d`；禁止重试、换参数包装成修复。
- 精确安全修补与审查：`docs/ASSISTANT_VERIFIED_PATCHES.md`、`scripts/reviewed-session-hardening.json`、`scripts/verify-state-continuity.py`。不得绕过源代码冻结保护。

## 1. 当前已实际完成的代码

- 在 Android 生产源码里建立 `ExchangeSession` 分阶段主小窗交换；同步动画协议改为可等事务结束的异步完成；锁屏/退出/任务资源暂存、回滚和失败待恢复纳入主线程会话协调。
- `VirtualDisplaySlot` 的生产修补：活动期纹理丢失时保留任务所依赖的虚拟显示，后续可重新绑定输出与手动重连。
- 附加的 fail-closed 修补：`PREPARED` 阶段因取消或超时结束时，必须重新核对两任务、Display、顶层身份；未验证原位置则保持 `RECOVERY_REQUIRED`，禁止冒充 RESTORED 后释放显示资源。
- 交换成功后的异步预览回调若抛异常，捕获并继续执行协调器的 `finish()` 清理，避免重复移动任务和异常向 SystemUI 主 Looper 传播。
- 本地纯 Kotlin/JVM 测试：**12 个测试方法通过；5000 个确定性场景通过**。分别见 `scripts/fast-check-exchange-session.sh`、`app/src/test/.../ExchangeSessionTest.kt`、`scripts/test-support/ExchangeSessionStress.kt`。
- 发布堵点已修复：`app/build.gradle.kts` 现在为 `versionCode=30`、`versionName=0.9.15-dev`，与现有 App 更新渠道及 `scripts/prepare-update.py` 相容；原先的 `0.9.15-candidate` 会被原发布脚本与 App 更新解析器拒绝。增加 `scripts/test-release-contract.py` 及 CI 步骤；`RELEASE_NOTES.md` 已匹配当前候选。
- 这些本地结果仅验证相应纯状态机与版本契约，不代表完整 Android 构建通过；不要把 5000 个模型场景说成真实 ColorOS 帧测试。

## 2. 唯一剩余的高耗时交付路径：先恢复一次自动构建

1. 读取该分支最新 HEAD、PR #4、工作树和 CI 状态，**不重做代码研究**。
2. 解决 GitHub Actions 仓库工作流被禁用/暂停的状态（若需要账户 UI 权限，使用已有 GitHub 授权云浏览器；需要明确用户授权时只问这一件事）。不要把 disabled 当成编译失败，也不要反复开新的 emulator。
3. 先执行全部廉价门禁：
   - `python3 scripts/test-release-contract.py`
   - `bash scripts/fast-check-exchange-session.sh`（有本地 Kotlin CLI 时）
   - `python3 scripts/verify-state-continuity.py`
   - 只针对真实出错的文件修复；保留精确可逆冻结审核，不取消检查。
4. 在有 Android SDK 与构建缓存的 runner 上执行 `scripts/ci-android-build.sh`，覆盖 API 101/102 相关测试、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`，核对启动资源、模块入口、包名及证书。**无需通过在聊天里反复思考来代替实际执行**。
5. 若 GitHub CI 视觉/交互运行可用，只尝试 **一轮设置时限的模拟器启动与回归**。此前工作模式的本地模拟器启动超时 180 秒，是环境启动失败，不等于 APK 代码崩溃。禁止重复创建镜像、重装 N 次或把模拟器测试说成 ColorOS 测试。若环境不可用，保留已成功的单元/编译结果，标记 runtime unverified，停止该模拟器分支，不把失败吞掉。
6. 发现具体源码失败，只修失败原因、一次一个补丁、一次对应回归；不得重新设计整款 App 或切换到未经证明的性能猜想。

## 3. 签名、安装包和发布的硬条件

- applicationId/package `io.github.x647368maker.atomicworkbench`
- versionCode `30`（必须大于当前已安装 code29）；候选 versionName `0.9.15-dev`；正式稳定版需要在实际真机验收后另增 code，不复用 30。
- **原证书 SHA-256** `b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd`，必须使用旧版相同的私有签名材料；不得换测试 key / 生成新 debug key / 把 keystore 上传公开仓库、网页或聊天。
- 原始包已安装用户数据、工作组和设置必须可保留；不要求卸载/清数据/Root 重新配置。
- 工作流 `.github/workflows/atomic-android.yml` 已具有在环境秘密可用时原签名构建、验证 `apksigner` 指纹、上传 Artifact、调用 `scripts/prepare-update.py` 生成哈希/元数据的步骤。**先检查这些现成环节**，不要重新写打包系统。
- 仅当编译、JVM、冻结、包/签名检验达到对应证据门槛时，提供可访问的 **候选 APK 文件链接**，包括 SHA-256、HEAD commit、完整实际测试表。若未通过关键安全门禁，不能假造 APK 下载、不能将旧版标为新版本。
- CI Artifact 若为 UNSIGNED，严禁让用户直接安装；如果原签名材料在当前授权环境确实缺失，准确说明阻塞且停止，不要求非技术用户自行签名。
- 无论候选是否能编译，不能提前宣称“ColorOS已修好”或“续航有提升”。

## 4. 真机验收与体验目标（本阶段用户只负责手机点按）

优先一加 Ace 7 Pro (PMS110 / ColorOS 17 / Android 17，实际 Vector/KernelSU 注入版本以手机诊断为准)，随后 OPPO Find N5 内外屏。

**冻结的体验资产：**
- 0～4 个真实可交互小窗，顶部/左侧/右侧布局和主窗比例与 v0.9.8 实机满意形态一致，完整等比、圆角、映射正确；不是截图缩略图冒充真实任务。
- 主小窗双向交换、工作组、快捷栏、媒体布局、添加/关闭/替换、主窗优先/完整显示。
- 原生 ColorOS 通知栏/控制中心完整下拉与底部手势上滑收回、三点菜单尺寸/层级/关闭、触摸/键盘/返回/多窗口稳定；无黑罩或原版 NeXtep 闪图。
- 系统锁屏、恢复、退出、任务消失、重复点击、超时、异常回滚时不误销毁真实 Display、不丢任务归属；失败显示可恢复状态而不冒充成功。
- LSP/Vector/API101/102 实际注入与多模块共存，工作区通信“已连接”；如果 APK 更新后仍加载旧 SystemUI 字节，需要正常重启后再验收。

**旧版定量证据**：真机 v0.9.14/29 的一次 exchange_move 252.9ms（main=true），任务查询 Binder 147.2ms/14 次、解码 54.4ms/14 次，画面连续就绪 98ms（内含原有最少80ms等待）。数据存在嵌套、不宜直接相加，只有单次样本，不是平均值。新版是否改善必须在同机同负载比较多轮真实交换与交接稳定性，不得使用模拟器数值替代。

**省电、低发热目标**：在完成并真机确认任务安全/动画正常后，利用真实活跃/空闲轨迹评估 1 秒后台任务轮询、重绘、壁纸捕获、虚拟显示输出开关与 CPU/GPU 工作量。每次仅改一项有证据的浪费并实测回归；不准为省电冻结真实小窗、禁用任务回调或降低用户希望的画面流畅性。没有功耗证据就先不动关键计时常量。

## 5. 消耗额度止损和回报格式

- 不允许再让模型做耗时数十分钟的“分析/方案/微优化”循环。已经冻结的代码与 GitHub 交接是事实基础。
- 一个阻塞点最多做一次有明确原因的修复及一次验证；若环境失败立即停在真实阻塞，不再次上高强度模型。
- 不要让用户操作 adb、Gradle、keystore、GitHub 命令；只有确认本机 ColorOS 功能需要点按验证时才让用户安装。
- 输出必须简明：最终分支/提交、编译/JVM/冻结/模拟器分别的 pass/fail/not run、证书/包名/版本/哈希、**真正可点击的 APK 文件下载地址**、ColorOS 未验收项。若失败则只报告唯一阻塞点及代码已保存的具体 commit，不承诺假完成。
- 先交付可验证的 **0.9.15-dev** 候选 APK，手机确认质量之后再选择是否把版本推进到正式稳定版；若改完代码但无签名证书或 CI 环境，必须诚实停止。

## 6. 下一位工作模式收到此文件后的第一步

使用上述分支最新 HEAD，**先跑低成本静态/纯 JVM 检查和恢复既有 GitHub Actions，而不是继续做新的 ExchangeSession 分析。** 如果可以拿到原签名，直接进入有限的 Android 构建、自动验证、候选文件交付。后续的性能/省电优化严格依据首次真机验收数据再开展。
