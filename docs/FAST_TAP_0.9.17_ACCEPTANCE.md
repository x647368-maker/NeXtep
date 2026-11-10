# 台前调度 0.9.17-dev — 点击交换提速专项

## 用户真机基线
- v0.9.16-dev 普通主窗与小窗点击交换已经显示窗口外框稳定、不再拉长缩短（反馈视频 3286.gif / 3287.gif）。
- 用户仍感觉反应慢，期望更快但不能牺牲“绝不拉伸人脸、文字、圆角外框”的已经成功效果。
- 两段原视频总长分别约 6.00 秒和 8.96 秒（含多次操作）；不能直接将 GIF 总时长当作单次任务切换耗时。

## 已经识别的无用等待
原动画在 `WorkspaceSwapAnimator.animate` 内仍按设置的 `200ms`（可选 100–1000ms）执行 `ValueAnimator`，即使 `freezeGeometry=true` 的预览帧全程不发生形状或位置变化。**直到 ValueAnimator.onAnimationEnd 后才请求任务迁移。**

## 精确增量变更
- 新增 `StageSwapTimingPolicy.previewDurationMs`：仅当 `freezeGeometry=true && returningHome=false` 时缩短不可见动画到 1ms（下一次帧调度）。
- 完全不改 `registerFrameCommitCallback`、160ms 保底回调、`moveAsync` 分阶段任务迁移、`MIN_HANDOFF_DELAY_MS=80`、`MAX_HANDOFF_DELAY_MS=600`、`StageStableHandoff` 连续两次就绪、`cancelRequested` / `releaseAllowed` 的回滚门禁。
- 不改 `SlotTaskCoordinator`、`VirtualDisplaySlot`、布局几何、圆角、触摸、折叠屏参数和原始拖拽动画。
- APK 与 Xposed 版本同步 0.9.17-dev/32，运行代码自报 0.9.17/32-fast-fixed-tap；原签名证书指纹必须维持不变。

## 验收与风险
- JVM 测试：无动作点击路径为 1ms；拖动和回桌面仍使用 100–1000ms 旧配置；冻结源码精确可逆保护通过。
- Android 编译与 API101/102 必须通过；已有全链路模拟器视觉回归在 v0.9.16 就曾被菜单交互失败阻断，不得把未跑完标为成功。
- ColorOS 真机：主↔小连续 10 次，两种布局，四个独立小窗内容均正常；无瞬间闪屏/黑屏、窗口外框形变、错位、残留预览；锁屏/解锁、通知栏和小白条、任务恢复。另测拖拽和 HOME 仍正确。
- 估计最大可省掉约 200ms 的“无动画等待”；这不是测得的端到端性能增益，截图捕捉、任务迁移和真实画面交接仍有自己的开销。
- 如果取消后预览无法释放或任何 Surface 交接异常，保留旧版 0.9.16 并停止升级测试。
- 不发布正式版、不合并主分支；只准备候选 APK + 原私钥离线签名交接。
