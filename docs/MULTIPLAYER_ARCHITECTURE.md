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

Every cell uses the original save seed. No cell-specific seed derivation is
performed. Non-zero cell worlds keep their `ChunkPos`, structures, block entities,
RegionFiles and network identity cell-local. Only horizontal noise interpolation,
aquifer sampling, surface-height queries and biome quart coordinates are evaluated
at `local + cell * 1048576`. Carver seeds, structure placement checks,
structure-layout seeds and decoration seeds use the corresponding global chunk or
block coordinates, so newly generated terrain continues across a seam while all
persisted positions remain cell-local.

Vanilla exposes noise, biome and several structure sampling coordinates as
32-bit integers. Larger World folds virtual sampling coordinates modulo 2^32 at
those API boundaries. This makes generation deterministic for every long-valued
cell and keeps adjacent samples consecutive; persistent/global player positions
are not folded.

This only affects chunks generated after the offset layer is installed. Existing
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
nearby shadow viewers using global cell distance. Shadow block/light changes
currently resend the affected full chunk, favoring correctness over bandwidth.

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
the old origin. Async editors such as a sign opened across a seam before crossing
are not yet remotely routed.

For loaded neighboring cells, block writes, neighbor-update chains, synchronized
block events and scheduled block/fluid ticks are projected to the owning backing
`ServerWorld`. This lets redstone, pistons and fluid flow continue through a seam
without storing non-canonical positions. Item merge searches and the player's
collision/pickup pass also query overlapping neighboring cells. These bridges do
not synchronously create an unloaded cell; as with vanilla behavior, both sides
must be loaded and ticking for propagation to continue.
