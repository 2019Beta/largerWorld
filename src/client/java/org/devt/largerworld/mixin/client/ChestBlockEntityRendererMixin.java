package org.devt.largerworld.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.render.block.entity.ChestBlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.ChestBlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the two rendered halves of a chest coherent while seam chunks arrive. */
@Mixin(ChestBlockEntityRenderer.class)
public abstract class ChestBlockEntityRendererMixin {
    @Inject(
            method = "updateRenderState(Lnet/minecraft/block/entity/BlockEntity;"
                    + "Lnet/minecraft/client/render/block/entity/state/ChestBlockEntityRenderState;F"
                    + "Lnet/minecraft/util/math/Vec3d;"
                    + "Lnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V",
            at = @At("RETURN"))
    private void largerworld$repairSeamChestType(
            BlockEntity blockEntity,
            ChestBlockEntityRenderState renderState,
            float tickProgress,
            Vec3d cameraPos,
            ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay,
            CallbackInfo ci) {
        World world = blockEntity.getWorld();
        BlockState state = blockEntity.getCachedState();
        if (world == null || !(state.getBlock() instanceof ChestBlock)) {
            return;
        }

        BlockPos pos = blockEntity.getPos();
        ChestType type = state.get(ChestBlock.CHEST_TYPE);
        if (type != ChestType.SINGLE) {
            BlockPos partnerPos = pos.offset(ChestBlock.getFacing(state));
            if (!largerworld$crossesCellSeam(pos, partnerPos)) {
                return;
            }
            BlockState partner = world.getBlockState(partnerPos);
            if (!largerworld$isMatchingHalf(state, type, partner,
                    ChestBlock.getFacing(state).getOpposite())) {
                // A full chunk for one side can overtake the incremental update
                // for the other. Rendering it as a single chest is preferable
                // to selecting a stretched or mirrored half texture.
                renderState.chestType = ChestType.SINGLE;
            }
            return;
        }

        // If this half still has the stale SINGLE state, the already received
        // partner contains enough information to recover its render-only type.
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos partnerPos = pos.offset(direction);
            if (!largerworld$crossesCellSeam(pos, partnerPos)) {
                continue;
            }
            BlockState partner = world.getBlockState(partnerPos);
            if (!(partner.getBlock() == state.getBlock())
                    || partner.get(ChestBlock.FACING) != state.get(ChestBlock.FACING)) {
                continue;
            }
            ChestType partnerType = partner.get(ChestBlock.CHEST_TYPE);
            if (partnerType != ChestType.SINGLE
                    && ChestBlock.getFacing(partner) == direction.getOpposite()) {
                renderState.chestType = partnerType.getOpposite();
                return;
            }
        }
    }

    @Unique
    private static boolean largerworld$isMatchingHalf(
            BlockState state, ChestType type, BlockState partner, Direction directionToCurrent) {
        return partner.getBlock() == state.getBlock()
                && partner.get(ChestBlock.FACING) == state.get(ChestBlock.FACING)
                && partner.get(ChestBlock.CHEST_TYPE) == type.getOpposite()
                && ChestBlock.getFacing(partner) == directionToCurrent;
    }

    @Unique
    private static boolean largerworld$crossesCellSeam(BlockPos first, BlockPos second) {
        return largerworld$cellCoordinate(first.getX()) != largerworld$cellCoordinate(second.getX())
                || largerworld$cellCoordinate(first.getZ()) != largerworld$cellCoordinate(second.getZ());
    }

    @Unique
    private static long largerworld$cellCoordinate(int coordinate) {
        return Math.floorDiv((long) coordinate + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
    }
}
