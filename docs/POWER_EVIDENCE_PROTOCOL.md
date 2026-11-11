# 台前调度｜息屏电源取证方案（只读）

**目的：查明虚拟显示组是否影响休眠，不是证明已经省电。** 不修改系统、不安装 APK、不获取 Root、不重启、不读取私人应用内容、不上传数据。

## 第一步：无需电脑的息屏电量对照

在同一台设备、类似室温和信号条件下，交错测试两种情况各至少 30～60 分钟：A. 完整退出工作台（必须确认虚拟显示已释放；任务迁移失败或延期时退出可能不完整，不能作为干净基线）；B. v0.9.19 保持四小窗后息屏。两组最好各重复两次，比较平均功率、Wh、百分比和温度，并注明设备/ROM、正在播放的音视频及网络模式。**整机耗电绝不能全部归因于台前调度。** 无需为了测试反复卸载或跨签名降级。

## 第二步：只读状态快照（Mac + ADB）

用户主动安装 Android Platform Tools、授权 USB 调试并连接**恰好一台设备**时，在仓库目录执行：

```bash
bash scripts/collect-power-evidence.sh workspace
bash scripts/collect-power-evidence.sh baseline
```

每次运行创建权限收紧的本地独立报告，路径由终端显示。仅将 `dumpsys battery/power/display` 中的白名单数值抽取到报告；**不再转存原始 dumpsys 行、完整 logcat 或原始设备标识**。如果 ROM 不输出所需字段，要记录“不可用”，不能自动推断为休眠正常。脚本只读，不会上传、写入设备或改变工作区。

**USB/ADB 自身可能让设备保持唤醒或被供电。快照只代表采集时刻，不能用于计算息屏电量，不是连续深度睡眠验证。** 真正电量对照需在未连接数据线时进行。

另外，现有代码 `VirtualDisplayFlagsCompat.resolveCandidates()` 打印的只是**候选标志**，并非创建成功所用的最终标志。诊断结果中若仅有未关联显示组编号，也不能声称那些组由 NeXtep 创建。报告里的 `slot_record_count` 只表示当前 dumpsys 中识别的不同槽位名称数，**为 0 也可能是权限或输出格式不支持，不能直接证明虚拟显示已释放**。需通过实际设备权限允许的系统状态核对，做不到就标“未知”。

## 安全与验收门槛

请在分享任何报告前人工再检查脱敏。只读脚本的安全测试可以运行 `bash scripts/test-power-evidence.sh`；它使用伪造 ADB 数据，不连接手机。必须同时对照息屏耗电、解锁时延与四小窗任务连续性，才考虑批准 PR #16。仍不得自动合并、正式推送或把任何模拟器和单元测试称作省电实测。

## 按电源组观察（有限信息）

只解析 AOSP `dumpsys power` 的 `Display Group User Activity:` 段，记录各 `displayGroupId` 对应的 `userActivitySummary`、`mWakeLockSummary` 数值。若系统未输出该段，报告会明确标为 `power_group_section=not_available`。这些摘要不是每组完整的 Wakefulness 状态，也不能单凭组号认定是台前调度创建的；必须再交叉核对实际显示归属和息屏耗电。
