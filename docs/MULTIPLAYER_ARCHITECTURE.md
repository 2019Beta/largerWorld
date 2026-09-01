# Multiplayer coordinate architecture

## Invariants

1. Minecraft block/entity physics only receives local X/Z in `[-524288, 524288)`.
2. A server-side chunk is identified by `(baseDimension, cellX, cellZ, localChunkX, localChunkZ)`.
3. Each cell has its own `ServerWorld`, chunk cache, RegionFile, entity and POI storage.
4. A client sees ordinary integer chunk coordinates relative to a normally stable per-connection origin cell.
5. The owning server remains authoritative for all global/cell conversions.

## Storage and runtime

```text
base dimension + CellPos
            |
            v
largerworld:cell/<namespace>/<dimension>/<cellX>/<cellZ>
            |
            v
dimensions/largerworld/cell/.../{region,entities,poi}
```

Cell worlds are created on the server thread when first requested. Their registry
keys are reversible, allowing player NBT that references a cell world to load it
again after a restart. Empty and unwatched cell worlds save and unload after 60
seconds. Shadow-view tickets prevent a visible neighbor from being evicted.

Weather timers, rain/thunder flags, the initialized flag, and wandering-trader
state live in `data/largerworld_cell_properties.dat` below each cell dimension.
They copy the base-world values only when that file is first created. Every
setter marks the persistent state dirty, so the normal cell save-before-close
transaction commits the latest values. World borders use vanilla's separate
per-dimension `data/world_border.dat`; border collision, damage, packets, and
client rendering remain enabled. Packet centers are translated from the owning
cell into the connection coordinate frame instead of disabling borders globally.
Dynamic borders have their own change listener, and every seamless player
handoff sends a complete target-cell border plus rain/thunder snapshot because
the vanilla client normally receives those only on join or respawn.

Every cell uses the original save seed. No cell-specific seed derivation is
performed. Non-zero cell worlds keep their `ChunkPos`, structures, block entities,
RegionFiles and network identity cell-local. Only horizontal noise interpolation,
aquifer sampling, surface-height queries and biome quart coordinates are evaluated
at `local + cell * 1048576`. Carver seeds, structure placement checks,
structure-layout seeds and decoration seeds use the corresponding global chunk or
block coordinates, so newly generated terrain continues across a seam while all
persisted positions remain cell-local.

Vanilla exposes noise, biome and several structure sampling coordinates as
32-bit integers, so the vanilla base field is still folded at those API
boundaries. Larger World additionally samples a continuous density overlay from
the full arbitrary-precision global lattice coordinate. Carver, decoration and
region random tokens hash the complete coordinate as well. The combined
generator has no fixed low-32-bit world period, while the overlay evaluates the
same function on both sides of every cell seam. Persistent/global player
positions are never folded.

This only affects chunks generated after the arbitrary-precision layer is installed. Existing
cell RegionFiles retain their old terrain and must not be mixed with regenerated
border chunks when checking continuity.

Players, mobs, items, projectiles and complete vehicle/passenger graphs migrate as
one unit. Portals that temporarily place a player in a canonical base dimension are
reconciled back to the same cell on the following server tick.

Read-only block, fluid and block-entity queries that cross a seam are projected
into an already loaded neighboring cell. This is used by collision rays and the
bounded chunk view used for mob pathfinding. Entity distance, visibility and the
standard active-target goal likewise use stitched coordinates for immediately
adjacent cells, allowing an existing pursuit to continue across a seam and a mob
near the seam to discover a target on the other side. These behavior bridges do
not synchronously create cell worlds.

## Seamless client view mapping

One cell contains 65536 chunks per axis. The server records the player's cell at
login as `originCell`. For a chunk from `sourceCell`, the client coordinate is:

```text
clientChunk = localChunk + (sourceCell - originCell) * 65536
```

The inverse uses floor division, including for negative coordinates. Keeping the
origin fixed means a cell crossing does not move existing client chunks or
entities. It also leaves the client world and renderer intact.

Every vanilla play packet is carried inside `largerworld:cell_packet` with its
`sourceCell` and the connection's `originCell`. This follows the source-context
packet-redirection pattern used by Immersive Portals, specialized for a single
stitched cell plane. Client mixins translate coordinate-bearing accessors only
while the enclosed vanilla packet is applied.

## Shadow tracking and updates

For each player, the server computes the portion of the vanilla view circle that
falls outside the current cell. Those neighbor chunks receive loading and
simulation tickets, then their full chunk/light data and entity tracker listeners
are attached to the same connection. Ownership is handed between shadow tracking
and vanilla tracking when the player crosses a seam, without unloading the client
chunk during that handoff.

The translated packet groups include:

- full chunk, light, biome and unload packets;
- block and section delta packets;
- block entities, sounds, particles, explosions and world events;
- entity spawn, movement and tracking packets;
- spawn, look-at and world-border coordinates;
- inbound digging, use-block, entity interaction, player movement and vehicle movement.

Sounds and world events sent through `PlayerManager.sendToAround`, plus particles
and block-breaking progress emitted directly by `ServerWorld`, are copied to
nearby shadow viewers using global cell distance. Shadow viewers are also joined
to `ChunkHolder`'s update recipients, so they receive the same single-block,
section-delta, block-entity, and light packets as vanilla chunk watchers without
replacing the client chunk.

## Cell-aware chunk task engine

Shadow chunk preparation is submitted through a backend-neutral task front end.
Its identity is `(baseDimension, cellX, cellZ, localChunkX, localChunkZ, target)`;
a local `ChunkPos` alone is never used as a global scheduler or cache key. While
an accessible-chunk task is in flight, requests from multiple players share one
future and one backing chunk-pipeline submission. Completion and failure both
remove the entry, so a later request can retry and no completed future is kept as
an unbounded cache. Per-server submission/coalescing/completion/failure counters
are available from `CellChunkTaskEngine.statistics`.

Worldgen targets from `EMPTY` through `FULL` are explicit graph nodes. A status
node depends on `ChunkStatus.getPrevious()` for the same global chunk. The engine
also expands every ring in `ChunkGenerationStep.directDependencies`; ring chunks
are normalized through `VirtualChunkPos`, so a FEATURES dependency can continue
in another backing Cell instead of stopping at the local `ServerWorld` edge.
Calls that touch chunk holders or create a backing Cell are marshalled back to
the server thread, while unrelated cell/chunk futures progress concurrently.

The real `ServerChunkLoadingManager.generate` entry point is admission-controlled,
including generation started by ordinary vanilla tickets. Interactive view work
preempts prediction work, active generation is bounded, and excess speculative
work is rejected without rejecting interactive loads. A speculative node that is
later required by a real view is promoted in place. Defaults are one active node
per available processor and 512 queued speculative nodes, configurable with
`largerworld.chunkTasks.maxActive` and
`largerworld.chunkTasks.maxQueuedPrefetch`.

Every generation step uses its vanilla `blockStateWriteRadius` to construct a
global chunk write set. The set includes base dimension, arbitrary-precision Cell
and canonical local chunk coordinates. Admission reserves all members atomically;
overlapping nodes wait, while disjoint nodes continue concurrently. These are
asynchronous leases rather than thread-owned Java locks, so ownership can safely
span a generation future and be released on either success or failure.

Before an accessible ticket is submitted, `CellRegionIoPrefetch` starts
`VersionedChunkStorage.getNbt` on Minecraft's `StorageIoWorker`. A narrow optional
mixin in the real loader consumes that same future, so prediction overlaps Region
IO with ticket propagation without parsing or mutating a chunk off-thread. Reads
that are never consumed expire after 15 seconds; completed NBT is not an unbounded
cache. The hook uses `require = 0`, so a backend that replaces the vanilla load
method can fall back to its own IO implementation instead of failing mixin startup.

The write side is coordinated by `CellChunkIoQueue`. One active and one replaceable
pending snapshot are retained per backing manager/local chunk. Repeated saves while
IO is active collapse into the newest pending snapshot, while every superseded
caller waits for that newest write. `SerializedChunk.toNbt` is represented by a
lazy future, so a pending snapshot replaced before StorageIoWorker consumes it is
never serialized. A failed Region write retries three times by default, reusing
the same serialized NBT, and only the final failure is returned to vanilla's
normal save-failure handler.

Entity teardown during chunk unload waits asynchronously for that chunk's write
queue to drain. Flush saves and dynamic Cell closure also wait for the manager
barrier and run `completeAll(true)` once more, covering writes that were coalesced
after vanilla placed its first StorageIoWorker flush marker. Retry count and delay
are configurable with `largerworld.chunkIo.maxWriteAttempts` and
`largerworld.chunkIo.retryDelayMillis`. Queue, coalescing, retry, completion,
failure and deferred-serialization counters are exposed by
`CellChunkIoQueue.statistics()`.

Every five ticks the prediction planner projects the root vehicle's velocity up
to 60 ticks ahead. If that trajectory or the ordinary view margin can reach a
seam, it creates the predicted target cell and requests a 5x5 entry area with
15-second expiring tickets. Diagonal trajectories normalize the entry square
across all participating cells. These defaults can be changed with
`largerworld.prefetchIntervalTicks`, `largerworld.prefetchHorizonTicks`,
`largerworld.prefetchRadiusChunks`, and `largerworld.regionPrefetchTtlSeconds`.
Far from a seam, the view tracker still takes a constant-time empty fast path and
allocates no virtual chunk positions.

## Seam crossing and inbound routing

When a player crosses the canonical local boundary, the server moves the player
to the target `ServerWorld` without sending `PlayerRespawnS2CPacket`. The normal
position-correction packet is tagged with the target cell, so it resolves to the
same continuous client coordinate. Server movement packets are converted from the
stable client origin back into the player's current local cell.

Digging, block use and entity interaction first resolve their client coordinate or
tracked entity against the visible neighbor cell. If the target is remote, the
player and interaction manager are temporarily projected into that `ServerWorld`
while vanilla handles the operation. This preserves vanilla reach, permission,
sequence and screen-distance checks instead of duplicating them.

## Current limits

The connection origin stays stable during ordinary seam crossings. Before a
distant teleport or roughly 29 cell crossings would exceed vanilla's
approximately 30-million-block safety range, the server rebases the origin to the
target cell and uses one vanilla client-world reload to discard state mapped with
the old origin. A sign opened across a seam retains its target world and editor
lifetime until the filtered update is applied. Other vanilla position-bearing
editor actions (command, structure, jigsaw, and test blocks) are translated and
executed in their owning cell.

For loaded neighboring cells, block writes, neighbor-update chains, synchronized
block events and scheduled block/fluid ticks are projected to the owning backing
`ServerWorld`. This lets redstone, pistons and fluid flow continue through a seam
without storing non-canonical positions. Item merge searches and the player's
collision/pickup pass also query overlapping neighboring cells. These bridges do
not synchronously create an unloaded cell; as with vanilla behavior, both sides
must be loaded and ticking for propagation to continue.

Cell coordinates are arbitrary-precision integers. Legacy long-valued player
attachments remain readable. Network mappings subtract source and origin cells
exactly before converting a bounded relative result to an int or double. The
packet representation limits each integer to 512 bytes as a denial-of-service
guard, without imposing a machine integer width on storage arithmetic.

Dynamic worlds are limited to 256 active cells and 16 new cells per server tick
by default. Empty cells are saved and closed before removal from the server world
map; failed saves remain loaded for a later retry. Worldgen and tick-scheduler
registrations use weak ownership and are explicitly removed after a successful
close.
