package org.devt.largerworld.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps container distance checks continuous when a double inventory spans a cell seam. */
@Mixin(Inventory.class)
public interface InventoryMixin {
    @Inject(method = "canPlayerUse(Lnet/minecraft/block/entity/BlockEntity;Lnet/minecraft/entity/player/PlayerEntity;F)Z",
            at = @At("HEAD"), cancellable = true)
    private static void largerworld$canPlayerUseAcrossCell(
            BlockEntity blockEntity,
            PlayerEntity player,
            float range,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(blockEntity.getWorld() instanceof ServerWorld containerWorld)
                || !(player.getEntityWorld() instanceof ServerWorld playerWorld)
                || containerWorld == playerWorld
                || !CellWorldKey.baseWorld(containerWorld.getRegistryKey())
                .equals(CellWorldKey.baseWorld(playerWorld.getRegistryKey()))) {
            return;
        }

        CellPos containerCell = CellWorldKey.cell(containerWorld.getRegistryKey());
        CellPos playerCell = CellWorldKey.cell(playerWorld.getRegistryKey());
        long deltaX;
        long deltaZ;
        try {
            deltaX = Math.subtractExact(containerCell.x(), playerCell.x());
            deltaZ = Math.subtractExact(containerCell.z(), playerCell.z());
        } catch (ArithmeticException exception) {
            cir.setReturnValue(false);
            return;
        }
        if (deltaX < -1 || deltaX > 1 || deltaZ < -1 || deltaZ > 1) {
            cir.setReturnValue(false);
            return;
        }
        if (containerWorld.getBlockEntity(blockEntity.getPos()) != blockEntity) {
            cir.setReturnValue(false);
            return;
        }

        BlockPos pos = blockEntity.getPos();
        long projectedX = (long) pos.getX() + deltaX * VirtualPosition.CELL_SIZE;
        long projectedZ = (long) pos.getZ() + deltaZ * VirtualPosition.CELL_SIZE;
        if (projectedX < Integer.MIN_VALUE || projectedX > Integer.MAX_VALUE
                || projectedZ < Integer.MIN_VALUE || projectedZ > Integer.MAX_VALUE) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(player.canInteractWithBlockAt(
                new BlockPos((int) projectedX, pos.getY(), (int) projectedZ), range));
    }
}
