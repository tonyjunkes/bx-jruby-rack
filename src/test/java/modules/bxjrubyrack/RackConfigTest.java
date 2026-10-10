package modules.bxjrubyrack;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class RackConfigTest {
    @TempDir Path root;

    /**
     * Resolves a custom Rack fixture configuration with explicit test overrides.
     *
     * @param extras explicit configuration overrides for the fixture
     * @return the validated fixture configuration
     */
    RackConfig config(Map<String, Object> extras) {
        var values = new HashMap<String, Object>(extras);
        values.put("id", "example");
        values.put("root", root.toString());
        return RackConfig.resolve(values, root);
    }

    /**
     * Verifies mounts, admission/body bounds and generated environment conflicts are validated.
     */
    @Test
    void validatesMountsBoundsAndEnvironmentConflicts() {
        var config = config(Map.of("mount", "/ruby/"));
        assertEquals("/ruby", config.mount());
        assertTrue(config.matches("/ruby"));
        assertTrue(config.matches("/ruby/orders"));
        assertFalse(config.matches("/rubygems"));
        for (String path : List.of("ruby", "//ruby", "/ruby/..", "/ruby%2fadmin", "/ruby?x"))
            assertThrows(RackException.class, () -> config(Map.of("mount", path)));
        assertThrows(RackException.class, () -> config(Map.of("workers", 0)));
        assertThrows(RackException.class, () -> config(Map.of("queueCapacity", -1)));
        assertThrows(RackException.class, () -> config(Map.of("surprise", true)));
        assertThrows(RackException.class, () -> config(Map.of("rackup", "../outside.ru")));
        assertThrows(RackException.class, () -> config(Map.of("runtime", Map.of("env", Map.of("RACK_ENV", "other")))));
        assertThrows(RackException.class, () -> config(Map.of("runtime", Map.of("env", Map.of("KEY", 1)))));
    }

    /**
     * Verifies malformed keys, null limits and invalid environment names fail as configuration errors.
     */
    @Test
    void invalidConfigurationFailsBeforeWorkerStartup() {
        var values = new HashMap<String, Object>();
        values.put("workers", null);
        assertThrows(RackException.class, () -> config(values));
        assertThrows(RackException.class,
            () -> RackConfig.fields(Collections.singletonMap(null, 1), Set.of("workers")));
        assertThrows(RackException.class,
            () -> RackConfig.fields(Map.of(1, 1), Set.of("workers")));
        for (String name : List.of("", "BAD=NAME", "BAD\0NAME"))
            assertThrows(RackException.class,
                () -> config(Map.of("runtime", Map.of("env", Map.of(name, "value")))));
        assertThrows(RackException.class,
            () -> config(Map.of("runtime", Map.of("env", Map.of("KEY", "bad\0value")))));
        var collisions = new LinkedHashMap<Object, Object>();
        collisions.put("KEY", "first");
        collisions.put(new StringBuilder("KEY"), "second");
        assertThrows(RackException.class,
            () -> config(Map.of("runtime", Map.of("env", collisions))));
    }

    /**
     * Verifies configuration snapshots round-trip and mutations do not change live app settings.
     */
    @Test
    void configurationSnapshotsAreDetachedAndReusable() {
        var resolved = config(Map.of("runtime", Map.of("env", Map.of("APP_VALUE", "original"),
            "loadPaths", List.of("lib"))));
        var snapshot = resolved.toStruct();
        assertEquals(resolved, RackConfig.resolve(snapshot, root));
        var runtime = (ortus.boxlang.runtime.types.IStruct) snapshot.get("runtime");
        ((ortus.boxlang.runtime.types.IStruct) runtime.get("env")).put("APP_VALUE", "changed");
        ((List<?>) runtime.get("loadPaths")).clear();
        assertEquals("original", ((Map<?, ?>) resolved.runtime().get("env")).get("APP_VALUE"));
        assertEquals(List.of("lib"), resolved.runtime().get("loadpaths"));
    }

    /**
     * Verifies frozen production boot requires an existing lockfile and performs no gem installation.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void frozenProductionBundleRequiresLockAndDoesNotInstall() throws Exception {
        Files.writeString(root.resolve("Gemfile"), "gem 'rack'");
        assertThrows(RackException.class, () -> config(Map.of()));
        assertFalse(config(Map.of("bundler", Map.of("enabled", false))).bundler());
        Files.writeString(root.resolve("Gemfile.lock"), "fixture");
        assertTrue(config(Map.of()).bundler());
        assertTrue(config(Map.of()).frozen());
    }

    /**
     * Verifies body storage switches to disk, deletes spills and bounds unknown-length input.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void spoolsAndDeletesAndEnforcesUnknownLengthLimit() throws Exception {
        var config = config(Map.of("maxRequestBodyBytes", 20, "requestMemoryThresholdBytes", 4));
        Path spooled;
        try (var body = RequestBody.read(new ByteArrayInputStream(new byte[] {0, -1, 1, 2, 3}), -1, config)) {
            spooled = Path.of(body.getPath());
            assertNull(body.getBytes());
            assertArrayEquals(new byte[] {0, -1, 1, 2, 3}, Files.readAllBytes(spooled));
        }
        assertFalse(Files.exists(spooled));
        try (var body = RequestBody.read(new ByteArrayInputStream(new byte[] {0, -1}), 2, config)) {
            assertNull(body.getPath());
            assertArrayEquals(new byte[] {0, -1}, body.getBytes());
        }
        assertEquals(
            413,
            assertThrows(
                RackException.class,
                () -> RequestBody.read(new ByteArrayInputStream(new byte[21]), -1, config)
            )
            .getStatus()
        );
        assertEquals(
            413,
            assertThrows(
                RackException.class,
                () -> RequestBody.read(new ByteArrayInputStream(new byte[0]), 21, config)
            )
            .getStatus()
        );
    }
}
