# Multiplayer coordinate architecture

## Invariants

1. Minecraft block/entity physics only receives local X/Z in `[-524288, 524288)`.
2. A server-side chunk is identified by `(baseDimension, cellX, cellZ, localChunkX, localChunkZ)`.
3. Each cell has its own `ServerWorld`, chunk cache, RegionFile, entity and POI storage.
4. A client sees ordinary integer chunk coordinates relative to its current cell.
5. The owning server remains authoritative for all global/cell conversions.

## Implemented storage/runtime layer

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
again after a restart. Empty cell worlds save and unload after 60 seconds.

Every cell uses the original save seed. No cell-specific seed derivation is
performed. Non-zero cell worlds keep their `ChunkPos`, structures, block entities,
RegionFiles and network identity cell-local. Only horizontal noise interpolation,
aquifer sampling, surface-height queries and biome quart coordinates are evaluated
at `local + cell * 1048576`. This narrow sampling offset prevents global positions
from leaking into chunk storage while keeping base terrain continuous. Carvers
translate their temporary aquifer query position at the call boundary, then still
write caves and post-processing markers with cell-local block positions. Carver
seeds, structure placement checks, structure-layout seeds and decoration seeds use
the corresponding global chunk or block coordinates. Vanilla's generation halo
can therefore reproduce the same translated cave or structure start on both sides
of a cell seam while all persisted positions remain cell-local.

This only affects chunks generated after the offset layer is installed. Existing
cell RegionFiles retain their old terrain and must not be mixed with regenerated
border chunks when checking continuity.

Players, mobs, items, projectiles and complete vehicle/passenger graphs migrate as
one unit. Portals that temporarily place a player in a canonical base dimension are
reconciled back to the same cell on the following server tick.

## Client view mapping

One cell contains 65536 chunks per axis. For a chunk from `sourceCell`, the client
coordinate relative to `playerCell` is:

```text
clientChunk = localChunk + (sourceCell - playerCell) * 65536
```

The inverse uses floor division, including for negative coordinates. This mapping
is implemented by `VirtualChunkPos` and is the contract for packet translation.

## Remaining seamless-network layer

Independent multiplayer storage is not by itself visual stitching. To render and
interact across a boundary without a dimension reload, the following packet groups
must all use `VirtualChunkPos` consistently:

- full chunk, light, biome and unload packets;
- block and section delta packets;
- block entities, sounds, particles, explosions and world events;
- entity spawn/movement/tracking packets;
- inbound digging, use-block, movement and command coordinates.

Neighbor cell chunks also need per-player shadow tracking because vanilla only
tracks chunks in the player's current `ServerWorld`. Sending only an initial chunk
packet is insufficient: later block/entity changes would silently desynchronize.

Until that layer is installed, crossing a cell uses a real `ServerWorld` transfer.
This provides independent saves and concurrent multiplayer cells, but the client
will rebuild its chunk cache at the seam.
