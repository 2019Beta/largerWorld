package org.devt.largerworld.mixin;

import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes workstation validity checks use continuous coordinates across a cell seam. */
@Mixin(ScreenHandler.class)
public abstract class ScreenHandlerMixin {
    @Inject(
            method = "canUse(Lnet/minecraft/screen/ScreenHandlerContext;"
                    + "Lnet/minecraft/entity/player/PlayerEntity;"
                    + "Lnet/minecraft/block/Block;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private static void largerworld$canUseAcrossCell(
            ScreenHandlerContext context,
            PlayerEntity player,
            Block expectedBlock,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(player.getEntityWorld() instanceof ServerWorld playerWorld)) {
            return;
        }

        ContextTarget target = context.get(ContextTarget::new, null);
        if (target == null
                || !(target.world() instanceof ServerWorld targetWorld)
                || targetWorld == playerWorld
                || !CellWorldKey.baseWorld(targetWorld.getRegistryKey())
                .equals(CellWorldKey.baseWorld(playerWorld.getRegistryKey()))) {
            return;
        }

        // Keep vanilla's source-validity condition. Only its local-coordinate
        // distance check needs replacing for a neighboring backing world.
        if (!targetWorld.getBlockState(target.pos()).isOf(expectedBlock)) {
            cir.setReturnValue(false);
            return;
        }

        CellPos targetCell = CellWorldKey.cell(targetWorld.getRegistryKey());
        CellPos playerCell = CellWorldKey.cell(playerWorld.getRegistryKey());
        try {
            long deltaX = targetCell.deltaXExact(playerCell);
            long deltaZ = targetCell.deltaZExact(playerCell);
            long projectedX = Math.addExact(
                    target.pos().getX(), Math.multiplyExact(deltaX, VirtualPosition.CELL_SIZE));
            long projectedZ = Math.addExact(
                    target.pos().getZ(), Math.multiplyExact(deltaZ, VirtualPosition.CELL_SIZE));
            BlockPos projected = new BlockPos(
                    Math.toIntExact(projectedX), target.pos().getY(), Math.toIntExact(projectedZ));

            // ScreenHandler uses the same 4-block interaction margin in vanilla.
            cir.setReturnValue(player.canInteractWithBlockAt(projected, 4.0));
        } catch (ArithmeticException outsideRepresentableWindow) {
            cir.setReturnValue(false);
        }
    }

    private record ContextTarget(World world, BlockPos pos) {
    }
}
