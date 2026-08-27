# Larger World

Fabric 1.21.11 implementation of partitioned coordinates (floating origin) with continuous chunk loading across cell boundaries.

Instead of widening Minecraft's `BlockPos` or `ChunkPos`, horizontal positions are stored as:

```text
global = cell * 1,048,576 + local
local in [-524,288, 524,288)
```

The vanilla engine only ever sees local coordinates. `cellX`/`cellZ` are kept as 64-bit integers in player data and synced to the client. Global coordinates are composed with `BigDecimal` when displayed, so the high bits don't get dropped in a `double` conversion.

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
- Admins can use `/largerworld teleport <globalX> <y> <globalZ>` to reach positions past the `long` block-count limit, as long as they fit in `long cell × 2^20`.

## Known limitations

The network coordinate origin normally stays put within a connection, so the client world doesn't shift on every border crossing. When a long-range teleport or repeated crossing approaches the vanilla client safe range (~30,000,000 blocks), the server resets the origin to the target cell and forces one client world reload. Stored coordinates stay `long cell × 2^20`.

Block changes in a neighboring cell currently propagate as full chunk refreshes. That keeps things consistent but costs more bandwidth than vanilla incremental packets; forwarding per-section deltas and lighting is the planned refinement. Async edit interfaces like signs are still rough around the edges when a player hasn't stepped into the target cell yet.

Generation coordinate offsets only affect newly generated chunks. The vanilla worldgen API takes 32-bit horizontal coordinates, so sample coordinates beyond that range fold deterministically to their low 32 bits. Stored positions and displayed global coordinates keep full precision. Region files from cells generated before an upgrade are not rewritten; test boundary cases in a fresh world or in cells that haven't been generated yet.

Full packet mapping, neighbor shadow tracking, and inbound interaction routing are documented in [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md).

## Testing

```powershell
gradle build
```

`check` runs the coordinate-boundary, negative floor semantics, large-coordinate composition, and overflow tests without depending on a test framework.
