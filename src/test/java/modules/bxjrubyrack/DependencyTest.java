package modules.bxjrubyrack;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import ortus.boxlang.runtime.BoxRuntime;

class DependencyTest {
    @TempDir Path temp;

    /**
     * Verifies isolated installed-module startup rejects missing, disabled and incompatible core dependencies.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void missingDisabledAndIncompatibleCoreFailInstalledStartup() throws Exception {
        for (String mode : List.of("missing", "disabled", "incompatible")) {
            Path root = temp.resolve(mode);
            Files.createDirectories(root.resolve("modules/rack"));
            try (var zip = new ZipFile(System.getProperty("rack.moduleZip"))) {
                for (var entry : Collections.list(zip.entries()))
                    if (!entry.isDirectory()) {
                        Path target = root.resolve("modules/rack").resolve(entry.getName()).normalize();
                        assertTrue(target.startsWith(root));
                        Files.createDirectories(target.getParent());
                        try (var input = zip.getInputStream(entry)) {
                            Files.copy(input, target);
                        }
                    }
            }
            if (!mode.equals("missing")) {
                Path core = root.resolve("modules/core");
                Files.createDirectories(core);
                Files.writeString(
                    core.resolve("ModuleConfig.bx"),
                    "class { this.mapping='bxjruby'; this.version='1.0.0'; function configure() {} }"
                );
                Files.writeString(
                    core.resolve("box.json"),
                    "{\"name\":\"fake"
                        + " core\",\"slug\":\"bx-jruby\",\"version\":\"1.0.0\",\"boxlang\":{\"moduleName\":\"bxjruby\"}}"
                );
            }
            Path config = root.resolve("boxlang.json"),
                    script = root.resolve("smoke.bxs"),
                    output = root.resolve("output.txt");
            Files.writeString(script, "throw(message='Unexpected startup success');");
            Files.writeString(
                config,
                "{\"modulesDirectory\":[\""
                    + root.resolve("modules").toString().replace('\\', '/')
                    + "\"],\"modules\":{\"bxjruby\":{\"enabled\":"
                    + !mode.equals("disabled")
                    + "}}}"
            );
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath =
                    Path.of(BoxRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            var process = new ProcessBuilder(
                java,
                "-Xmx512m",
                "-cp",
                classpath,
                "ortus.boxlang.runtime.BoxRunner",
                "--bx-home",
                root.resolve("home").toString(),
                "--bx-config",
                config.toString(),
                script.toString()
            )
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();

            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            String message = Files.readString(output);
            assertFalse(message.contains("Unexpected startup success"), message);
            assertTrue(message.contains("bxjruby") || message.contains("bx-jruby"), message);
        }
    }
}
