package org.devt.largerworld.world;

/** Pure policy used by the runtime and dependency-free limit tests. */
public record CellCreationLimits(int maxActiveCells, int maxCreationsPerTick) {
    public CellCreationLimits {
        if (maxActiveCells < 1 || maxCreationsPerTick < 1) {
            throw new IllegalArgumentException("Cell creation limits must be positive");
        }
    }

    public boolean allows(int activeCells, int alreadyCreatedThisTick) {
        return activeCells < maxActiveCells
                && alreadyCreatedThisTick < maxCreationsPerTick;
    }

    public String rejectionReason(int activeCells, int alreadyCreatedThisTick) {
        if (activeCells >= maxActiveCells) {
            return "Active cell limit reached: " + maxActiveCells;
        }
        if (alreadyCreatedThisTick >= maxCreationsPerTick) {
            return "Cell creation rate limit reached: " + maxCreationsPerTick + " per tick";
        }
        return "Cell creation allowed";
    }
}
