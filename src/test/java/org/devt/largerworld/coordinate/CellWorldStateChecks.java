package org.devt.largerworld.world;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.util.UUID;

/** Codec and dirty-state checks kept in the package of the internal state type. */
public final class CellWorldStateChecks {
    private CellWorldStateChecks() {
    }

    public static void run() {
        UUID traderId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        JsonObject encoded = new JsonObject();
        encoded.addProperty("clear_weather_time", 91);
        encoded.addProperty("rain_time", 1234);
        encoded.addProperty("thunder_time", 5678);
        encoded.addProperty("raining", true);
        encoded.addProperty("thundering", true);
        encoded.addProperty("wandering_trader_delay", 4321);
        encoded.addProperty("wandering_trader_chance", 17);
        encoded.addProperty("wandering_trader_id", traderId.toString());
        encoded.addProperty("initialized", false);

        CellWorldState state = CellWorldState.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow();
        check(state.clearWeatherTime() == 91, "clear-weather time round trip");
        check(state.rainTime() == 1234 && state.raining(), "rain state round trip");
        check(state.thunderTime() == 5678 && state.thundering(), "thunder state round trip");
        check(state.traderDelay() == 4321, "trader delay round trip");
        check(state.traderChance() == 17, "trader chance round trip");
        check(traderId.equals(state.traderId()), "trader UUID round trip");
        check(!state.initialized(), "initialized flag round trip");

        check(!state.isDirty(), "decoded state starts clean");
        state.setRainTime(1235);
        check(state.isDirty(), "mutated state is marked dirty");
        JsonObject saved = CellWorldState.CODEC
                .encodeStart(JsonOps.INSTANCE, state)
                .getOrThrow()
                .getAsJsonObject();
        check(saved.get("rain_time").getAsInt() == 1235, "mutated state encodes");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
