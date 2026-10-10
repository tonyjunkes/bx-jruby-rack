package modules.bxjrubyrack;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.Map;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;

/**
 * Calls only versioned public APIs via BoxLang's supported module loader. No static linkage to sibling-module
 * classes
 * or JRuby internals.
 */
final class CoreBridge {
    private final Class<?> api;

    /**
     * Resolves active core through its supported loader and verifies extension API version 1.
     *
     * @param runtime the active BoxLang runtime
     * @throws RackException if core is absent, inactive or incompatible
     */
    CoreBridge(BoxRuntime runtime) {
        var record = runtime.getModuleService().getModuleRecord(Key.of("bxjruby"));
        if (record == null || record.getModuleClassLoader() == null || record.activatedOn == null)
            throw new RackException("CORE_MISSING", 500, "bx-jruby 1.1.0+ must be installed and active");
        try {
            api = record.getModuleClassLoader().toClassLoader().loadClass("modules.bxjruby.JRubyRuntime");
            if (!Integer.valueOf(1).equals(invoke(api.getMethod("extensionVersion"), null)))
                throw new NoSuchMethodException();
            var sessionType = api.getMethod("session", Path.class, Map.class).getReturnType();
            sessionType.getMethod("eval", String.class, Map.class);
            sessionType.getMethod("closeAndCapture");
            api.getMethod("beforeClose", Runnable.class);
        } catch (ReflectiveOperationException e) {
            throw new RackException("CORE_INCOMPATIBLE", 500, "bx-jruby 1.1.0+ extension API 1 is required");
        }
    }

    /**
     * Registers cleanup before core closes remaining sessions.
     *
     * @param callback the cleanup callback that waits for extension-owned work
     * @return a handle whose close method unregisters the callback
     */
    AutoCloseable beforeClose(Runnable callback) {
        return (AutoCloseable) method(api, "beforeClose", null, new Class<?>[] {Runnable.class}, callback);
    }

    /**
     * Creates a core-owned session from resolved app options without retaining a request context.
     *
     * @param config the application configuration
     * @return the reflected session adapter
     */
    Session session(RackConfig config) {
        return new Session(
            method(api, "session", null, new Class<?>[] {Path.class, Map.class}, config.root(), config.runtime())
        );
    }

    /**
     * Resolves and invokes a versioned public core method.
     *
     * @param type the public core class declaring the method
     * @param name the versioned public core method name
     * @param target the receiver, or null for a static method
     * @param signature the public parameter types for reflective lookup
     * @param args the positional invocation arguments
     * @return the method result, or null for a void method
     * @throws IllegalStateException if the required public method does not exist
     */
    static Object method(Class<?> type, String name, Object target, Class<?>[] signature, Object... args) {
        try {
            return invoke(type.getMethod(name, signature), target, args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Incompatible public core API: " + name, e);
        }
    }

    /**
     * Invokes a reflected core method, preserving original runtime exceptions and errors.
     *
     * @param method the reflected public core method
     * @param target the receiver, or null for a static method
     * @param args the positional invocation arguments
     * @return the invocation result
     * @throws IllegalStateException if reflection fails or the method throws a checked exception
     */
    static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class Session implements AutoCloseable {
        private final Object session;

        /**
         * Wraps a core session without exposing its implementation types across module loaders.
         *
         * @param session the reflected core-owned session instance
         */
        Session(Object session) {
            this.session = session;
        }

        /**
         * Evaluates source and bindings in the owned core session.
         *
         * @param script the Ruby source to evaluate
         * @param bindings explicit Ruby local bindings, leaving other session locals intact
         * @return the converted result and bounded stdout/stderr
         */
        IStruct eval(String script, Map<?, ?> bindings) {
            try {
                return (IStruct) method(
                    session.getClass(),
                    "eval",
                    session,
                    new Class<?>[] {String.class, Map.class},
                    script,
                    bindings
                );
            } catch (RuntimeException error) {
                logFailure(error);
                throw error;
            }
        }

        /**
         * Logs bounded captures retained by core execution failures without linking core exception types.
         *
         * @param error the original exception, which remains the propagated failure
         */
        private static void logFailure(RuntimeException error) {
            if (!error.getClass().getName().equals("modules.bxjruby.RubyExecutionException")) return;
            try {
                RackApp.log(ortus.boxlang.runtime.types.Struct.of(
                    "stdout", invoke(error.getClass().getMethod("getStdout"), error),
                    "stderr", invoke(error.getClass().getMethod("getStderr"), error)
                ));
            } catch (ReflectiveOperationException | RuntimeException logging) {
                error.addSuppressed(logging);
            }
        }

        /**
         * Replaces transport bindings with nil so workers do not retain servlet requests between operations.
         */
        void clearRequestBindings() {
            var empty = new java.util.HashMap<String, Object>();
            for (String name : java.util.List.of("bx_meta", "bx_input", "bx_sink")) empty.put(name, null);
            eval("nil", empty);
        }

        /**
         * Terminates the session and logs a fresh capture of Ruby exit-handler output.
         */
        @Override
        public void close() {
            try {
                RackApp.log((IStruct) method(session.getClass(), "closeAndCapture", session, new Class<?>[0]));
            } catch (RuntimeException error) {
                logFailure(error);
                throw error;
            }
        }
    }
}
