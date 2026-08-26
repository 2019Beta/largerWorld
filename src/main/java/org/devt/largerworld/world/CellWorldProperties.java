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
    private int clearWeatherTime;
    private int rainTime;
    private int thunderTime;
    private boolean raining;
    private boolean thundering;
    private int traderDelay;
    private int traderChance;
    private UUID traderId;
    private boolean initialized = true;
    private Optional<WorldBorder.Properties> worldBorder;

    CellWorldProperties(ServerWorldProperties delegate) {
        this.delegate = delegate;
        clearWeatherTime = delegate.getClearWeatherTime();
        rainTime = delegate.getRainTime();
        thunderTime = delegate.getThunderTime();
        raining = delegate.isRaining();
        thundering = delegate.isThundering();
        traderDelay = delegate.getWanderingTraderSpawnDelay();
        traderChance = delegate.getWanderingTraderSpawnChance();
        traderId = delegate.getWanderingTraderId();
        worldBorder = delegate.getWorldBorder();
    }

    @Override public String getLevelName() { return delegate.getLevelName(); }
    @Override public WorldProperties.SpawnPoint getSpawnPoint() { return delegate.getSpawnPoint(); }
    @Override public void setSpawnPoint(WorldProperties.SpawnPoint spawnPoint) { delegate.setSpawnPoint(spawnPoint); }
    @Override public long getTime() { return delegate.getTime(); }
    @Override public long getTimeOfDay() { return delegate.getTimeOfDay(); }
    @Override public void setTime(long time) { delegate.setTime(time); }
    @Override public void setTimeOfDay(long time) { delegate.setTimeOfDay(time); }
    @Override public boolean isThundering() { return thundering; }
    @Override public void setThundering(boolean value) { thundering = value; }
    @Override public boolean isRaining() { return raining; }
    @Override public void setRaining(boolean value) { raining = value; }
    @Override public int getRainTime() { return rainTime; }
    @Override public void setRainTime(int value) { rainTime = value; }
    @Override public int getThunderTime() { return thunderTime; }
    @Override public void setThunderTime(int value) { thunderTime = value; }
    @Override public int getClearWeatherTime() { return clearWeatherTime; }
    @Override public void setClearWeatherTime(int value) { clearWeatherTime = value; }
    @Override public boolean isHardcore() { return delegate.isHardcore(); }
    @Override public Difficulty getDifficulty() { return delegate.getDifficulty(); }
    @Override public boolean isDifficultyLocked() { return delegate.isDifficultyLocked(); }
    @Override public int getWanderingTraderSpawnDelay() { return traderDelay; }
    @Override public void setWanderingTraderSpawnDelay(int value) { traderDelay = value; }
    @Override public int getWanderingTraderSpawnChance() { return traderChance; }
    @Override public void setWanderingTraderSpawnChance(int value) { traderChance = value; }
    @Override public UUID getWanderingTraderId() { return traderId; }
    @Override public void setWanderingTraderId(UUID value) { traderId = value; }
    @Override public GameMode getGameMode() { return delegate.getGameMode(); }
    @Override public void setGameMode(GameMode gameMode) { delegate.setGameMode(gameMode); }
    @Override public Optional<WorldBorder.Properties> getWorldBorder() { return worldBorder; }
    @Override public void setWorldBorder(Optional<WorldBorder.Properties> value) { worldBorder = value; }
    @Override public boolean isInitialized() { return initialized; }
    @Override public void setInitialized(boolean value) { initialized = value; }
    @Override public boolean areCommandsAllowed() { return delegate.areCommandsAllowed(); }
    @Override public Timer<MinecraftServer> getScheduledEvents() { return delegate.getScheduledEvents(); }
    @Override public GameRules getGameRules() { return delegate.getGameRules(); }
    @Override public void populateCrashReport(CrashReportSection section, HeightLimitView world) {
        delegate.populateCrashReport(section, world);
    }
}
