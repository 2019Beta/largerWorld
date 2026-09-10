package org.devt.largerworld.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Short, stable disk addresses; the full key remains in a checked manifest. */
public final class CellStorage {
    private static final String MANIFEST = "cell-key.txt";

    private CellStorage() {
    }

    /** Returns null for ordinary dimensions, which keep vanilla's directory. */
    public static synchronized Path directory(RegistryKey<World> key, Path saveRoot) {
        if (CellWorldKey.parse(key).isEmpty()) {
            return null;
        }
        String identity = key.getValue().toString();
        Path root = saveRoot.toAbsolutePath().normalize();
        Path compact = compactDirectory(root, identity);
        try {
            // Preserve previously generated cells in place. Do not attempt to
            // create or move the potentially enormous legacy coordinate path.
            Path legacy = root.resolve("dimensions").resolve(key.getValue().getNamespace())
                    .resolve(key.getValue().getPath()).normalize();
            if (!legacy.startsWith(root)) {
                throw new IOException("Cell directory escapes the world save");
            }
            boolean hasLegacy = legacyDirectoryExists(legacy, key.getValue().getPath());
            boolean hasCompact = Files.exists(compact, LinkOption.NOFOLLOW_LINKS);
            if (hasLegacy) {
                if (hasCompact) {
                    throw new IOException("Both legacy and compact storage exist for " + identity);
                }
                return legacy;
            }
            if (hasCompact) {
                verify(compact, identity);
            } else {
                Files.createDirectories(compact);
                // A collision or an interrupted initialization must never
                // silently attach an unrelated region directory to this cell.
                Files.writeString(compact.resolve(MANIFEST), identity, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            return compact;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot open coordinate cell storage", exception);
        }
    }

    private static boolean legacyDirectoryExists(Path legacy, String identifierPath) throws IOException {
        // Such components could never have been created by the legacy layout.
        // Avoid even querying them: Windows may reject the path before IO starts.
        for (String component : identifierPath.split("/")) {
            if (component.length() > 255) {
                return false;
            }
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    legacy, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("Legacy cell storage is not a directory: " + legacy);
            }
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    public static Path compactDirectory(Path root, String identity) {
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        return root.resolve("largerworld_cells").resolve(digest.substring(0, 2))
                .resolve(digest.substring(2));
    }

    private static void verify(Path directory, String identity) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Cell storage must not be a symbolic link: " + directory);
        }
        Path manifest = directory.resolve(MANIFEST);
        long expectedBytes = identity.getBytes(StandardCharsets.UTF_8).length;
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest) != expectedBytes
                || !Files.readString(manifest, StandardCharsets.UTF_8).equals(identity)) {
            throw new IOException("Missing or mismatched cell identity in " + directory);
        }
    }
}
