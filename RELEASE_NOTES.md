台前调度 v0.9.68-dev（versionCode 83）｜真实任务查询异步化专项私测

v82 已通过 development、Android15 HWUI visual-regression、API37 native mirror CI。深度检查发现正常交换的初始任务快照、提升前核验、降级前核验、显示迁移等待轮询及最终提交核验仍会在 SystemUI owner Looper 同步执行 ActivityManager.getRunningTasks 与任务信息反射解码。将这些仅限正常异步双任务交换路径的任务查询移入已有单飞 Binder lane，包含初始快照、提升/降级前核验、每次既有显示观察轮询、最终提交核验；原轮询间隔、截止时间、查询次数/时序、任务身份/前台顺序/显示器/布局/缓冲校验不变。同步保留回滚路径和所有其他入口，不新增线程、轮询频率或系统查询次数。非可调整大小任务的 setTaskResizeable 也移至同一 lane，真实返回后重新查询身份/布局再继续。任务快照反射解码在 worker 执行；TaskSurfaceCompat 观察状态使用 ConcurrentHashMap，UI/Surface 写入仍留 owner。仍有 runTransition 的初始前台资格查询与SurfaceControl呈现留在owner，后续单独评估。自动化通过前不得交付；不宣称真机性能改善，不改main、不公开发布。

