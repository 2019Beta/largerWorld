# 矿车 cell 边界交接检查（2026-09-29）

## 已有记录能证明什么

重新读取了 `build/minecart-handoff-trace-20260928.jfr`，记录长 47 秒。
其中 `teleportGraphInPlace` 调用 108 次，连续交接 `accept` 调用 95 次，
`consumeTargetSpawn` 调用 1,441 次，`observeTargetSpawn` 调用 1,394 次。
最后一次服务端交接方法开始于 UTC 13:49:07.661343700，客户端接收标记、
处理 spawn 分别开始于 13:49:07.665258700 和 13:49:07.665296500。

这些是方法调用计数，不是成功交接计数。JFR MethodTrace 不含实体 ID、
参数或返回值，不能据此断言某辆矿车完成注册、spawn 被成功消费，或彻底排除
区块 tick 问题。它也不是可以查询实体字段的 HPROF 堆转储。
另一份已有记录为 `build/minecart-join-boundary-20260928-2200.jfr`。

当前 `run/logs/latest.log` 在 2026-09-29 16:04:55 记录了“未找到实体”。
该行没有选择器文本。带位置约束的原版选择器可能仅查询当前 ServerWorld，
所以这条提示本身不能证明相邻 cell 中也不存在该实体。

## 从当前源码与本地 Minecraft 字节码确认的缺陷

1. `ServerChunkLoadingManager.EntityTracker.equals/hashCode` 只比较实体 ID。
   `CellViewTracker.PlayerState` 原来用普通 HashSet 保存已监听和期望监听的
   tracker。一个 shadow tracker 被同 ID 的新实例取代时，`contains` 错误地
   认为新实例已经监听，跳过 `startShadowTracking`；随后集合还会换成这个没有
   listener 的新实例，之后也不再补订阅。已保留的客户端矿车因此可能失去后续
   位置更新，表现为卡住、交互位置与服务端分离。
2. 空车的 `ClientContinuousEntityHandoff` 与载人图的 `ClientEntityHandoff`
   原来独立保留五秒的过滤状态。空车 A→B 后上车并 B→A，两个状态可能同时
   把 A、B 的更新分别当成旧源数据丢弃；真正的销毁包也可能被旧模式吞掉，
   留下客户端幽灵。现在每次 BEGIN 撤销另一种模式，真正销毁时清理两种状态。
3. 连续交接窗口中的重复目标 spawn 原来直接丢弃，不更新相对移动基准。
   tracker 重新建立监听后，其 spawn 基准必须被消费，否则后续 delta 会从
   旧基准累加。现在更新网络基准，同时保留对象和视觉插值。
4. 本地字节码确认 `ServerPlayerEntity.startRiding` 会先更新座位位置，调用
   `requestTeleport(EntityPosition, Set)`，然后发送乘客包。跨 cell 右键路径
   此时仍在 `runInWorld` 的临时投影中，后续才执行永久玩家迁移；连续交接
   重建乘客关系也会再次调用该方法。现在这两种路径不发送中间瞬移请求，
   由乘客包附着到保留的客户端载具，既有迁移代码负责服务端位置与骑乘基准同步。
   普通同 cell 上车仍保留原版行为。

## 验证边界与复测

五个修改的 Java 文件通过 JDK 21 parser-only 语法检查；本地映射 jar 的
`javap` 输出核对了 tracker 相等语义和 startRiding 的调用描述符。
未运行 Gradle、项目编译、Minecraft 或新一轮录制，不能声称游戏内问题已验证消失。

重新构建并重启后，重点检查：

- 人站在边界一侧，空矿车反复往返，随后立即攻击或右键上车。
- 五秒内连续完成空车跨界、上车、反向跨界、下车、破坏矿车。
- 玩家和矿车同时换 cell，以及 X/Z 两向和 cell 角点处的 tracker 替换。
- 客户端新加入时矿车已经运动，以及相邻区块重新进入可见范围。
- 普通同 cell 上车、跨 cell 上船/骑马仍正常；移动中上车没有额外中间跳跃。

## 骑乘跨界短暂下车的后续修复

本地 Minecraft 1.21.11 字节码确认：`Entity.setRemoved(CHANGED_DIMENSION)`
仍会让子实体执行 `stopRiding`。`SeamlessCellTeleport.removeGraph` 先移除
乘客再移除载具，所以当载具移除时，玩家已标记为 `CHANGED_DIMENSION`。
`ServerPlayerEntity.dismountVehicle` 此时发送空的乘客列表，随后
`restoreRidingGraph` 又调用 `startRiding` 发送完整列表。客户端原版处理乘客包
会先执行 `removeAllPassengers`，从而出现短暂下车再上车。

现在仅在 cell 交接且玩家移除原因为 `CHANGED_DIMENSION` 时，跳过
`dismountVehicle` 的那次乘客包发送。服务端的脱离与图恢复照常执行，
目标 cell 的最终乘客包仍发送；普通主动下车及其他维度变更仍走原版路径。
JDK 21 对新增 Mixin 完成定向 `javac` 类型检查，并用本地映射 jar 核对
了两个 `sendPacket(Packet)` 调用点的顺序；未运行 Gradle 或游戏。
