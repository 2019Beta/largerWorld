package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Mirrors vanilla player collision callbacks into loaded neighboring cells. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityBoundaryMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void largerworld$collideWithNeighborCellEntities(CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (player.isSpectator()
                || !(player.getEntityWorld() instanceof ServerWorld source)) {
            return;
        }

        Box searchBox = player.getBoundingBox().expand(1.0, 0.5, 1.0);
        List<Entity> experienceOrbs = new ArrayList<>();
        for (CellBoundaryAccess.ProjectedWorld projected
                : CellBoundaryAccess.loadedWorldsOverlapping(source, searchBox)) {
            for (Entity entity : projected.world().getOtherEntities(
                    null, projected.localBox(), candidate -> !candidate.isRemoved())) {
                if (entity.getType() == EntityType.EXPERIENCE_ORB) {
                    experienceOrbs.add(entity);
                } else {
                    entity.onPlayerCollision(player);
                }
            }
        }
        if (!experienceOrbs.isEmpty()) {
            experienceOrbs.get(player.getRandom().nextInt(experienceOrbs.size()))
                    .onPlayerCollision(player);
        }
    }
}
