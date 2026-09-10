# Larger World

**English** | [简体中文](README.md)

Fabric 1.21.11 implementation of partitioned coordinates (floating origin) with continuous chunk loading across cell boundaries.

Rather than widening Minecraft's `BlockPos` or `ChunkPos`, the mod stores horizontal positions as:

```text
global = cell * 1,048,576 + local
local in [-524,288, 524,288)
```

The vanilla engine only ever sees local coordinates. `cellX`/`cellZ` are arbitrary-precision integers in player data and on the wire; legacy 64-bit saves still read fine. Global coordinates are composed with `BigDecimal` when displayed, so the high bits never get dropped in a `double` conversion.

## What works now

- Each nonzero cell gets its own `ServerWorld` on demand, with separate `region`/`entities`/`poi` storage and its own chunk cache.
- New cells use `largerworld_cells/<hash-prefix>/<hash>` below the save root. Directory length is independent of coordinate length. A checked `cell-key.txt` retains the full identity; existing legacy directories remain in use. Conflicting or unreadable identities fail closed.
- Weather, the initialization flag, wandering-trader timers, and the world border are persisted in the cell's own dimension directory, so unloading or restarting no longer copies state over from the base world.
- All cells share the main world's seed. Generation samples global noise and biomes at `localChunk + cell × 65536`, which keeps terrain continuous across cell borders.
- Players can sit in different cells at the same time. Players, vehicles with all passengers, ordinary entities, and projectiles migrate between worlds.
- Chunk requests are merged under one global dimension/cell/local-chunk key. The server projects player or vehicle velocity three seconds ahead and preloads the predicted target entry through vanilla's ticket/future pipeline; crossing the border switches to the target world.
- The mod does not rebuild or wrap vanilla's `ChunkStatus` generation graph. Cell-level code only coalesces identical accessible-chunk requests; dependency expansion, concurrency, ticket propagation, and thread ownership remain under `ServerChunkManager`.
- Repeated pending chunk saves coalesce to the latest snapshot, NBT conversion stays lazy until consumption, failed Region writes retry, and unload/Cell-close barriers wait for queued writes to complete. Retry behavior is controlled by `largerworld.chunkIo.maxWriteAttempts` and `largerworld.chunkIo.retryDelayMillis`.
- The network origin stays fixed during ordinary crossings. Near the client coordinate limit, an ordered message migrates cached chunks, lighting and entity positions while retaining `ClientWorld` and the player; origin changes no longer send a dimension respawn packet.
- Block updates, entity movement, sounds, particles, explosions, world events, and break animations from neighboring cells map into the same client view by source cell.
- Movement, vehicle movement, mining, block use, and entity interaction route back to the correct cell; containers opened across a boundary still check distance against the target cell.
- Cells survive logout, re-entry, and death, and stay synced to the current client.
- The HUD shows XYZ, the cell, and local XZ in the top-left corner. Values exceeding 18 digits use engineering notation; the command still prints full coordinates. With F3 open the display moves to the bottom.
- `/largerworld coords` prints exact global coordinates.
- Admins can use `/largerworld teleport <globalX> <y> <globalZ>` past the `long` block-count limit. The former 512-byte coordinate cap is removed: decoding checks against the bytes actually received before allocating. Frame limits and vanilla dimension-identifier length limits remain; unsupported destinations fail before world creation, and excessive exponents are rejected before expansion.

## Known limitations

Origin changes process an in-memory snapshot and refresh render caches on the client thread, which can cause a frame-time spike. Retained chunks do not require another server download, and the client world is not replaced. Very distant teleports discard old caches outside the new window and receive destination chunks normally. Coordinate-bearing input carries its sending origin, including deferred sign updates, so pre-rebase input is not misinterpreted in the new frame. Client and server must run matching versions.

Block, block-entity, and lighting changes in neighboring cells reuse vanilla single-block, section-delta, and light-update packets, so a small change no longer rebuilds the entire client chunk. Sign editors opened across a cell boundary retain a remote editing session; independent position-based editor packets for command blocks, structure blocks, jigsaws, and test blocks are routed to their owning cell as well.

Generation changes only affect new chunks. Biome and density coordinates now fold at the same block boundary. Distant noise, shifted noise, cave noise, base 3D noise and End island noise choose 65,536-block patches using full global coordinates and smoothly blend bounded native samples. The transition starts at `2^30` blocks and finishes at `2^31 - 2^20`, before the signed-int discontinuity. Neighboring cells select identical patches for the same global point; wrapped coordinates no longer reproduce the central End biome remotely.

New distant terrain differs from earlier versions. Existing region files are not rewritten, so boundaries between generation versions may still have seams. Arbitrary-precision coordinates do not imply unlimited RAM or disk space. Y limits are unchanged.

By default the server keeps at most 256 dynamic cells active and creates at most 16 per tick. JVM properties `largerworld.maxActiveCells` and `largerworld.maxCellCreationsPerTick` change these limits.

Per-entity handoff, spawn, and tracker diagnostics are off by default, so normal play doesn't write a flood of log lines. Turn them on with `-Dlargerworld.entityInfoLogging=true` when debugging.

Prefetching runs every 5 ticks, looks 60 ticks ahead, and prepares the 2-chunk radius around the target entry. Adjustable via `largerworld.prefetchIntervalTicks`, `largerworld.prefetchHorizonTicks`, `largerworld.prefetchRadiusChunks`, and `largerworld.regionPrefetchTtlSeconds`; prefetch tickets and unconsumed Region reads expire on their own.

Dynamic Cells close in stages: vanilla holders, tickets, lighting, and generation must first become safe; a non-blocking save then drains Region writes asynchronously; and the server thread performs a final activity check before closing. A player, neighboring view, or interaction that returns during the drain revives the same `ServerWorld` instead of opening the Region files twice.

The first time a cell created by an older version (no `largerworld_cell_properties.dat`) loads, weather and wandering-trader state are initialized once from the base world and persisted independently from then on. Vanilla `world_border.dat` is active and rendered again; if you edited borders while the old global suppression was installed, check each cell's border settings after upgrading.

See [docs/MULTIPLAYER_ARCHITECTURE.md](docs/MULTIPLAYER_ARCHITECTURE.md) for full packet mapping, neighbor shadow tracking, and inbound interaction routing.

## Testing

```powershell
gradle build
```

`check` includes coordinate boundaries, negative floor semantics, large coordinates, packet lengths, compact storage and noise wrap checks without a test framework. The latest revision received syntax and static inspection only, as requested; there is no completed build or runtime validation result. In-game seam behavior and client cache migration still require runtime validation.
