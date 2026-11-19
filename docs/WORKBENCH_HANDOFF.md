# 2026-11-19 最新有效接任检查点：Atomic Workbench 2.0
**本节优先于下方历史 V89 研发方向。** 机主明确授权本窗口独任研发负责人，旧窗口/PR 仅供只读参考；停止 V89 补丁路线。唯一生产研发分支 `workbench2/core`，目前 2.0 PR 0 个，最多允许 1 个。禁止历史分支变更、删除、合并或发布。保护原包名、实际有效签名、配置/工作组数据、成熟产品及设备适配成果。永久研发规则继续有效。此治理文件仅记录事实，不是产品代码。

## 第十阶段 A：统一核心实际 Android 工程接入（2026-11-19）
- 用户明确说明 PMS110 暂不在身边；原签名 field 工具已交付，不等待机器而继续可执行的 Android 核心接入。唯一分支 workbench2/core HEAD f58bfaeb7ba3fc94d2bf321fba8c1762e790c98b，父 ac4c2712a93f3d0fb2840f4ef82990606f1a298a。12 个文件变更，旧 app、field 工具源代码、包名签名配置及历史分支不改，0 新研发分支/PR。
- 独立 workbench2/engine Android library 接入 stage-7 current 三个原始源码，全部 SHA256SUMS.txt 原始校验通过，远程 Git blob 与接管包本地 git hash-object 逐一一致：UnifiedCore.kt 3fd3403c9d0d82c1d23f35827bf5ac47cc5a8885；MotionMath.kt 499d32e4d6cad80c1cd78904db48212819eaa1af；ReceiptBridge.kt d8439b4d04d18fb39e7dbac03908864302ce86a3。未重新造状态机或改动画数学；历史 87978 JVM 断言不是本轮 Android 或设备证据。
- 新 OwnerLooperPort 使用调用方现有 Android Looper/Handler，跨线程回执快照复制并投递，不创建常驻线程、轮询、定时器或 OEM 调用。保留迟到回执，不因取消清空收据。Looper 死亡时不能将无法投递视作释放；需要独立宿主死亡归还证明，当前未解决。核心没有生产入口、真实 NativePort 或准入证据；androidTest 假后端仅用于验证 Android 实际消息传递与统一核心状态变更。不得使用 Capability 全 true 测试值、预置身份 begin(scene) 或合成 scene 充当真实任务授权/归还。
- 实际 CI [44543976145](https://github.com/x647368-maker/NeXtep/actions/runs/44543976145) 已触发，job 135629464152；Android SDK 36.1 / assembleDebug 已成功，artifact 14539602390 已实际下载，ZIP SHA256 76838ca76955cf2676433ede081b300912da32e202c2864051ae1d7a42faa032，AAR SHA256 a72fe31e6568013c9706a235228e1419168d6e347f81d40aa1e2ed0249e06746。本地 CRC/归档/checkout-sha/source-blobs/四个 class 与 Manifest 全部验证匹配，无权限/应用组件；javap 复核 OwnerLooperPort 只构造 Handler 绑定现有 Looper，调用 Handler.post，无线程创建或定时调用。stock API35 emulator 正在启动，instrumentation 尚未确认；运行时三项门禁为 noCapabilityMeansNoDispatch、cancellationAndLateReceiptRetainReturnBeforeHostRelease、mismatchedHostReceiptCannotSettleOutstandingEffect。新旧专属 workflow 增加 path 过滤，之后 engine 改动不需重复构建 probe/field。本次变更 probe workflow 本身触发一次旧完整 CI [44543976251](https://github.com/x647368-maker/NeXtep/actions/runs/44543976251)，不改变上一轮交付 APK，不重新签名交付。不把未完成 CI 当作通过。
- 接续：先核实本轮 Android AAR/classes/source blobs 及实际 XML；失败按实际日志修复同一分支。已有原签名取证工具无需重做。下一工程任务为在 Android 真实图层上验证无权限的自有 surface 回执边界（如适用）与统一 core 的双任务交换失败/回滚，不能替代原生任务。用户手机回来后取得公开身份/固件报告，独立核实合法宿主入口和任务归还/宿主死亡恢复，再实现实际 native port 的受控单任务，不以 stock 模拟器或参考模型为 ColorOS 证据。禁止重复 V89 或增加全局 Hook。

## 第九阶段 B 最终闭环：独立真机取证 APK 原签名交付（2026-11-19）
- 唯一生产研发分支 workbench2/core HEAD ac4c2712a93f3d0fb2840f4ef82990606f1a298a。此次仅改专属 CI 离线签名输入归档，生产 app、其包名/配置/用户数据及历史分支不改。
- [CI 44540541263](https://github.com/x647368-maker/NeXtep/actions/runs/44540541263) completed/success，实际下载并独立校验 field-validation 14539236000 SHA256 f93cb4339c027183ebb035fe735318d213152931768931632dc23dc614c8db22、runtime-boundary 14538981487 SHA256 40464608406f678f21bc1715dd34a187a85a59417ffa5f51e320b0339e347860；XML 4/4、0 failure/error/skipped，原三项拒绝态及一次手动界面取证测试通过。只在 stock Android 15 模拟器，不是 ColorOS 验收。
- 离线签名输入 artifact 14538606768，归档 SHA256 463ae477f41c2b7e912ebe92b5f034d10d9af403f86fb3a1a4eb695bef98948d；输入 APK SHA256 16f009e3e78a53d91432fe44f2a52302509f4e5271fd4755d0af4f7a174eabf8。实际校验归档 CRC、文件清单和 SHA256SUMS、DEX 内完整 checkout SHA、release manifest 的零权限/唯一 Activity/无共享 UID/无 Xposed entry；用同归档 SDK apksigner.jar 及已核实的历史有效私钥离线签名。V3 验签通过，唯一证书 SHA256 3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe。签前签后全部 13 项非签名 ZIP payload 哈希一致。
- 真机交付文件 AtomicWorkbench2-HostEvidence-2.0-evidence-1-ORIGINAL-SIGNED.apk，2139397 字节，SHA256 fdb4aa30e58e93db788740b9ef414eb59842b51405be08a07d36596f9ce561e5；独立诊断包 io.github.x647368maker.atomicworkbench.hostevidence，versionCode=1，versionName=2.0-evidence-1。文件在本轮私测对话交付，不发布 GitHub Release。生产包名/签名未改变。GitHub 签名秘密仍缺失；本轮临时本地密钥及密码副本已清除，原保管文件未改。
- 接续唯一实际门禁：机主在 PMS110 安装此独立工具（不覆盖旧 APP、不启用任何模块、无额外权限），点击“一次性读取宿主信息”，将生成的文本报告发回；只确认目标固件上的公开包元数据，尚未证实宿主授权、真实任务承载、归还、输入或帧性能。拿到报告后按实际结果验证已有且获授权的原生入口，不伪造授权，不再堆诊断版本。无证据不得进入 OEM 调用或正式生产准入。已验证的编译及模拟器检查不重复。

## 第九阶段 B 验收及原签名离线交付恢复（2026-11-19）
- 上轮 CI [44539030424 attempt 2](https://github.com/x647368-maker/NeXtep/actions/runs/44539030424) 最终 completed/success，源码 SHA b28173b9269d8522282126c41781a45554b43b43。独立下载 artifact 14537253019（SHA256 edf912c5eb5b3cbd5bc5faacb4f2347ac1337371aa384608a3b34e113d2ead77）与 14538330840（SHA256 8ecb4ece81b029aaab9cbee6768afd6e14a0a360641f750722db002208bd11f6），本地 CRC 和摘要一致；XML 4/4，failures/errors/skipped=0，真实 stock API35 的三项拒绝态和 manualReadOnlyReportOnStockDevice 全通过。Release manifest 独立检查确认包 io.github.x647368maker.atomicworkbench.hostevidence、无 uses-permission / shared UID / module entry，唯一 launcher activity，非 debuggable、禁止备份。未签名 APK SHA256 0c021758d1c54a32aebd490bd75885b5b6bbf375fb8d8a328a882f2b47e1b95d；签名状态文件明确 MISSING_ORIGINAL_SIGNING_SECRET，没有签名 APK 产物。
- 当前隔离环境已实际找到并解锁用户既有原签名材料，经 keytool 证书 SHA256 独立比对为 3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe；PrivateKeyEntry 确认。密钥/密码不进仓库、产物、交接文档或日志，不上传到第三方；仅一次性离线签名，结束清除工作副本。GitHub Secrets 仍未配置，不把此状态误认为永久恢复。
- 新 HEAD ac4c2712a93f3d0fb2840f4ef82990606f1a298a，父 b28173b9269d8522282126c41781a45554b43b43；唯一改动为既有 2.0 CI 增加签名配置缺失时经所有门禁的未签名 APK、官方 SDK apksigner.jar、manifest、checkout SHA 及哈希清单的短期离线签名输入归档；保留所有测试，验证 zipalign、拒绝已签名输入，不发布 release。旧 app 和所有探针、界面源码零改动。新 CI [44540541263](https://github.com/x647368-maker/NeXtep/actions/runs/44540541263) 已触发，尚未确认最终结果及离线签名。
- 下一步接续：检查新 CI 最终结果、artifact 精确 SHA 和 SHA256SUMS，下载后在隔离环境用已核实的原私钥签名；核验同一原证书、APK 内容一致、包名与版本、签名完整性，才交付诊断 APK，不替换正常生产 APP。真机回报仅是公开宿主包/固件元数据，不等于授权、原生任务承载、归还或引擎性能；无授权证据继续禁止 OEM 调用。

## 第九阶段 B：独立真机公开取证工具（2026-11-19）
- 最新远程 HEAD `b28173b9269d8522282126c41781a45554b43b43`，父 `e98464ef4eacc6f89bd991ec0cd9d62ec03054ad`；同一 workbench2/core，仅九个独立工程/专属 CI 文件变更。3 项上一阶段运行门禁需随 Gradle 多模块接入重新验证。无新分支、PR、注入点、旧 app 变更或正式发布。
- 选择独立证据工具而不覆盖历史 V89：:field applicationId io.github.x647368maker.atomicworkbench.hostevidence 只用于真机手动取证，正式 APP applicationId、证书、个人数据及工作组完全不改。不启用 Hook、不启动 OEM、不增权限、服务、接收器、持久化或上传；普通应用可见性不是授权。点击后在有限 worker 查询公开 PackageManager 信息，报告含 checkout SHA，复制/分享需另一次显式操作；未授权、未证实的生产准入继续 false。
- [CI 44539030424](https://github.com/x647368-maker/NeXtep/actions/runs/44539030424) attempt 1 真实失败（job 135612120324，Gradle classpath 在源码编译前无法解析多个 Maven 依赖，含 kotlin-stdlib/reflect 2.3.20、annotations 13.0、httpmime 4.5.6、ASM 9.9），不是运行时或源码通过。此前同 AGP/SDK 已成功。原 SHA attempt 2 的 Android AAR 编译及类检查成功，artifact 14537591522 已下载，ZIP SHA256 b76b33958aefd6cd8e0cd62e51f9788c46995e6f5c12874445958bf651792435，AAR SHA256 83971b60c99ddbf5adf95ca0d3f2e6e989a87339247e438033c11c1f3572814a，与阶段 A 的 AAR 字节一致，checkout SHA 和 CRC/类名已复验。模拟器已成功启动。最后核实 job 135612875479 in_progress/pending；Provision stock Android 15 emulator:completed/success; Execute Android runtime boundary tests:completed/success; Build field companion and run actual UI inventory test:in_progress/pending; Inspect the actual release APK before signing:pending/pending; Sign field companion only with verified existing certificate:pending/pending; Upload verified companion only; never a release:pending/pending; Preserve field test and signing evidence:pending/pending; Preserve runtime evidence without APKs:pending/pending。未确认的测试/签名不得报告通过，不降依赖版本、不做盲目修补。
- CI 包含 3 个原拒绝态回归 + 1 个新真实 Activity 手动取证测试，release APK 必须独立检查 package、零权限、单 launcher activity、无 module entry、无 shared UID；原签名秘密缺失则记录 MISSING_ORIGINAL_SIGNING_SECRET，不上传安装包；配置存在时仅用已知有效证书 SHA-256 3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe 签名及校验，绝不临时替换签名或发布 release。该工具不能视作原生引擎或真机验收，签名和实际 OEM 授权仍待证据。
- 既有 PMS110 故障报告只能确认 Android 17/API37 和旧 V89 实际运行，不含该宿主包元数据/授权；无须重复历史动画研究。下步先读取此次 CI 最终状态、XML、Manifest 及签名产物；通过且原证书验证后才提供真机公开身份取证工具。若无法签名，明确阻塞，不绕过签名。目标宿主真实授权仍需另行独立证据；禁止硬编码准入、私有标志、root 指令和未经核实注入。

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
