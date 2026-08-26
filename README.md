# Larger World

Fabric 1.21.11 的分区坐标 / floating-origin 与跨 cell 无缝加载实现。

它不把 Minecraft 的 `BlockPos`、`ChunkPos` 等类型直接扩大，而是把水平位置表示为：

```text
global = cell * 1,048,576 + local
local in [-524,288, 524,288)
```

原版引擎只接触局部坐标；`cellX/cellZ` 以 64 位整数持久化在玩家数据中，并同步给客户端。显示全局坐标时使用 `BigDecimal` 合成，避免先转成 `double` 丢失高位。

## 当前可运行范围

- 每个非零 cell 按需创建独立 `ServerWorld`，具有独立的 `region/entities/poi` 存储与区块缓存。
- 所有 cell 复用主存档种子；世界生成阶段按 `localChunk + cell × 65536` 采样全局噪声和生物群系，使新生成地形跨 cell 连续。
- 不同玩家可以同时处于不同 cell；玩家、载具及其全部乘客、普通实体和投射物会跨世界迁移。
- 玩家接近边界时预加载目标 cell 的入口区块，越界后切换到目标世界。
- 客户端连接期间固定一个网络坐标原点，同时显示当前 cell 与相邻 cell 的区块、光照、生物群系和实体；跨界不会发送维度重生包，也不会重建客户端世界。
- 相邻 cell 的方块更新、实体移动、声音、粒子、爆炸、世界事件与破坏动画会按来源 cell 映射到同一个客户端视图。
- 移动、载具移动、挖掘、方块使用和实体交互会反向路由到正确的 cell；边界另一侧打开的容器仍按目标 cell 做距离校验。
- cell 在退出、重进和死亡后保持，并同步给当前客户端。
- 普通游戏界面左上角常驻显示实际 XYZ、cell 和局部 XZ；打开 F3 时移至底部，避免覆盖原版调试信息。
- `/largerworld coords` 查询精确全局坐标。
- 管理员可用 `/largerworld teleport <globalX> <y> <globalZ>` 跳转到超出 `long` 格数但仍在 `long cell × 2^20` 范围内的位置。

## 重要限制

网络坐标原点在一次连接内保持不变，以免跨界时平移整个客户端世界。因此连续穿越约 28 个以上 cell 后会逐渐接近原版约 3000 万格的客户端安全范围；重新连接会把网络原点放到登录时所在 cell。持久化坐标本身仍使用 `long cell × 2^20`，不受这个单次连接显示窗口限制。

相邻 cell 的方块变化当前通过完整区块刷新保证一致性，正确性优先但带宽开销高于原版增量包；后续可将其细化为按 section 的 delta/light 转发。告示牌等异步编辑界面在玩家尚未真正跨入目标 cell 时仍属于待完善边角场景。

世界生成坐标偏移只作用于新生成区块。升级前已生成的 cell RegionFile 不会自动重写，验证边界时应使用新世界或未生成过的 cell。

完整的包映射、邻区 shadow tracking 和入站交互路由见 [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md)。

## 验证

```powershell
gradle build
```

`check` 会自动运行不依赖测试框架的坐标边界、负数 floor 语义、大坐标精确合成和溢出测试。
