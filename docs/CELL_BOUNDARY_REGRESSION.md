# Cell boundary regression scenarios

These are manual runtime checks, not results of the static review. The current
patch was checked without Gradle, a build, or a running game.

Use a disposable world. A cell has local X/Z in `[-524288, 524288)`.
Repeat seam checks on both axes, in both directions, and at a corner.

1. **Simulation across the seam.** With a non-spectator player just inside one
   cell, keep a repeater clock, flowing water and an ordinary moving entity in
   the adjacent cell within simulation distance. They should continue updating
   without a second player in that cell. Move out of range and back; also change
   simulation distance and spectator mode. Keep view distance larger than
   simulation distance to check that visibility alone does not activate distant
   chunks. With two nearby players, leaving/disconnecting one must not release
   the other player's simulation coverage.
2. **Dimension isolation.** Put players near the same cell/chunk coordinates in
   the Overworld and Nether, with neighboring chunks visible. Change blocks and
   lighting on one side. Only observers in that base dimension should see the
   updates.
3. **View replacement.** Teleport between base dimensions near the same seam
   coordinates, then respawn near a seam. Neighbor chunks and entities must be
   re-sent from the destination world. Walking across a seam within the same base
   dimension must retain the existing seamless handoff behavior.
4. **Projectile collision.** Place a target immediately beyond the seam and fire
   an arrow fast enough to cross both the seam and target in one tick. Check
   damage and the hit position. Repeat with a nearer target before the seam to
   verify closest-hit selection, and with a block obstructing the shot. Also
   check ordinary same-cell shots and shots from a vehicle.
5. **MSPT during crossing.** Compare the same route, view/simulation distances
   and entity count before and after the patch. Measure warm (already generated)
   crossings separately from first-time terrain generation, in both directions
   and at a corner. Include standing near the seam for at least 15 seconds,
   repeated vehicle crossings, a stream of entities, and two nearby players.
   Record the crossing ticks' maximum/p95 MSPT, not just average FPS. Prediction
   should refresh tickets without calling `addChunkLoadingTicket` for every
   predicted chunk; already accessible shadow chunks and entity-ticking landing
   chunks should also skip its immediate graph update. Cold destinations must
   still load, entities must keep ticking, and failed shadow loads must retry
   within the shared 16-start per-player tick budget. Source inspection confirms
   these paths; no runtime MSPT improvement has been measured yet.

The automated geometry/range checks are wired into the existing coordinate-test
entry point for a later authorized test run; they were not executed for this patch.
