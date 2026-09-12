# 骑乘矿车跨 cell 诊断（2026-09-12）

## 结论与证据边界

已附加游戏进程 7184，重启后重新附加 5596。实际使用的是 `DefaultMinecartController`，不是实验性矿车控制器。

本次捕获的跨界事件没有服务端速度归零：例如矿车 12344 从 cell 0 到 cell 1 时，`SNAPSHOT`、`AFTER_TELEPORT`、`AFTER_RESTORE` 的 X 速度均为 `1.687186398417547`；反向均为 `-0.9577607732395099`。不能把速度字段与实际位移混同：默认矿车控制器的实际水平移动受 0.4 格/tick 限制，内部速度仍能继续增大。

服务器线程只读采样捕获到矿车 5116 在 cell 1 的局部 X `-524283.49000000954` 处变为速度 `-0.02`，此前速度为 `2.054`；此处距边界约 4.51 格，是回弹点，不是跨 cell 迁移瞬间。与原版动力铁轨碰到阻挡后反向启动的 0.02 分支相符，但没有采集该 setter 的调用栈，因此不将具体分支视为运行时已证实。

## 骑乘交接的坐标基准缺口

`src/client/java/org/devt/largerworld/mixin/client/EntitySpawnPacketHandlerMixin.java` 的 `largerworld$ignoreDuplicateSpawn` 对匹配的骑乘交接出生包执行 `ci.cancel()`。

它调用的 `ClientEntityHandoff.shouldIgnoreSpawn` 只标记 `targetTrackerSeen`，没有用目标出生位置更新保留实体的 `TrackedPosition`。原版 `ClientPlayNetworkHandler.onEntity` 则用 `entity.getTrackedPosition().withDelta(...)` 解释后续相对位移。

因此，当新服务端跟踪器的出生基准与客户端旧基准不同时，后续相对位移会被加到旧基准上。保留客户端实体和当前插值轨迹是合理的，但丢掉出生包里的跟踪基准会留下位置偏差，往返交接可能累积或改变偏差。这是源码与原版字节码共同确认的状态同步缺口。

普通非骑乘连续交接通过 `ClientContinuousEntityHandoff.consumeTargetSpawn` 调用 `onSpawnPacket`，然后恢复画面位置和历史姿态，已经处理了出生基准。骑乘交接没有对应处理。

运行时观察支持显示偏移：矿车 12344 的客户端采样达到 X `-524280.5701497396`，超过此前服务器采样的右端回弹位置约 2.92 格。两段采样窗口不同，不能把这个差值当作同一 tick 的精确客户端/服务端误差，也不能仅据此断言服务端碰撞失效。

## 已实施修正与待用户复测

### 用户复测后的第二轮修正

用户反馈：补上客户端基准后，矿车跨界仍会短暂停顿，其他骑乘实体正常。第一轮修正没有完整处理矿车停顿。

进一步核对原版字节码发现：`DefaultMinecartController.tick` 在客户端仅推进 `PositionInterpolator`；插值耗尽时不会按速度继续行驶。`EntityTrackerEntry` 重建会把 `trackingTick` 归零，并用实体当前坐标初始化 `trackedPos`。跨界因此改变了位移包的发送相位，也跳过了上次发包至交接期间的未发送位移；仅保留实体速度和客户端对象不能保持矿车插值连续。这条路径与客户端负责移动的其他骑乘实体不同。

第二轮修改在默认矿车连续跨界前保存旧跟踪器的发送计数、最后发送位置、速度及角度等同步状态，在目标跟踪器构造完成、发出出生包之前恢复；位置基准按 cell 位移平移。目标出生包和后续相对移动包由此使用一致的旧发送基准，下一次位移更新沿用原发送相位并包含未发送的位移。失败回滚注册源跟踪器时也恢复该状态。保留第一轮客户端基准同步，不使用速度外推来掩盖缺包。

新增 `MinecartTrackerHandoff` 和 `EntityTrackerEntryMixin`，通过现有原地迁移路径传递状态，只针对 `DefaultMinecartController` 的连续跨界。普通传送与实验性矿车不启用此逻辑。按用户要求未编译、未测试，实际是否消除顿挫仍待用户复测。

已在 `largerworld$ignoreDuplicateSpawn` 中补上基准同步：匹配目标出生包并保留实体时，调用 `existing.updateTrackedPosition(packet.getX(), packet.getY(), packet.getZ())`。包的 getter 已映射到客户端坐标系；该重载只更新相对位移跟踪基准，保持当前位置、速度、乘客和正在执行的插值轨迹。新增的 `BASELINE` 诊断记录受原有日志开关控制。对重复目标出生包和旧来源移动包的顺序也需要复测。

复测应覆盖两个方向、至少数十次往返、骑乘与空车、实际速度与画面速度，并同步记录新 tracker 出生位置、客户端 trackedPosition 和相对位移。复测后才能确认是否同时解决用户感受到的减速和穿模。遵照用户要求，修改后不运行编译、自动化测试或游戏验证，由用户测试；当前运行中的游戏未热更新此修复。

## 诊断工具事故

12:55:24 的服务端及 12:55:25 的客户端崩溃由本次诊断引入：临时 `VelocityDropAgent` 在 `Entity.setVelocity` 中引用的辅助类被 Fabric Knot 类加载隔离阻止，触发 `NoClassDefFoundError`。这不是矿车问题的根因证据。该代理只修改旧进程内存，未修改游戏 jar、模组类文件或启动参数，重启后失效；未向新进程加载它。

新进程使用不引用额外游戏侧辅助类的现有日志开关，以及通过游戏线程执行的只读反射采样。日志开关限时 180 秒，服务器采样约 60 秒，客户端采样约 20 秒。

## 本地材料

- `build/velocity-diagnostic/minecart-evidence.log`：新进程的过滤证据。
- `build/velocity-diagnostic/session-before-crash.log`：旧进程日志快照。
- `build/velocity-diagnostic/client-handler-bytecode.txt`：原版网络处理字节码。
- `build/velocity-diagnostic/minecart-bytecode.txt`：原版矿车控制器字节码。
- `run/crash-reports/crash-2026-09-12_12.55.24-server.txt` 和对应客户端报告：诊断代理导致的崩溃。

`build` 下材料是临时产物，清理构建目录会删除它们。
