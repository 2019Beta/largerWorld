package org.devt.largerworld.mixin.client;

import net.minecraft.entity.data.DataTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DataTracker.class)
public interface DataTrackerAccessor {
    @Accessor("entries")
    DataTracker.Entry<?>[] largerworld$getEntries();
}
