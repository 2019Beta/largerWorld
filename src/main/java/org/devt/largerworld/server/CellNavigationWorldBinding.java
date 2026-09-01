package org.devt.largerworld.server;

import net.minecraft.world.World;

/** Rebinds a retained mob navigation instance after an in-place cell move. */
public interface CellNavigationWorldBinding {
    void largerworld$setNavigationWorld(World world);
}
