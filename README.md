# Larger World

Fabric 1.21.11 的分区坐标 / floating-origin 原型。

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
- cell 在退出、重进和死亡后保持，并同步给当前客户端。
- 普通游戏界面左上角常驻显示实际 XYZ、cell 和局部 XZ；打开 F3 时移至底部，避免覆盖原版调试信息。
- `/largerworld coords` 查询精确全局坐标。
- 管理员可用 `/largerworld teleport <globalX> <y> <globalZ>` 跳转到超出 `long` 格数但仍在 `long cell × 2^20` 范围内的位置。

## 重要限制

cell 已经拥有真正独立的存档，不再与其他 cell 共用相同局部区块。但客户端无缝拼接层仍未完成：目前跨界会执行一次 `ServerWorld` 切换，客户端会重建区块缓存，站在边界时也还不能同时接收两个 cell 的实时区块更新。

世界生成坐标偏移只作用于新生成区块。升级前已生成的 cell RegionFile 不会自动重写，验证边界时应使用新世界或未生成过的 cell。

完整的包映射、邻区 shadow tracking 和入站交互路由见 [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md)。仅重写完整区块包坐标并不足够，因为后续方块、实体、光照和客户端交互也必须使用完全相同的映射。

## 验证

```powershell
gradle build
```

`check` 会自动运行不依赖测试框架的坐标边界、负数 floor 语义、大坐标精确合成和溢出测试。
