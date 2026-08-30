# Larger World

**English** | [简体中文](README.md)

Fabric 1.21.11 implementation of partitioned coordinates (floating origin) with continuous chunk loading across cell boundaries.

Instead of widening Minecraft's `BlockPos` or `ChunkPos`, horizontal positions are stored as:

```text
global = cell * 1,048,576 + local
local in [-524,288, 524,288)
```

The vanilla engine only ever sees local coordinates. `cellX`/`cellZ` are arbitrary-precision integers in player data and on the wire; legacy 64-bit saves remain readable. Global coordinates are composed with `BigDecimal` when displayed, so high bits are never dropped through a `double` conversion.

## What works now

- Each nonzero cell gets its own `ServerWorld` on demand, with separate `region`/`entities`/`poi` storage and its own chunk cache.
- All cells share the main world's seed. Generation samples global noise and biomes at `localChunk + cell × 65536`, so terrain stays continuous across cell borders.
- Players can sit in different cells at the same time. Players, vehicles with all passengers, ordinary entities, and projectiles migrate between worlds.
- When a player nears a boundary, the target cell's entry chunks are preloaded; crossing over switches to the target world.
- The network coordinate origin is fixed for the lifetime of a connection. Chunks, lighting, biomes, and entities from the current and neighboring cells all render in one client view; crossing a border sends no dimension respawn packet and rebuilds nothing client-side.
- Block updates, entity movement, sounds, particles, explosions, world events, and break animations from neighboring cells map into the same client view by source cell.
- Movement, vehicle movement, mining, block use, and entity interaction route back to the correct cell; containers opened across a boundary still check distance against the target cell.
- Cells survive logout, re-entry, and death, and stay synced to the current client.
- The HUD shows real XYZ, the cell, and local XZ in the top-left corner. With F3 open the display moves to the bottom so it doesn't cover the vanilla debug overlay.
- `/largerworld coords` prints exact global coordinates.
- Admins can use `/largerworld teleport <globalX> <y> <globalZ>` past the `long` block-count limit. Logical coordinates have no fixed integer width; the wire format applies a per-coordinate byte limit to prevent malicious allocations.

## Known limitations

The network coordinate origin normally stays put within a connection, so the client world doesn't shift on every border crossing. When a long-range teleport or repeated crossing approaches the vanilla client safe range (~30,000,000 blocks), the server resets the origin to the target cell and forces one client world reload. Relative coordinates subtract arbitrary-precision cells before conversion to client-local numeric types.

Block, block-entity, and lighting changes in neighboring cells reuse vanilla single-block, section-delta, and light-update packets, so a small change no longer rebuilds the entire client chunk. Sign editors opened across a cell boundary retain a remote editing session; independent position-based editor packets for command blocks, structure blocks, jigsaws, and test blocks are routed to their owning cell as well.

Generation changes only affect newly generated chunks. Vanilla base terrain still passes through 32-bit APIs, but distant generation now adds a continuous density field hashed directly from arbitrary-precision global lattice coordinates. Carver, decoration, and region randomness also includes all high bits, removing the old fixed `2^32`/`2^36` complete-world period while keeping the density overlay continuous across cell seams. Existing region files are not rewritten.

By default the server keeps at most 256 dynamic cells active and creates at most 16 per tick. JVM properties `largerworld.maxActiveCells` and `largerworld.maxCellCreationsPerTick` configure these limits.

Full packet mapping, neighbor shadow tracking, and inbound interaction routing are documented in [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md).

## Testing

```powershell
gradle build
```

`check` runs the coordinate-boundary, negative floor semantics, large-coordinate composition, and overflow tests without depending on a test framework.
