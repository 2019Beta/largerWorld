# Larger World

Fabric 1.21.11 的分区坐标 / floating-origin 原型。

它不把 Minecraft 的 `BlockPos`、`ChunkPos` 等类型直接扩大，而是把水平位置表示为：

```text
global = cell * 1,048,576 + local
local in [-524,288, 524,288)
```

原版引擎只接触局部坐标；`cellX/cellZ` 以 64 位整数持久化在玩家数据中，并同步给客户端。显示全局坐标时使用 `BigDecimal` 合成，避免先转成 `double` 丢失高位。

## 当前可运行范围（MVP）

- 玩家越过局部 cell 边界后自动回绕到另一侧，同时增减 cell。
- cell 在退出、重进和死亡后保持，并同步给当前客户端。
- F3 界面底部显示全局坐标、cell 和局部坐标。
- `/largerworld coords` 查询精确全局坐标。
- 管理员可用 `/largerworld teleport <globalX> <y> <globalZ>` 跳转到超出 `long` 格数但仍在 `long cell × 2^20` 范围内的位置。

## 重要限制

这是坐标层原型，不是完整的无限世界实现。当前所有 cell 仍映射到同一个 Minecraft backing world，因此不同 cell 的相同局部坐标会访问同一批区块。跨界时只迁移玩家，骑乘状态会被解除，实体、投射物、地图、结构引用和计划刻尚未跨 cell 迁移。

不能简单在 RegionFile 路径前添加 cell：区块缓存和网络协议仍只以 `ChunkPos(int,int)` 为键，多名玩家处于不同 cell 时会发生别名。下一阶段必须二选一：

1. 每个活动 cell 使用独立 `ServerWorld`，在边界执行世界切换；或
2. 将服务端区块键扩展为 `(cell, localChunk)`，发送给客户端前再映射成玩家相对区块坐标。

前者适合先完成单 cell 可游玩的版本，后者才可能做到跨 cell 视距内真正无缝拼接。

## 验证

```powershell
gradle build
```

`check` 会自动运行不依赖测试框架的坐标边界、负数 floor 语义、大坐标精确合成和溢出测试。
