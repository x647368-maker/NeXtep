# 主小窗交换取消时预览残留｜可复现根因及精确修复

现象：v0.9.25-dev #42988340327 模拟器 HWUI 真实画面回归测试 `PhasedPreviewRegression.kt:51` 失败：`Rollback preview did not settle within 3 seconds`。此错误在之前多个版本也出现，不能当作 Y700 本次布局已验收。

定位：保持原样的 3 秒超时及严格断言，增加了取消执行后立即状态快照。#42989851797 失败报告：

```
afterRollback=running=true count=0 allowed=true finished.count=1
sourceRecycled=false targetRecycled=false
```

说明测试确已将取消条件打开，仍未执行工作台预览的取消、回收与唯一完成回调。审查 `WorkspaceSwapAnimator.kt` 发现 `ValueAnimator.ofFloat(...).apply { ... }` 内部的 `onAnimationEnd` → `commitMove` → `completed` 失败分支使用了不带接收者的 `cancel()`。Kotlin 外层 `apply` 隐式接收者是 `ValueAnimator`，该调用可能只取消已经结束的 ValueAnimator，而没有释放 `WorkspaceSwapAnimator` 维护的 overlay、bitmap 和 `completion`。

修复：**只更改一个调用目标**，由 `cancel()` 改为 `this@WorkspaceSwapAnimator.cancel()`。保留 `releaseAllowed` 事务保护、用户中途取消语义、帧提交/等待真实帧、单次回调及现有动画时间配置。不调高 3 秒阈值、不关闭测试、不冻结真实任务、不降低画质。

风险/验收：新改动可能改变实际回滚的回调执行时刻；必须通过同一提交上完整 Android 构建、原 362 项 JVM 单测、`PhasedPreviewRegression` 真 HWUI 的 bitmap 必须回收且 onFinished 恰好一次，以及其后的 `TaskMigrationRegression` 和 `ShadeRegression` 视觉链。还需要多次重复运行以排除偶发通过；不能把 CI 通过直接等同于 Y700 真机完美。保持 PR #22 草稿、不发布新的 APK。
