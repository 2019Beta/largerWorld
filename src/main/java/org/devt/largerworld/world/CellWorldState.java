package org.devt.largerworld.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.util.Uuids;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.level.ServerWorldProperties;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Per-cell level properties stored in that cell world's own data directory. */
final class CellWorldState extends PersistentState {
    static final Codec<CellWorldState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("clear_weather_time", 0)
                    .forGetter(CellWorldState::clearWeatherTime),
            Codec.INT.optionalFieldOf("rain_time", 0)
                    .forGetter(CellWorldState::rainTime),
            Codec.INT.optionalFieldOf("thunder_time", 0)
                    .forGetter(CellWorldState::thunderTime),
            Codec.BOOL.optionalFieldOf("raining", false)
                    .forGetter(CellWorldState::raining),
            Codec.BOOL.optionalFieldOf("thundering", false)
                    .forGetter(CellWorldState::thundering),
            Codec.INT.optionalFieldOf("wandering_trader_delay", 0)
                    .forGetter(CellWorldState::traderDelay),
            Codec.INT.optionalFieldOf("wandering_trader_chance", 0)
                    .forGetter(CellWorldState::traderChance),
            Uuids.STRING_CODEC.optionalFieldOf("wandering_trader_id")
                    .forGetter(state -> Optional.ofNullable(state.traderId())),
            Codec.BOOL.optionalFieldOf("initialized", true)
                    .forGetter(CellWorldState::initialized)
    ).apply(instance, (clearWeatherTime, rainTime, thunderTime, raining, thundering,
                       traderDelay, traderChance, traderId, initialized) -> new CellWorldState(
            clearWeatherTime,
            rainTime,
            thunderTime,
            raining,
            thundering,
            traderDelay,
            traderChance,
            traderId.orElse(null),
            initialized)));

    static final PersistentStateType<CellWorldState> TYPE = new PersistentStateType<>(
            "largerworld_cell_properties",
            CellWorldState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private int clearWeatherTime;
    private int rainTime;
    private int thunderTime;
    private boolean raining;
    private boolean thundering;
    private int traderDelay;
    private int traderChance;
    private UUID traderId;
    private boolean initialized;

    private CellWorldState() {
        initialized = true;
    }

    private CellWorldState(
            int clearWeatherTime,
            int rainTime,
            int thunderTime,
            boolean raining,
            boolean thundering,
            int traderDelay,
            int traderChance,
            UUID traderId,
            boolean initialized) {
        this.clearWeatherTime = clearWeatherTime;
        this.rainTime = rainTime;
        this.thunderTime = thunderTime;
        this.raining = raining;
        this.thundering = thundering;
        this.traderDelay = traderDelay;
        this.traderChance = traderChance;
        this.traderId = traderId;
        this.initialized = initialized;
    }

    static CellWorldState copyOf(ServerWorldProperties source) {
        return new CellWorldState(
                source.getClearWeatherTime(),
                source.getRainTime(),
                source.getThunderTime(),
                source.isRaining(),
                source.isThundering(),
                source.getWanderingTraderSpawnDelay(),
                source.getWanderingTraderSpawnChance(),
                source.getWanderingTraderId(),
                source.isInitialized());
    }

    int clearWeatherTime() { return clearWeatherTime; }
    int rainTime() { return rainTime; }
    int thunderTime() { return thunderTime; }
    boolean raining() { return raining; }
    boolean thundering() { return thundering; }
    int traderDelay() { return traderDelay; }
    int traderChance() { return traderChance; }
    UUID traderId() { return traderId; }
    boolean initialized() { return initialized; }

    void setClearWeatherTime(int value) {
        if (clearWeatherTime != value) {
            clearWeatherTime = value;
            markDirty();
        }
    }

    void setRainTime(int value) {
        if (rainTime != value) {
            rainTime = value;
            markDirty();
        }
    }

    void setThunderTime(int value) {
        if (thunderTime != value) {
            thunderTime = value;
            markDirty();
        }
    }

    void setRaining(boolean value) {
        if (raining != value) {
            raining = value;
            markDirty();
        }
    }

    void setThundering(boolean value) {
        if (thundering != value) {
            thundering = value;
            markDirty();
        }
    }

    void setTraderDelay(int value) {
        if (traderDelay != value) {
            traderDelay = value;
            markDirty();
        }
    }

    void setTraderChance(int value) {
        if (traderChance != value) {
            traderChance = value;
            markDirty();
        }
    }

    void setTraderId(UUID value) {
        if (!Objects.equals(traderId, value)) {
            traderId = value;
            markDirty();
        }
    }

    void setInitialized(boolean value) {
        if (initialized != value) {
            initialized = value;
            markDirty();
        }
    }
}
