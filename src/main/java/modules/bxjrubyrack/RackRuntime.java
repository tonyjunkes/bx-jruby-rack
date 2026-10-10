package modules.bxjrubyrack;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.modules.ModuleRecord;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.*;
import ortus.boxlang.runtime.util.FileSystemUtil;

/** One registry per module activation; namespaces are explicit gateway scopes. */
public final class RackRuntime {
    private static volatile RackRuntime active;
    private final CoreBridge core;
    private final Map<String, RackApp> apps = new ConcurrentHashMap<>();
    // Includes draining apps until their worker threads terminate.
    private final Set<RackApp> owned = ConcurrentHashMap.newKeySet();
    private final Set<Thread> tasks = ConcurrentHashMap.newKeySet();
    private final Set<String> scopes = new HashSet<>();
    private final List<RackConfig> declared = new ArrayList<>();
    private final AutoCloseable shutdown;
    private volatile boolean closed;

    /**
     * Resolves declared apps for one activation and registers coordinated cleanup with core.
     *
     * @param runtime the active BoxLang runtime
     * @param record this activation's module record and settings
     */
    private RackRuntime(BoxRuntime runtime, ModuleRecord record) {
        core = new CoreBridge(runtime);
        Object configured = record.settings.get("apps");
        if (!(configured instanceof Iterable<?> list)) throw RackConfig.config("settings.apps must be an array");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw RackConfig.config("Each declared app must be a struct");
            var c = RackConfig.resolve(map, Path.of(".").toAbsolutePath());
            Object root = map.get(Key.of("root"));
            if (root == null) root = map.get("root");
            if (root == null || !Path.of(root.toString()).isAbsolute())
                throw RackConfig.config("Declarative app roots must be absolute");
            declared.add(c);
        }
        if (!declared.isEmpty()) {
            try {
                Class.forName("jakarta.servlet.Filter", false, RackRuntime.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw RackConfig.config("Declarative HTTP apps require a Jakarta Servlet host and Rack gateway");
            }
        }
        shutdown = core.beforeClose(this::stopAll);
    }

    /**
     * Activates the registry after core loads. Declared apps boot when their gateway scope opens.
     *
     * @param runtime the active BoxLang runtime
     * @param record this activation's module record and settings
     */
    public static void start(BoxRuntime runtime, ModuleRecord record) {
        active = new RackRuntime(runtime, record);
    }

    /**
     * Drains this activation, unregisters its core shutdown hook, and removes only BIFs owned by the named
     * module.
     *
     * @param runtime the active BoxLang runtime
     * @param name the module name whose BIFs may be unregistered
     */
    public static void stop(BoxRuntime runtime, String name) {
        RackRuntime current = active;
        active = null;
        if (current != null) {
            current.stopAll();
            try {
                current.shutdown.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        for (String bif : List.of("jrubyRack", "jrubyRackGet", "jrubyRackTask")) {
            var descriptor = runtime.getFunctionService().getGlobalFunction(bif);
            if (descriptor != null && name.equalsIgnoreCase(descriptor.module))
                runtime.getFunctionService().unregisterGlobalFunction(Key.of(bif));
        }
    }

    /**
     * Obtains the live registry, rejecting calls outside an active activation.
     *
     * @return the active registry
     * @throws RackException if this activation is closed or absent
     */
    private static RackRuntime current() {
        var instance = active;
        if (instance == null || instance.closed)
            throw new RackException("MODULE_CLOSED", 503, "bx-jruby-rack is not active");
        return instance;
    }

    /**
     * Registers a fixed servlet-context scope and boots its declared apps, rolling back the scope on boot
     * failure.
     *
     * @param scope the fixed servlet-context gateway namespace
     * @throws RackException if the scope is invalid, already registered, or an app is invalid
     */
    public static void openGateway(String scope) {
        if (scope == null || !scope.matches("[A-Za-z0-9_.-]+")) throw RackConfig.config("Invalid gateway scope");
        var current = current();
        synchronized (current) {
            if (!current.scopes.add(scope)) throw RackConfig.config("Gateway scope already registered: " + scope);
            try {
                for (var config : current.declared) {
                    if (config.scope().equals(scope)) current.create(config);
                }
            } catch (RuntimeException e) {
                closeGateway(scope);
                throw e;
            }
        }
    }

    /**
     * Removes a gateway scope and waits for its owned workers to finish cleanup and termination.
     *
     * @param scope the fixed servlet-context gateway namespace
     */
    public static void closeGateway(String scope) {
        var current = active;
        if (current == null) return;
        synchronized (current) {
            current.scopes.remove(scope);
        }
        for (RackApp app : List.copyOf(current.owned))
            if (app.config().scope().equals(scope)) {
                app.awaitClosed();
                current.owned.remove(app);
            }
    }

    /**
     * Boots and registers an app in an active gateway scope. IDs and mounts must be unique within that scope;
     * caller contexts provide a base for relative paths.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param config the app settings or resolved immutable configuration
     * @return the booted application handle
     * @throws RackException if the scope is inactive or the ID or mount collides
     */
    public static RackApp create(IBoxContext context, Map<?, ?> config) {
        return current().create(RackConfig.resolve(config, FileSystemUtil.expandPath(context, ".").absolutePath()));
    }

    /**
     * Boots and registers an app in an active gateway scope. IDs and mounts must be unique within that scope;
     * caller contexts provide a base for relative paths.
     *
     * @param config the app settings or resolved immutable configuration
     * @return the booted application handle
     * @throws RackException if the scope is inactive or the ID or mount collides
     */
    private synchronized RackApp create(RackConfig config) {
        if (closed || !scopes.contains(config.scope()))
            throw RackConfig.config("No active servlet gateway for scope: " + config.scope());
        String key = config.scope() + ":" + config.id();
        for (var app : apps.values()) {
            var existing = app.config();
            if (existing.scope().equals(config.scope())
                && (existing.id().equals(config.id())
                || existing.matches(config.mount())
                || config.matches(existing.mount()))
            ) {
                throw RackConfig.config("App id or mount collides in scope " + config.scope());
            }
        }
        var app = new RackApp(config, core, removed -> apps.remove(key, removed));
        owned.removeIf(old -> old.status().get("state").equals("CLOSED"));
        owned.add(app);
        try {
            app.boot();
            apps.put(key, app);
            return app;
        } catch (RuntimeException e) {
            owned.remove(app);
            throw e;
        }
    }

    /**
     * Finds an app by its explicit scope and identifier.
     *
     * @param id the application identifier within the selected scope
     * @param scope the fixed servlet-context gateway namespace
     * @return the registered app
     * @throws RackException if no app is registered with this ID and scope
     */
    public static RackApp get(String id, String scope) {
        RackApp app = current().apps.get(scope + ":" + id);
        if (app == null) throw new RackException("APP_NOT_FOUND", 404, "No Rack app with id in this scope");
        return app;
    }

    /**
     * Delegates routing without linking servlet types into the CLI entry point.
     *
     * @param scope the fixed servlet-context gateway namespace
     * @param rawRequest the Jakarta HTTP request, typed as Object for CLI linkage isolation
     * @param rawResponse the Jakarta HTTP response, typed as Object for CLI linkage isolation
     * @return true if Rack owns the request; false to continue the host chain
     */
    public static boolean dispatch(String scope, Object rawRequest, Object rawResponse) {
        return ServletAdapter.dispatch(current(), scope, rawRequest, rawResponse);
    }

    /**
     * Finds the app owning a path within one gateway scope.
     *
     * @param scope the fixed servlet-context gateway namespace
     * @param path the Ruby file path or request URL path
     * @return the matching app, or null for an unmounted path
     */
    RackApp find(String scope, String path) {
        return apps.values().stream()
                .filter(app -> app.config().scope().equals(scope) && app.config().matches(path))
                .findFirst()
                .orElse(null);
    }

    /**
     * Invokes a Rake task in a fresh session on a dedicated thread. String arguments are passed without
     * building a shell command; the caller waits through cleanup.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param config the application configuration
     * @param task the Rake task name to invoke explicitly
     * @param args the structured strings passed directly to the Rake task
     * @return the task result and captured stdout/stderr
     * @throws RackException if task configuration or execution fails
     */
    public static IStruct task(IBoxContext context, Map<?, ?> config, String task, List<?> args) {
        var current = current();
        var resolved = RackConfig.resolve(config, FileSystemUtil.expandPath(context, ".").absolutePath());
        if (task.isBlank()) throw RackConfig.config("Task name is required");
        for (Object arg : args) if (!(arg instanceof String)) throw RackConfig.config("Task arguments must be strings");
        var result =
                new FutureTask<IStruct>(() -> {
                    try (var session = current.core.session(resolved)) {
                        return session.eval(
                            """
                            module Kernel
                                def exit!(*); raise 'exit! is unsupported in embedded Rack tasks'; end
                                module_function :exit!
                            end
                            begin
                                require 'bundler/setup' if bx_bundle
                                require 'rake'
                                Rake.application.init('rake', [])
                                load File.join(Dir.pwd, 'Rakefile')
                                Rake.application.load_imports
                                Rake::Task[bx_task].invoke(*bx_args)
                                nil
                            rescue SystemExit => error
                                raise "Embedded task attempted to exit with status #{error.status}"
                            end
                            """,
                            Map.of("bx_bundle", resolved.bundler(), "bx_task", task, "bx_args", args)
                        );
                    }
                });
        Thread thread = new Thread(result, "bx-rack-task-" + resolved.id());
        thread.setDaemon(true);
        synchronized (current) {
            if (current.closed) throw new RackException("MODULE_CLOSED", 503, "Rack module is closed");
            current.tasks.add(thread);
            thread.start();
        }
        boolean interrupted = false;
        try {
            for (; ; )
                try {
                    return result.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new RackException("TASK_FAILED", 500, "Rack task failed: " + task, e.getCause());
                }
        } finally {
            current.tasks.remove(thread);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops admission and waits for owned workers and tasks without forcibly terminating Ruby.
     */
    private void stopAll() {
        synchronized (this) {
            closed = true;
            scopes.clear();
        }
        for (var app : List.copyOf(owned)) app.close();
        for (var app : List.copyOf(owned)) app.awaitClosed();
        boolean interrupted = false;
        for (Thread task : List.copyOf(tasks))
            while (task.isAlive()) {
                try {
                    task.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        owned.clear();
        apps.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }
}
