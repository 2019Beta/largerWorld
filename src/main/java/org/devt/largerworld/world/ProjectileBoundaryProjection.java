package org.devt.largerworld.world;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Geometry helpers for evaluating a neighboring-cell entity without moving it. */
public final class ProjectileBoundaryProjection {
    private ProjectileBoundaryProjection() {
    }

    /**
     * Moves a local bounding box by the displacement between its entity's native
     * position and that position projected into the observer world's coordinates.
     */
    public static Box translateBox(Box localBox, Vec3d localPosition, Vec3d projectedPosition) {
        return localBox.offset(
                projectedPosition.x - localPosition.x,
                projectedPosition.y - localPosition.y,
                projectedPosition.z - localPosition.z);
    }
}
