package org.devt.largerworld.coordinate;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the coordinate checks in the same transformed class loader as Minecraft. */
public final class FabricCoordinateTestLauncher {
    private static final String TEST_MAIN =
            "org.devt.largerworld.coordinate.VirtualCoordinatesTest";

    private FabricCoordinateTestLauncher() {
    }

    public static void main(String[] args) throws Exception {
        loadCommonLaunchProperties(Path.of(System.getProperty(
                "largerworld.coordinateTest.launchConfig",
                ".gradle/loom-cache/launch.cfg")));
        appendTestClassesToClassPathGroups();

        Knot knot = new Knot(EnvType.SERVER);
        ClassLoader targetLoader = knot.init(args);
        Class<?> testClass = Class.forName(TEST_MAIN, true, targetLoader);
        try {
            testClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        }
    }

    private static void loadCommonLaunchProperties(Path config) throws Exception {
        if (!Files.isRegularFile(config)) {
            throw new IllegalStateException("Missing Loom launch config: " + config);
        }
        boolean commonProperties = false;
        for (String line : Files.readAllLines(config)) {
            if (!line.startsWith("\t")) {
                commonProperties = "commonProperties".equals(line);
                continue;
            }
            if (!commonProperties) {
                continue;
            }
            String property = line.substring(1);
            int separator = property.indexOf('=');
            if (separator > 0) {
                System.setProperty(
                        property.substring(0, separator), property.substring(separator + 1));
            }
        }
    }

    private static void appendTestClassesToClassPathGroups() {
        String testClasses = System.getProperty("largerworld.coordinateTest.classes");
        if (testClasses == null || testClasses.isBlank()) {
            return;
        }
        String groups = System.getProperty("fabric.classPathGroups", "");
        String separator = System.getProperty("path.separator");
        System.setProperty("fabric.classPathGroups", groups + separator + testClasses);
    }
}
