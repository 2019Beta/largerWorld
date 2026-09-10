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

- 每个非零 cell 按需创建自己的 `ServerWorld`，region/entities/poi 存储和区块缓存都是独立的。
- 新 cell 使用存档根目录下的 `largerworld_cells/<哈希前缀>/<哈希>`，路径长度不随坐标位数增长。`cell-key.txt` 保存完整身份并在打开时校验；已存在的旧维度目录继续原地使用，身份冲突或无法读取时拒绝打开，避免误用其他区域的数据。
- 天气、初始化标记、流浪商人计时和 world border 都保存在各自 cell 的维度目录里；卸载或重启不会再从主世界复制当时的状态。
- 所有 cell 共享主世界的种子。生成时在 `localChunk + cell × 65536` 处采样全局噪声与生物群系，跨 cell 边界的地形因此保持连续。
- 不同玩家可以同时待在不同的 cell。玩家、满载乘客的载具、普通实体和弹射物都可以在世界之间迁移。
- 区块请求使用“维度 + cell + 本地区块”全局键合并并发请求。服务器按玩家或载具的速度预测未来 3 秒的越界方向，通过原版 Ticket/Future 管线预加载目标入口；跨过边界即切换到目标世界。
- 网络坐标原点在通常的跨 cell 移动中保持不变。接近客户端坐标范围时通过专用消息迁移内存区块、光照和实体坐标，保留 `ClientWorld` 和玩家对象，不再为原点切换发送维度重生（respawn）包。
- 相邻 cell 的方块更新、实体移动、声音、粒子、爆炸、世界事件和破坏动画，都按来源 cell 映射进同一个客户端视图。
- 移动、载具移动、挖掘、使用方块和交互实体都会路由回正确的 cell；跨边界打开的容器仍按目标 cell 检查距离。
- cell 在退出登录、重新进入和死亡后依然保留，并保持与当前客户端同步。
- HUD 左上角显示 XYZ、cell 编号和局部 XZ 坐标；超过 18 位的数值使用工程计数法，完整坐标仍可通过命令查看。打开 F3 调试界面时，显示会移到下方，避免遮住原版调试信息。
- `/largerworld coords` 输出精确的全局坐标。
- 管理员可以使用 `/largerworld teleport <globalX> <y> <globalZ>` 传送到超出 `long` 方块计数上限的位置。已移除单个坐标 512 字节的限制，解码先检查长度是否落在实际收到的数据内；仍受网络帧和原版维度标识字符串长度限制。超出标识长度的目标在创建 cell 前报错，极端指数输入也会在展开前被拒绝。

## 已知限制

原点切换会在客户端线程处理内存快照并刷新渲染缓存，可能产生短暂帧耗时，但不需要重新从服务器下载已经保留的区块，也不替换客户端世界。超远传送时，无法映射到新窗口的旧缓存会被清理，随后正常接收目标区块。移动和交互包携带发送时的原点，切换前发出的包不会误按新原点解释；异步告示牌更新也保留该上下文。客户端和服务器必须安装匹配版本。

相邻 cell 的方块、方块实体与光照变更复用原版的单方块、section delta 和 light update 数据包，小改动不用重建整个客户端区块。跨 cell 打开的告示牌编辑器会保留远端编辑会话；命令方块、结构方块、拼图方块和测试方块等独立的坐标型编辑数据包，也会路由到所属 cell。

生成坐标偏移只影响新生成的区块。生物群系与密度坐标现在按同一个方块边界回绕。主要噪声、位移噪声、洞穴噪声、三维基础噪声及末地岛屿噪声在远处使用完整全局坐标选择 65,536 格采样片区，并平滑混合有界的原版噪声；从 `2^30` 格开始过渡，在 `2^31 - 2^20` 格完成，避开有符号整数回绕断点。相邻 cell 对同一全局位置使用同一采样片区，末地远端也不再因坐标回绕重新出现中心生物群系。

远处新地形会与旧版本不同。已有区域文件不会被重写，新旧生成算法交界处仍可能出现版本接缝。任意精度坐标不等于无限内存或磁盘；Y 高度保持原有范围。

服务器默认最多同时保留 256 个动态 cell，每 tick 最多创建 16 个。可用 JVM 属性 `largerworld.maxActiveCells` 和 `largerworld.maxCellCreationsPerTick` 调整。

逐实体交接、出生与跟踪的诊断日志默认关闭，避免持续运行时产生大量日志写入。排查相关问题时，可临时加 JVM 属性 `-Dlargerworld.entityInfoLogging=true` 开启。

预取默认每 5 tick 跑一次，预测未来 60 tick，预加载入口周围 2 区块半径。可通过 `largerworld.prefetchIntervalTicks`、`largerworld.prefetchHorizonTicks`、`largerworld.prefetchRadiusChunks` 和 `largerworld.regionPrefetchTtlSeconds` 调整；预取 Ticket 与未消费的 Region 读取会自动过期。

模组不重建或包裹原版 `ChunkStatus` 生成图。Cell 层只合并相同的 accessible-chunk 请求，生成依赖、并发、票据传播与线程归属全部由原版 `ServerChunkManager` 管理。

区块保存会合并同一区块尚未开始的重复写入，NBT 序列化推迟到实际消费时；被更新版本取代的快照不会产生完整 NBT。Region 写入默认最多尝试 3 次、间隔 25 毫秒，卸载区块实体和关闭 cell 会等待相应的写屏障。用 `largerworld.chunkIo.maxWriteAttempts` 与 `largerworld.chunkIo.retryDelayMillis` 调整。

动态 cell 采用分阶段关闭：先等待原版 holder、ticket、光照与生成队列进入安全状态，再提交非阻塞保存并异步排空 Region 写入，最后在服务器线程复核后关闭。写入排空期间若玩家、相邻视图或交互重新使用该 cell，会复活同一个 `ServerWorld`，不会重复打开 Region 文件。

首次加载由旧版本创建、尚无 `largerworld_cell_properties.dat` 的 cell 时，会用主世界当前的天气和流浪商人状态初始化一次，之后独立持久化。原版 `world_border.dat` 现在重新生效并正常渲染；如果旧存档曾在边界被全局屏蔽期间修改过边界，升级后请检查各 cell 的边界配置。

完整的包映射、相邻 cell 阴影跟踪和入站交互路由见 [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md)。

## 测试

```powershell
gradle build
```

`check` 包含坐标边界、负数向下取整、大坐标组合、编码长度、短路径存储和噪声回绕检查，不依赖任何测试框架。最新改动按要求仅做语法与静态检查，尚无完整构建和运行验证结果；运行时接缝和客户端缓存迁移仍需实际游戏验证。
