# Larger World

[English](README_EN.md) | **简体中文**

Fabric 1.21.11 下的分区坐标（浮动原点，floating origin）实现，支持跨 cell 边界的连续区块加载。

不是加宽 Minecraft 的 `BlockPos` 或 `ChunkPos`，而是把水平坐标存储为：

```text
global = cell * 1,048,576 + local
local in [-524,288, 524,288)
```

原版引擎永远只看到 local 坐标。`cellX`/`cellZ` 以任意精度整数保存在玩家数据中并同步给客户端；旧版的 64 位存档格式仍可读取。显示全局坐标时用 `BigDecimal` 组合，避免高位在 `double` 转换中丢失。

## 目前已实现的功能

- 每个非零 cell 按需创建独立的 `ServerWorld`，拥有各自的 `region`/`entities`/`poi` 存储和独立的区块缓存。
- 每个 cell 的天气、初始化标记、流浪商人计时及 world border 都保存在该 cell 自己的维度目录中；卸载或重启不会重新复制主世界当时的状态。
- 所有 cell 共享主世界的种子。生成时在 `localChunk + cell × 65536` 处采样全局噪声与生物群系，因此跨 cell 边界的地形保持连续。
- 不同玩家可以同时位于不同的 cell。玩家、满载乘客的载具、普通实体和弹射物都可以在世界之间迁移。
- 区块任务使用“维度 + cell + 本地区块 + ChunkStatus”全局键合并并发请求。服务器按玩家或载具速度预测未来 3 秒的越界方向，提前异步读取 Region NBT 并预加载目标入口；跨过边界即切换到目标世界。
- 网络坐标原点在一条连接的生命周期内固定不变。当前 cell 与相邻 cell 的区块、光照、生物群系和实体都在同一个客户端视图中渲染；跨越边界不会发送维度重生（respawn）包，客户端也无需重建任何内容。
- 相邻 cell 的方块更新、实体移动、声音、粒子、爆炸、世界事件和破坏动画都按来源 cell 映射到同一个客户端视图。
- 移动、载具移动、挖掘、使用方块和交互实体都会路由回正确的 cell；跨边界打开的容器仍会按目标 cell 检查距离。
- cell 在退出登录、重新进入和死亡后依然保留，并保持与当前客户端同步。
- HUD 左上角显示真实 XYZ、cell 编号和局部 XZ 坐标。打开 F3 调试界面时，显示会移到下方，避免遮住原版调试信息。
- `/largerworld coords` 输出精确的全局坐标。
- 管理员可以使用 `/largerworld teleport <globalX> <y> <globalZ>` 传送到超出 `long` 方块计数上限的位置。逻辑坐标没有固定整数位宽；网络会限制单个坐标的编码长度以防止恶意内存分配。

## 已知限制

网络坐标原点在连接期间通常会保持不变，因此客户端世界不会在每次跨边界时整体平移。当长距离传送或反复跨越逼近原版客户端的最大安全范围（约 30,000,000 格）时，服务器会把原点重置到目标 cell，并强制客户端重新加载一次世界。所有相对坐标都先以任意精度整数求 cell 差，再转换为客户端局部数值。

相邻 cell 中的方块、方块实体与光照变更复用原版的单方块、section delta 和 light update 数据包传播，避免小改动重建整个客户端区块。跨 cell 打开的告示牌编辑器会保留远端编辑会话；命令方块、结构方块、拼图方块和测试方块等独立的坐标型编辑数据包也会路由到其所属 cell。

生成坐标偏移只影响新生成的区块。原版基础地貌仍需通过 32 位 API，但在远坐标处会叠加一个直接散列任意精度全局格点的连续密度场；雕刻、装饰和区域随机也让完整高位参与，因此不再存在固定的 `2^32`/`2^36` 完整世界周期。该密度场在 cell 接缝两侧采样同一个全局函数。升级前已生成的区域文件不会被重写；请在新世界或尚未生成的 cell 中测试边界情况。

服务器默认最多同时保留 256 个动态 cell，每 tick 最多创建 16 个。可用 JVM 属性 `largerworld.maxActiveCells` 和 `largerworld.maxCellCreationsPerTick` 调整。

逐实体交接、出生与跟踪诊断日志默认关闭，避免持续运行时产生大量日志写入。排查相关问题时，可添加 JVM 属性 `-Dlargerworld.entityInfoLogging=true` 临时开启。

预测预取默认每 5 tick 运行一次、预测未来 60 tick，并准备入口附近 2 区块半径。可通过 JVM 属性 `largerworld.prefetchIntervalTicks`、`largerworld.prefetchHorizonTicks`、`largerworld.prefetchRadiusChunks` 和 `largerworld.regionPrefetchTtlSeconds` 调整；预取 Ticket 与未消费的 Region 读取会自动过期。

区块生成现在会展开跨 cell 的 `ChunkStatus` 邻区依赖，并在真正的原版生成入口统一执行优先级、背压和全局写集合协调。默认并发生成节点数等于可用处理器数，预测队列上限为 512；可通过 `largerworld.chunkTasks.maxActive` 和 `largerworld.chunkTasks.maxQueuedPrefetch` 调整。真实视距请求优先，且能把尚未开始的预测任务原地提升为交互任务。

区块保存会合并同一区块尚未开始的重复写入，并延迟 NBT 序列化到实际消费时；被更新版本取代的快照不会产生完整 NBT。Region 写入默认最多尝试 3 次、间隔 25 毫秒，区块实体卸载和 cell 关闭会等待相应写屏障。可通过 `largerworld.chunkIo.maxWriteAttempts` 与 `largerworld.chunkIo.retryDelayMillis` 调整。

首次加载由旧版本创建、尚无 `largerworld_cell_properties.dat` 的 cell 时，会以主世界当前天气和流浪商人状态初始化一次，之后独立持久化。原版 `world_border.dat` 现在重新生效并正常渲染；若旧存档曾在边界被全局屏蔽期间修改过边界，请在升级后检查各 cell 的边界配置。

完整的包映射、相邻 cell 阴影跟踪和入站交互路由记录在 [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md)。

## 测试

```powershell
gradle build
```

`check` 会运行坐标边界、负数向下取整语义、大坐标组合与溢出测试，不依赖任何测试框架。
