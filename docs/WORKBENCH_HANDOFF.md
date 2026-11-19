# 2026-11-19 最新有效接任检查点：Atomic Workbench 2.0
**本节优先于下方历史 V89 研发方向。** 机主明确授权本窗口独任研发负责人，旧窗口/PR 仅供只读参考；停止 V89 补丁路线。唯一生产研发分支 `workbench2/core`，目前 2.0 PR 0 个，最多允许 1 个。禁止历史分支变更、删除、合并或发布。保护原包名、实际有效签名、配置/工作组数据、成熟产品及设备适配成果。永久研发规则继续有效。此治理文件仅记录事实，不是产品代码。

## 第九阶段 A：公开宿主身份取证 + Android 运行时拒绝门禁（2026-11-19）
- 实际远程 HEAD `e98464ef4eacc6f89bd991ec0cd9d62ec03054ad`（父 `236dfa7ec2d7de2b823905abc50979e44064d44d`），同一 workbench2/core；仅独立工程和其专属工作流共七个文件变更；旧 app 代码、scope、签名、配置及历史分支不改。源码：[提交](https://github.com/x647368-maker/NeXtep/commit/e98464ef4eacc6f89bd991ec0cd9d62ec03054ad)。
- 实查旧 scope.list 没有 com.oplus.pscanvas，NeXtepHookEntry 没有已授权的 OEM 宿主诊断入口。不能把旧 Xposed 注入或普通应用包名当作授权。禁止新增注入/伪装或调用 OEM。
- 新 AndroidHostInventory 在调用方既有 worker 上显式执行，只读 Android 公共 PackageManager；窄包可见性声明不要求新权限、无组件、计时器、上传或 OEM 调用。报告公开的宿主版本、当前证书哈希、系统应用属性/启用状态、固件；不可见/不存在不能区分，授权/输入/归还未知，production_admitted=false。原两探针未改。未接入旧 APP UI，未部署。编译后检查 AAR Manifest 和实际字节码，确认仅公开包信息及摘要等调用，没有 OEM 调用/启动/上传。
- [CI 44537502993](https://github.com/x647368-maker/NeXtep/actions/runs/44537502993) 针对本 SHA：Android SDK 36.1 + assembleDebug 成功，三探针 class 核验及 artifact 上传成功；artifact 14537520111 实际下载，ZIP SHA256 `c0095c0674406d7b3f62e9e0aa235b2fb95e42c61801970460b6285637a464ed`；AAR SHA256 `83971b60c99ddbf5adf95ca0d3f2e6e989a87339247e438033c11c1f3572814a`。本地复验归档 CRC、checkout-sha、AAR hash、三类及合并 Manifest，均匹配。
- API35 stock emulator 实测完成，[CI 44537502993](https://github.com/x647368-maker/NeXtep/actions/runs/44537502993) 最终 completed/success；运行产物 14536348978 已下载，ZIP SHA256 `5b23d6e908e49d61e7858e84c5739ab12a63a1750039767ea343bde7706d868c`，本地 CRC/摘要核验一致。XML tests=3 failures=0 errors=0 skipped=0：stockAndroidPackageManagerDoesNotAssertHostAvailability、ordinaryProcessCannotEnterHostProbe、bothCollectorsRejectMainThread 均实际通过。模拟器 fingerprint `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`。三项均为拒绝态运行时测试，不能充当 OEM/PMS110 真机证据；未验证宿主正向权限、真实承载或归还。此阶段不输出用户 APK。
- 下一步：本轮 CI 与三项 XML 已核实，无需重跑；直接取得目标 PMS110 的现行固件/pscanvas 元数据和确切合法宿主授权入口。当前没有目标设备连接或经核实的授权入口；不得仅为了推进而硬编码准入、仿造原生证据或回到 V89。受控单任务承载、归还及输入前必须有独立证据。允许未来仅在已核实且用户授权的入口实施。原始有效第七阶段核心保留，不再造 JVM 模型。

- 第八阶段首个真实 Android 工程检查点：`236dfa7ec2d7de2b823905abc50979e44064d44d`（[源码](https://github.com/x647368-maker/NeXtep/tree/workbench2/core/workbench2)），父 SHA `305f595220a6c193202d6acbdb2ac20f6e250df4`。新增七个文件 227 行，旧文件零改动。复用独立 Gradle Android library；接管包 31 个 SHA-256、current 11 个 SHA-256 和 7 个历史 ZIP 完整核验。两探针 blob 与原件字节一致。只接入只读探针，统一核心的其余参考文件尚未迁入 Android module。
- [CI 44535052378](https://github.com/x647368-maker/NeXtep/actions/runs/44535052378) 对上述 SHA 真实成功：JDK 21、SDK 36.1、build-tools 36.0.0、compileDebugKotlin、assembleDebug、两类检查与上传全部成功。Artifact 14535701050 已实际下载，ZIP SHA-256 `400c168266567563c006e0bb4d468881a790c429fad812aafea094ae4aa4ecad`，AAR SHA-256 `201b84085f543dd886ddcac7b884a04dd427b3f4b1038bb4f89ec52a8131bfc9`；本地重新核对哈希、checkout-sha 和 jar 类名均一致。
- AAR 非 APK；未部署、未调用 OEM 方法、未取得真实宿主授权、真实任务承载/归还/输入/性能均未验证。不得出安装包或将编译冒充真机验收。
- GitHub 本次确认 #175/#176 仍开放且 head 与接管包相同；旧 20 个工作流触发条件审查后隔离 2.0 推送，不触发旧签名/发布流程。rulesets 仍为空；私有归档入口本次连接返回 404，无法独立确认可恢复性。不得因存档缺证据而清理历史。隔离验证依机主最新授权不受历史治理未完成阻塞。
- 下一项实际工作：仅在现有授权的 pscanvas 宿主入口审查并一次性取得真实固件/宿主元数据；先证明安全单任务归还与原生输入，再双任务、五任务和真实性能测试。禁止创建额外分支、增加全局 Hook、截图替代或假 APK。未确认宿主授权时保持停止部署，不伪造权限。
- 原始第七阶段接管包和独立编译工程保留，当前 Android 真实编译证据以 GitHub 上述记录为准。不再重复 JVM 模型或 V89 研究。

---

# 台前调度研发交接
仓库 x647368-maker/NeXtep。读取本页后核对实时 GitHub 状态。生产候选 v0.9.74-dev（89）。治理提交的父版本 295a1f0ae0a70c216e94cf836f36898c680777cb 为本次核对的生产 SHA。最新整体真机验收版本缺少可绑定 SHA 的完整记录。保留已认可的 v0.9.8 布局、v0.9.18 窗口比例与各专项冻结结果。

| 入口 | 用途 |
|---|---|
| #176 atomic-workbench/v89-held-receipt-current-20261119 | 唯一活跃动画修复候选，295a1f0a |
| #175 atomic-workbench/v89-domain-regression-recovery-20261119 | #176 父线，b625f225 |
| #173 atomic-workbench/v89-coordinate-only-20261118 | #175 父线与坐标回归对照，30498e1a |
| #171、#172、#174 | 已冻结实验与负优化对照；#174 的 8132026a 已被机主否决 |
| main、atomic-update-feed | 原项目入口及线上更新索引 |
| R11 27f7845e、eac1021c、118e1220、PR #122、V88 e35b99c3、V83 0e0733fe | 整合、生命周期、设备性能、UI、动画和历史性能基线 |

保护四小窗、真实任务输入、主窗比例与圆角、保存恢复、侧栏、常用应用、玻璃菜单、系统手势及通知栏。覆盖直屏、折叠/展开、平板双方向及 LSPosed/Vector。保留原签名覆盖升级、SHA 校验、设备适配、低功耗和历史回归。研究证据沿用现有 vivo 分析，不重复解包 ROM。

## 当前 P0
#174 把不同局部画布间的内容连续性假设用于实机，720×1120 到 1272×2772 构成反例。#175 收窄适用范围，#176 仅在指定设备自愿诊断试验核对当前几何回执。StageLiveSurfaceExchange.currentHeldGeometryCommitted 普通路径读取方向性提交标志，试验路径复核 leash、迁移预留和几何。confirmTerminalReceipt 约束终点事务，commitMove 再验任务身份。两者都缺少目标应用新内容已经在物理屏呈现的直接证明。StageEndpointContentMapping 可处理裁剪与映射，不能生成尚未产生的像素。当前真机仍报告卡顿、跳变和黑影。

唯一主路线是保留真实 GPU 任务镜像、原迁移与输入安全门禁，精确验证旧内容最后一帧到目标同代首帧的显示。按方向和代次串联任务身份、布局、事务 ACK、buffer 提交、屏幕实际呈现及输入恢复。先用录屏和现有单次采证找第一处不一致，再决定最小局部重构。分别核对过期回执、取消、菜单/手势、退出和 Surface 释放。没有证据支持删除备用路径、定时器或断言，也尚未确认泄漏。下一次只检验一个根因；建议先抽离回执与内容就绪的判定及同代失效测试，保持调度、几何和生命周期的外部行为。提交前后做双向、快速重复、旋转、退出回归。

使用同机、同布局、相同应用状态及温度对照实机最佳录屏。71/74 旧记录的交换中位约 337/365ms，仅作复测起点。核对首末帧、黑闪、黑影、圆角、轨迹、暂停/播放连续性、输入延迟中位和长尾、CPU/GPU、内存及锁屏耗电。CI 与独立真机结论分别登记。出现明显回退立即隔离并恢复已验证 SHA；不在失败路线继续叠加。

## 接任与归档
新窗口先读 AGENTS.md、本页及 docs/DEVELOPMENT_HANDOFF_POLICY.md，再检查 #176 和 #175 的实际状态及最新交接。项目机主的统一私有恢复包名为 AtomicWorkbench-GitHub-Branch-Recovery-20261119.zip；其中包含完整 Git bundle。私有文件标识和签名材料不要写入公开仓库。阶段结束记录工程门禁、实际设备/应用、失败证据、未完成事项和继任提交。

## 2026-11-19 第四轮接管记录
长期协调入口为 governance/workbench 上的本页。atomic-workbench/dev 和 #176 是研发读取入口。V89 唯一活跃修复沿 #176，#175 为父线；每次接管必须查询开放 PR，不沿用历史状态。整体验收 SHA 仍缺。第四轮从 168 个远程分支开始，新设 1 条隔离治理入口，实际删除 18 条，剩余 151 条。远程归档位于同一机主的独立私有 AtomicWorkbench-Private-Archive，根目录 RESTORE.md 与 round4-inventory.tsv 提供逐分支记录。私有归档包含 279 个原始头、169 个操作前快照与 4 个标签；Git 对象离线恢复校验已完成。私有访问需要账户授权。

治理工作流使用只读权限，检查基线引用、PR 父线、研发登记及近期新增分支。运行记录 44510842851。原项目默认分支未安装治理工作流，跨分支定时监督沿用机主既有外部任务。仓库内监控随治理分支推送等匹配事件运行。当前未配置强制 ruleset；维护者权限仍可改动治理文件。151 条引用含未核实的其他窗口依赖，详见私有清单。“保留待核实”不得视为永久必要性证明。
