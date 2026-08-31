package org.devt.largerworld.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.rule.GameRules;
import net.minecraft.world.timer.Timer;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.util.crash.CrashReportSection;

import java.util.Optional;
import java.util.UUID;

/**
 * Delegates global rules/time to the save while keeping weather and wandering
 * trader counters local to one cell. Without this wrapper, N active overworld
 * cells would decrement the same weather timers N times per server tick.
 */
final class CellWorldProperties implements ServerWorldProperties {
    private final ServerWorldProperties delegate;
    private CellWorldState state;
    private Optional<WorldBorder.Properties> initialWorldBorder;
    private WorldBorder boundWorldBorder;

    CellWorldProperties(ServerWorldProperties delegate) {
        this.delegate = delegate;
        state = CellWorldState.copyOf(delegate);
        initialWorldBorder = delegate.getWorldBorder();
    }

    void attach(PersistentStateManager manager) {
        CellWorldState saved = manager.get(CellWorldState.TYPE);
        if (saved == null) {
            manager.set(CellWorldState.TYPE, state);
        } else {
            state = saved;
        }
    }

    Optional<WorldBorder.Properties> initialWorldBorder() {
        return initialWorldBorder;
    }

    void bindWorldBorder(WorldBorder worldBorder) {
        boundWorldBorder = worldBorder;
        initialWorldBorder = Optional.of(new WorldBorder.Properties(worldBorder));
    }

    @Override public String getLevelName() { return delegate.getLevelName(); }
    @Override public WorldProperties.SpawnPoint getSpawnPoint() { return delegate.getSpawnPoint(); }
    @Override public void setSpawnPoint(WorldProperties.SpawnPoint spawnPoint) { delegate.setSpawnPoint(spawnPoint); }
    @Override public long getTime() { return delegate.getTime(); }
    @Override public long getTimeOfDay() { return delegate.getTimeOfDay(); }
    @Override public void setTime(long time) { delegate.setTime(time); }
    @Override public void setTimeOfDay(long time) { delegate.setTimeOfDay(time); }
    @Override public boolean isThundering() { return state.thundering(); }
    @Override public void setThundering(boolean value) { state.setThundering(value); }
    @Override public boolean isRaining() { return state.raining(); }
    @Override public void setRaining(boolean value) { state.setRaining(value); }
    @Override public int getRainTime() { return state.rainTime(); }
    @Override public void setRainTime(int value) { state.setRainTime(value); }
    @Override public int getThunderTime() { return state.thunderTime(); }
    @Override public void setThunderTime(int value) { state.setThunderTime(value); }
    @Override public int getClearWeatherTime() { return state.clearWeatherTime(); }
    @Override public void setClearWeatherTime(int value) { state.setClearWeatherTime(value); }
    @Override public boolean isHardcore() { return delegate.isHardcore(); }
    @Override public Difficulty getDifficulty() { return delegate.getDifficulty(); }
    @Override public boolean isDifficultyLocked() { return delegate.isDifficultyLocked(); }
    @Override public int getWanderingTraderSpawnDelay() { return state.traderDelay(); }
    @Override public void setWanderingTraderSpawnDelay(int value) { state.setTraderDelay(value); }
    @Override public int getWanderingTraderSpawnChance() { return state.traderChance(); }
    @Override public void setWanderingTraderSpawnChance(int value) { state.setTraderChance(value); }
    @Override public UUID getWanderingTraderId() { return state.traderId(); }
    @Override public void setWanderingTraderId(UUID value) { state.setTraderId(value); }
    @Override public GameMode getGameMode() { return delegate.getGameMode(); }
    @Override public void setGameMode(GameMode gameMode) { delegate.setGameMode(gameMode); }
    @Override public Optional<WorldBorder.Properties> getWorldBorder() {
        return boundWorldBorder == null
                ? initialWorldBorder
                : Optional.of(new WorldBorder.Properties(boundWorldBorder));
    }
    @Override public void setWorldBorder(Optional<WorldBorder.Properties> value) {
        initialWorldBorder = value;
        if (boundWorldBorder != null) {
            applyBorderProperties(boundWorldBorder, value.orElse(WorldBorder.Properties.DEFAULT));
        }
    }
    @Override public boolean isInitialized() { return state.initialized(); }
    @Override public void setInitialized(boolean value) { state.setInitialized(value); }
    @Override public boolean areCommandsAllowed() { return delegate.areCommandsAllowed(); }
    @Override public Timer<MinecraftServer> getScheduledEvents() { return delegate.getScheduledEvents(); }
    @Override public GameRules getGameRules() { return delegate.getGameRules(); }
    @Override public void populateCrashReport(CrashReportSection section, HeightLimitView world) {
        delegate.populateCrashReport(section, world);
    }

    private void applyBorderProperties(WorldBorder border, WorldBorder.Properties properties) {
        border.setCenter(properties.centerX(), properties.centerZ());
        border.setDamagePerBlock(properties.damagePerBlock());
        border.setSafeZone(properties.safeZone());
        border.setWarningBlocks(properties.warningBlocks());
        border.setWarningTime(properties.warningTime());
        if (properties.lerpTime() > 0L) {
            border.interpolateSize(
                    properties.size(), properties.lerpTarget(), properties.lerpTime(), getTime());
        } else {
            border.setSize(properties.size());
        }
    }
}
