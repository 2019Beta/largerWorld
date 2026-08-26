package org.devt.largerworld.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;

import java.util.Arrays;
import java.util.Optional;

/** Reversible encoding of a base dimension and cell into a World registry key. */
public final class CellWorldKey {
    private static final String PREFIX = "cell/";

    private CellWorldKey() {
    }

    public static RegistryKey<World> forCell(RegistryKey<World> baseWorld, CellPos cell) {
        Parsed parsedBase = parse(baseWorld).orElse(null);
        RegistryKey<World> canonicalBase = parsedBase == null ? baseWorld : parsedBase.baseWorld();
        if (cell.equals(CellPos.ZERO)) {
            return canonicalBase;
        }

        Identifier base = canonicalBase.getValue();
        String path = PREFIX + base.getNamespace() + "/" + base.getPath()
                + "/" + cell.x() + "/" + cell.z();
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(Largerworld.MOD_ID, path));
    }

    public static RegistryKey<World> baseWorld(RegistryKey<World> world) {
        return parse(world).map(Parsed::baseWorld).orElse(world);
    }

    public static CellPos cell(RegistryKey<World> world) {
        return parse(world).map(Parsed::cell).orElse(CellPos.ZERO);
    }

    public static Optional<Parsed> parse(RegistryKey<World> world) {
        Identifier id = world.getValue();
        if (!id.getNamespace().equals(Largerworld.MOD_ID) || !id.getPath().startsWith(PREFIX)) {
            return Optional.empty();
        }

        String[] parts = id.getPath().split("/");
        if (parts.length < 5 || !parts[0].equals("cell")) {
            return Optional.empty();
        }

        try {
            long cellX = Long.parseLong(parts[parts.length - 2]);
            long cellZ = Long.parseLong(parts[parts.length - 1]);
            String basePath = String.join("/", Arrays.copyOfRange(parts, 2, parts.length - 2));
            if (basePath.isEmpty()) {
                return Optional.empty();
            }
            Identifier baseId = Identifier.of(parts[1], basePath);
            RegistryKey<World> baseKey = RegistryKey.of(RegistryKeys.WORLD, baseId);
            return Optional.of(new Parsed(baseKey, new CellPos(cellX, cellZ)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public record Parsed(RegistryKey<World> baseWorld, CellPos cell) {
    }
}
