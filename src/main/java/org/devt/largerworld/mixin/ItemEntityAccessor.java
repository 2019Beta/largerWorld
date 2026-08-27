package org.devt.largerworld.mixin;

import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Invoker("canMerge")
    boolean largerworld$canMerge();

    @Invoker("tryMerge")
    void largerworld$tryMerge(ItemEntity other);
}
