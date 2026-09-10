package org.devt.largerworld.mixin.client;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntity.class)
public interface BlockEntityOriginAccessor {
    @Mutable @Accessor("pos")
    void largerworld$setOriginPosition(BlockPos pos);
}
