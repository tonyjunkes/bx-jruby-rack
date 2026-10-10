package modules.bxjrubyrack;

import java.nio.file.*;
import java.util.*;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Immutable app settings resolved before workers start. Paths are physical, never request-derived.
 *
 * @param id the app identifier within its gateway scope
 * @param scope the fixed servlet-context namespace
 * @param root the physical app root directory
 * @param rackup the resolved Rack entry-point file
 * @param mount the normalized URL mount, with segment-boundary matching
 * @param environment the generated RACK_ENV value
 * @param bundler whether to load the app's bundle before Rack
 * @param gemfile the resolved application Gemfile
 * @param frozen whether Bundler must use the existing lockfile without updating it
 * @param runtime the immutable core session options and environment overlay
 * @param workers the number of isolated thread-affine runtimes
 * @param queueCapacity the number of additional requests admitted while workers are busy
 * @param acquireTimeoutMs the maximum wait for an unstarted request to acquire a worker
 * @param shutdownGraceMs the wait budget for an explicit close, without cancelling active Ruby
 * @param maxRequestBodyBytes the maximum actual or declared body size in bytes
 * @param requestMemoryThresholdBytes the size above which input spills to a temporary file
 */
public record RackConfig(
        String id,
        String scope,
        Path root,
        Path rackup,
        String mount,
        String environment,
        boolean bundler,
        Path gemfile,
        boolean frozen,
        Map<String, Object> runtime,
        int workers,
        int queueCapacity,
        long acquireTimeoutMs,
        long shutdownGraceMs,
        long maxRequestBodyBytes,
        int requestMemoryThresholdBytes
) {
    /**
     * Normalizes keys case-insensitively and rejects unsupported or duplicate fields.
     *
     * @param object the configuration struct to normalize
     * @param allowed the supported lowercase option names
     * @return a mutable normalized field map
     * @throws RackException if the input is not a struct or fields are unknown or duplicated
     */
    static Map<String, Object> fields(Object object, Set<String> allowed) {
        if (!(object instanceof Map<?, ?> map)) throw config("Expected a configuration struct");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> {
            if (!(key instanceof CharSequence) && !(key instanceof Key))
                throw config("Configuration keys must be strings");
            String name = key instanceof Key k ? k.getName() : key.toString();
            name = name.toLowerCase(Locale.ROOT);
            if (!allowed.contains(name) || result.containsKey(name))
                throw config("Unknown or duplicate option: " + name);
            result.put(name, value);
        });
        return result;
    }

    /**
     * Resolves paths, bundle settings, environment values and bounds before workers start.
     *
     * @param values the supplied configuration values
     * @param base the physical base directory for relative paths
     * @return the immutable application configuration
     * @throws RackException if paths, bundle settings, environment values or limits are invalid
     */
    public static RackConfig resolve(Map<?, ?> values, Path base) {
        var c = fields(
            values,
            Set.of(
                "id",
                "scope",
                "root",
                "rackup",
                "mount",
                "environment",
                "bundler",
                "runtime",
                "workers",
                "queuecapacity",
                "acquiretimeoutms",
                "shutdowngracems",
                "maxrequestbodybytes",
                "requestmemorythresholdbytes"
            )
        );
        String id = string(c, "id", null), scope = string(c, "scope", "default");
        if (!id.matches("[A-Za-z0-9_.-]+") || !scope.matches("[A-Za-z0-9_.-]+"))
            throw config("id and scope must contain only letters, digits, underscore, dot or hyphen");
        Path root = path(base, string(c, "root", null));
        if (!Files.isDirectory(root)) throw config("root is not a directory: " + root);
        Path rackup = child(root, string(c, "rackup", "config.ru"));
        String mount = string(c, "mount", "/");
        if (!mount.matches("/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?)?"))
            throw config("mount must be an unambiguous absolute URL path");
        if (mount.endsWith("/") && mount.length() > 1) mount = mount.substring(0, mount.length() - 1);
        String environment = string(c, "environment", "production");
        var bundle = fields(c.getOrDefault("bundler", Map.of()), Set.of("enabled", "gemfile", "frozen"));
        Path gemfile = child(root, string(bundle, "gemfile", "Gemfile"));
        boolean enabled = bool(bundle, "enabled", Files.isRegularFile(gemfile));
        boolean frozen = bool(bundle, "frozen", environment.equals("production"));
        if (enabled && !Files.isRegularFile(gemfile))
            throw config("Bundler enabled but Gemfile is missing: " + gemfile);
        if (enabled && frozen && !Files.isRegularFile(Path.of(gemfile + ".lock")))
            throw config("Frozen bundle requires a lockfile: " + gemfile + ".lock");
        var runtime = fields(c.getOrDefault("runtime", Map.of()), Set.of("gemhome", "gempaths", "loadpaths", "env"));
        var options = new LinkedHashMap<String, Object>();
        options.put("workingDirectory", root.toString());
        options.put("outputLimit", 65536);
        for (String name : List.of("gemhome", "gempaths", "loadpaths")) {
            if (runtime.containsKey(name)) {
                Object value = runtime.get(name);
                options.put(name, value instanceof List<?> list ? List.copyOf(list) : value);
            }
        }
        var env = new LinkedHashMap<String, String>();
        if (runtime.containsKey("env")) {
            if (!(runtime.get("env") instanceof Map<?, ?> map)) throw config("runtime.env must be a struct of strings");
            map.forEach((key, value) -> {
                if (!(key instanceof CharSequence) && !(key instanceof Key))
                    throw config("runtime.env keys must be strings");
                String name = key instanceof Key k ? k.getName() : key.toString();
                if (name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0 ||
                    !(value instanceof String text) || text.indexOf('\0') >= 0)
                    throw config("runtime.env requires valid names and string values");
                if (env.putIfAbsent(name, text) != null)
                    throw config("Duplicate runtime.env key: " + name);
            });
        }
        own(env, "RACK_ENV", environment);
        if (enabled) {
            own(env, "BUNDLE_GEMFILE", gemfile.toString());
            own(env, "BUNDLE_FROZEN", Boolean.toString(frozen));
        }
        options.put("env", Map.copyOf(env));
        long maximum = number(c, "maxrequestbodybytes", 16777216, 0, Integer.MAX_VALUE);
        int threshold = (int) number(c, "requestmemorythresholdbytes", 65536, 0, Integer.MAX_VALUE);
        if (threshold > maximum) throw config("requestMemoryThresholdBytes exceeds maxRequestBodyBytes");
        return new RackConfig(
            id,
            scope,
            root,
            rackup,
            mount,
            environment,
            enabled,
            gemfile,
            frozen,
            Map.copyOf(options),
            (int) number(c, "workers", 1, 1, 256),
            (int) number(c, "queuecapacity", 32, 0, 100000),
            number(c, "acquiretimeoutms", 5000, 1, Integer.MAX_VALUE),
            number(c, "shutdowngracems", 30000, 0, Integer.MAX_VALUE),
            maximum,
            threshold
        );
    }

    /**
     * Adds a generated environment value and rejects conflicting caller values.
     *
     * @param env the application environment map being assembled
     * @param key the generated environment variable name
     * @param value the required generated environment value
     * @throws RackException if a configured value conflicts with the generated value
     */
    private static void own(Map<String, String> env, String key, String value) {
        if (env.containsKey(key) && !env.get(key).equals(value))
            throw config("runtime.env conflicts with generated " + key);
        env.put(key, value);
    }

    /**
     * Reads a required or defaulted nonblank string option.
     *
     * @param c the normalized configuration fields
     * @param key the canonical string option name
     * @param fallback the value used when the option is omitted
     * @return the validated string
     * @throws RackException if the value is not a nonblank string
     */
    static String string(Map<String, Object> c, String key, String fallback) {
        Object value = c.getOrDefault(key, fallback);
        if (!(value instanceof String text) || text.isBlank()) throw config(key + " must be a non-empty string");
        return text;
    }

    /**
     * Reads a defaulted integer option within inclusive bounds.
     *
     * @param c the normalized configuration fields
     * @param key the canonical integer option name
     * @param fallback the value used when the option is omitted
     * @param min the smallest accepted value, inclusive
     * @param max the largest accepted value, inclusive
     * @return the validated integer
     * @throws RackException if the value is not an integer within accepted bounds
     */
    static long number(Map<String, Object> c, String key, long fallback, long min, long max) {
        try {
            Object configured = c.getOrDefault(key, fallback);
            if (configured == null) throw config(key + " must be an integer");
            long value = Long.parseLong(configured.toString());
            if (value < min || value > max) throw config(key + " is out of range");
            return value;
        } catch (NumberFormatException error) {
            throw config(key + " must be an integer");
        }
    }

    /**
     * Reads a defaulted option that must be an actual Boolean.
     *
     * @param c the normalized configuration fields
     * @param key the canonical Boolean option name
     * @param fallback the value used when the option is omitted
     * @return the validated Boolean
     * @throws RackException if the value is not a Boolean
     */
    static boolean bool(Map<String, Object> c, String key, boolean fallback) {
        Object value = c.getOrDefault(key, fallback);
        if (!(value instanceof Boolean b)) throw config(key + " must be boolean");
        return b;
    }

    /**
     * Resolves a physical path against its base and normalizes it.
     *
     * @param base the physical base directory for relative paths
     * @param text the absolute or base-relative path string
     * @return the normalized absolute path
     */
    private static Path path(Path base, String text) {
        Path p = Path.of(text);
        return (p.isAbsolute() ? p : base.resolve(p)).toAbsolutePath().normalize();
    }

    /**
     * Resolves an app file path, rejecting relative paths escaping the root. Explicit absolute paths are
     * permitted.
     *
     * @param root the physical application root
     * @param text the absolute or root-relative application file path
     * @return the resolved file path
     * @throws RackException if a relative path escapes the root
     */
    private static Path child(Path root, String text) {
        Path p = path(root, text);
        if (!Path.of(text).isAbsolute() && !p.startsWith(root)) throw config("Relative paths cannot escape root");
        return p;
    }

    /**
     * Creates a consistent configuration validation failure.
     *
     * @param text the configuration validation diagnostic
     * @return a CONFIGURATION exception with HTTP status 500
     */
    static RackException config(String text) {
        return new RackException("CONFIGURATION", 500, text);
    }

    /**
     * Checks whether a request belongs to this mount using URL segment boundaries.
     *
     * @param path the raw request path relative to the servlet context
     * @return true for the exact mount or a descendant path
     */
    public boolean matches(String path) {
        return mount.equals("/") || path.equals(mount) || path.startsWith(mount + "/");
    }

    /**
     * Copies resolved settings into a detached struct suitable for task execution or app replacement.
     * Environment values are included; callers must treat this configuration as potentially sensitive.
     *
     * @return a fresh configuration struct accepted by the public Rack APIs
     */
    public IStruct toStruct() {
        var runtimeSettings = new Struct();
        for (String name : List.of("gemhome", "gempaths", "loadpaths")) {
            if (!runtime.containsKey(name)) continue;
            Object value = runtime.get(name);
            runtimeSettings.put(name, value instanceof List<?> list ? new ArrayList<>(list) : value);
        }
        runtimeSettings.put("env", Struct.fromMap((Map<?, ?>) runtime.get("env")));
        return Struct.of(
            "id", id, "scope", scope, "root", root.toString(), "rackup", rackup.toString(),
            "mount", mount, "environment", environment,
            "bundler", Struct.of("enabled", bundler, "gemfile", gemfile.toString(), "frozen", frozen),
            "runtime", runtimeSettings, "workers", workers, "queueCapacity", queueCapacity,
            "acquireTimeoutMs", acquireTimeoutMs, "shutdownGraceMs", shutdownGraceMs,
            "maxRequestBodyBytes", maxRequestBodyBytes,
            "requestMemoryThresholdBytes", requestMemoryThresholdBytes
        );
    }
}
