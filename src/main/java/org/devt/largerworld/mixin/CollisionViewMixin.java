package org.devt.largerworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.CollisionView;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Evaluates placement collision in the cell that owns the placement position. */
@Mixin(CollisionView.class)
public interface CollisionViewMixin {
    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void largerworld$canPlaceAcrossCell(
            BlockState state,
            BlockPos pos,
            ShapeContext context,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved ->
                    cir.setReturnValue(resolved.world().canPlace(state, resolved.pos(), context)));
        }
    }
}
