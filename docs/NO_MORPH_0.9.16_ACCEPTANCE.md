# 0.9.16-dev｜窗口交换不形变专项修复验收

## 来自真实 ColorOS 17 用户反馈
- v0.9.15/30-phased-exchange 已进入 SystemUI，显示 runtime_ready OK、工作区通信已连接；真机一次交换 exchange_move=39.3ms、画面交接连续就绪=96ms。
- 不要把这个 39.3ms 与旧版 0.9.14 的 252.9ms 总交换时长直接比较；日志的测量范围不同，且新版仅一次样本。
- 两段 300×654 GIF 演示左侧4小窗/顶部4小窗下的普通点击交换：画面从小缩略窗口向主窗增大、切换瞬间看起来变形，用户要求彻底避免可见的外框拉伸、缩小和人物/文字压扁。
- 运行诊断还有非阻断的 systemui_startup_candidate FAILED ClassNotFoundException，但 runtime_ready 已成功；不要未经证据就删除诊断或宣布故障已修好。

## 实现与已修复元数据
- 普通非拖动交换由 NeXtepWindowController 传 freezeGeometry=true。
- WorkspaceSwapAnimator 的截图预览不再在主/小窗之间插值宽高、顶点坐标或者透视投影，而是在原位置原尺寸静态遮盖。真实任务在遮盖下面迁移，待原有正确帧和身份检查通过才统一移除。
- 静态预览的图片只做等比 FIT_CENTER，保留必要黑边避免人脸和文字横向拉伸；真实 live surface 自身几何由原有代码控制。
- 拖动交换仍使用原版拖放动画，本轮不改变已验收的拖动手势。务必单独验收这一条路径是否仍有变形；不要冒充已全面修复。
- 原样保留 app 四真实小窗、大小/圆角/布局、触摸、通知栏滑动、锁屏、工作组、Task ID、VirtualDisplay 资源和 Xposed 多模块兼容。
- **修复上版遗漏：** app/build.gradle.kts、META-INF/xposed/module.prop 同步为 versionName=0.9.16-dev/versionCode=31，SystemUI 代码自报 0.9.16/31-fixed-geometry。scripts/test-release-contract.py 增加两者一致性的防回归检测。

## 门禁
1. StageFixedFrameGeometryTest 纯 Kotlin 测试：等比、居中、无裁切、异常输入。
2. scripts/test-release-contract.py 版本一致性、原证书和渠道约束。
3. scripts/verify-state-continuity.py: 只允许精确审核的两处受保护动画文件和 RuntimeIdentity 标记增量，未批准的几何/Task/Surface 源码仍冻结。
4. API 101/102 Gradle test+build、原签名验证 SHA-256 b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd。
5. 一加 Ace 7 Pro ColorOS 17：10次连续主/小窗交换，每次截图/录屏确认外框不动、图片无伸缩变形，界面无过长冻结/黑屏、任务身份不丢。顶部和左右布局、屏幕锁解、通知栏小白条。Find N5折叠与展开随后验收。
6. 若静态截图不够及时或回交出现旧画面，请停在真实障碍，不能为了无形变削弱原有任务/Surface就绪和回滚保护。

## 发布规则
- 独立草稿分支；不影响已安装 0.9.15-dev/30，原包名与签名不变，不卸载覆盖。
- CI 构建通过但签名不存在时，仅产出 unsigned 验证件，不给用户安装。
- 未有 ColorOS 实际结果时不宣称完美无形变、0闪烁或省电。
