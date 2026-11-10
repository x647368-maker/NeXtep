# 备用窗口2 · 更新日志补丁交接（独立分支，主窗口优先）

## 安全边界
- 基线：`atomic-workbench/no-morph-0.9.16` @ `7cab4f85a8b78843e5a37bc5d422736db7d8cf54`。
- 补丁分支：`atomic-workbench/assistant2-changelog`；只改此分支，不修改 `main`、`dev`、稳定 `v0.9.8`、`v0.9.15` 或原 `no-morph` 分支。
- 不调整 APK 版本、包名、证书、发布渠道，不写入任何私钥，不创建正式 Release。
- 不改 SystemUI、LSP、Surface、虚拟显示、任务交换、输入、手势或窗口几何。

## 已实现
- 设置 → 关于 → 更新日志（独立原生滚动页，支持返回到“关于”）。
- 覆盖 0.9.0～0.9.16 每个大/小版本里程碑，另记 0.8.0、0.8.1、早期 0.1～0.4 阶段。
- 用“开发候选”“待真机验收”“测试未通过”“用户真机认可的部分稳定基线”等标识，防止把测试失败或源码提交说成已修复。
- 纯本地常量/原生 TextView + LinearLayout，按用户进入页面时才创建；不添加后台服务、更新轮询、网络、数据库、Compose 或任何第三方依赖。
- 标注 0.9.15 实机交换形变问题及 0.9.16 候选仅覆盖普通点击交换的限制。

## 更改文件
- `app/src/main/kotlin/io/github/lujinxin/nextep/config/StageSettingsScreen.kt`：添加“关于”入口及导航（仅 UI）。
- `app/src/main/kotlin/io/github/lujinxin/nextep/config/StageChangeHistory.kt`：本地历史数据。
- `app/src/main/kotlin/io/github/lujinxin/nextep/config/StageChangelogPage.kt`：懒加载、逐项展开的 UI。
- `app/src/test/kotlin/io/github/lujinxin/nextep/config/StageChangeHistoryTest.kt`：里程碑范围、无重号、状态提示检查。
- 本交接文档。

## 自动同步规则（新增）
- **构建时自动同步**：`app/build.gradle.kts` 通过 `embedCurrentReleaseNotes` 从仓库唯一的 `RELEASE_NOTES.md` 生成 `assets/current-release.md`，APK 中可直接离线读出，无需再同步修改 Kotlin 版本列表。
- `StageEmbeddedReleaseNotes.parse` 按实际已安装 APK 的版本名、版本码匹配内容；不匹配则拒绝展示错误版本的发布说明，回退本地核实过的历史版本。
- CI 在编译后检查 APK 中的 assets 与 `RELEASE_NOTES.md` 字节一致，原有签名/版本/源码保护检查不变。
- **其他开发窗口完成代码不等于版本完成**：只有新改动汇入确定的构建分支、`RELEASE_NOTES.md` 如实描述重大变化、版本号与版本码通过校验、生成新 APK 并安装后，本机才能看到新条目。工作模式在准备每个版本时应同步维护这一份权威发布说明，不要求用户手动复制到更新日志页。
- 已安装的旧 APK 仍展示其打包时的本地内容；不会因为其他分支有新 commit 而静默联网或重写界面。没有后台联网、推送、轮询或跨会话实时消息监听。
- **自动历史归档**：构建时脚本 `scripts/generate-release-history.py` 读取 Git 提交历史中的 `RELEASE_NOTES.md` 历次修订，并打包为 `release-history.json`；运行时按需展示最新版本之外的中间版本。CI 对开发分支使用完整 git checkout，验收 JSON 随 APK 构建进入 assets。老版本 0.8.0～0.9.16 优先沿用人工核实的静态历史；新的中间版本自动补全。单份说明上限 32KB、最多 60 版，避免 APK 过度膨胀。若用户用仅含源码 ZIP、无 Git 历史构建，脚本只收录当前说明，不能宣称旧版归档完整。
- 禁止由 Git commit 文字自动宣称真机测试通过或“彻底修复”。

## 测试与整合规则
- 分支触发隔离 GitHub Actions，须检查 `scripts/verify-state-continuity.py`、`scripts/test-release-contract.py`、API 101/102 Kotlin 编译/JUnit；只有真机能最终验收页面与交换。
- 变更记录不证明耗电 0 增加；按需创建的文本页理论上无常驻开销，体积仅随文字代码增加。
- **不要整支覆盖主窗口后续源码**。在主窗口完成 0.9.16 修复、冻结目标提交后，仅按这几个文件做择优移植/三方合并。保留新分支的测试及文档。
- 通过后由主窗口统一完成编译、原证书签名、安装与真机验收。没有原证书时停止交付安装包，不生成替代签名。
