package modules.bxjrubyrack.gateway;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.*;

/** Early servlet gateway; host libraries may live above the BoxLang webapp classloader. */
public final class RackGateway implements Filter {
    private String scope;
    private Object modules;
    private Object moduleKey;
    private Method lookup;
    private volatile GatewayApi bridge;

    /**
     * Resolves BoxLang through the servlet webapp loader, then opens this gateway scope.
     * @param config scope and shared runtime paths
     * @throws ServletException if BoxLang or Rack initialization fails
     */
    @Override
    public void init(FilterConfig config) throws ServletException {
        scope = config.getInitParameter("scope");
        if (scope == null) {
            scope = "default";
        }
        try {
            var loader = config.getServletContext().getClassLoader();
            var runtimeType = loader.loadClass("ortus.boxlang.runtime.BoxRuntime");
            var runtime = invoke(runtimeType.getMethod("getInstance", Boolean.class, String.class, String.class),
                null, null, config.getInitParameter("boxlang-config-path"), config.getInitParameter("boxlang-home"));
            invoke(runtimeType.getMethod("waitForStart"), runtime);
            modules = invoke(runtimeType.getMethod("getModuleService"), runtime);
            var keyType = loader.loadClass("ortus.boxlang.runtime.scopes.Key");
            moduleKey = invoke(keyType.getMethod("of", String.class), null, "bxjrubyrack");
            lookup = modules.getClass().getMethod("getModuleRecord", keyType);
            refresh();
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new ServletException("Rack gateway startup failed", error);
        }
    }

    /**
     * Routes matched requests with one immutable activation snapshot, delegating unmatched requests.
     * @param request the incoming request
     * @param response the servlet response
     * @param chain downstream BoxLang and static-file handlers
     * @throws IOException on transport failure
     * @throws ServletException on failure after response commitment
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        boolean handled;
        try {
            var current = refresh();
            handled = (Boolean) invoke(current.dispatch(), null, scope, request, response);
        } catch (RuntimeException error) {
            System.getLogger("bx-jruby-rack").log(System.Logger.Level.ERROR, "Rack gateway unavailable", error);
            if (response.isCommitted()) {
                throw new ServletException("Rack gateway failed after commitment", error);
            }
            var http = (HttpServletResponse) response;
            http.reset();
            http.setStatus(503);
            http.setHeader("Retry-After", "1");
            http.setContentType("text/plain; charset=utf-8");
            http.getOutputStream().write("Service unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (!handled) {
            chain.doFilter(request, response);
        }
    }

    /** Closes the current scope and waits for its owned workers. */
    @Override
    public void destroy() {
        var current = bridge;
        if (current != null) {
            invoke(current.close(), null, scope);
        }
    }

    /**
     * Rebinds after module reload, publishing cached entry points only after the new scope opens.
     * @return the active gateway snapshot
     */
    private synchronized GatewayApi refresh() {
        var module = invoke(lookup, modules, moduleKey);
        if (module == null) {
            throw new IllegalStateException("Install and enable bx-jruby-rack and bx-jruby");
        }
        try {
            var moduleLoader = invoke(module.getClass().getMethod("getModuleClassLoader"), module);
            if (moduleLoader == null) {
                throw new IllegalStateException("Rack module is not registered");
            }
            var loader = (ClassLoader) invoke(moduleLoader.getClass().getMethod("toClassLoader"), moduleLoader);
            var latest = loader.loadClass("modules.bxjrubyrack.RackRuntime");
            if (bridge == null || bridge.type() != latest) {
                var candidate = new GatewayApi(latest,
                    latest.getMethod("openGateway", String.class), latest.getMethod("closeGateway", String.class),
                    latest.getMethod("dispatch", String.class, Object.class, Object.class));
                invoke(candidate.open(), null, scope);
                bridge = candidate;
            }
            return bridge;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Incompatible Rack gateway API", error);
        }
    }

    /**
     * Invokes a public host/module method without linking their classes into the gateway loader.
     * @param method the cached public method
     * @param target the instance or null for static methods
     * @param args method arguments
     * @return the result
     */
    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error.getCause() instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Incompatible host or Rack API", error);
        }
    }

    /**
     * Cached public methods belonging to one module activation.
     * @param type the activation's bridge class
     * @param open opens the scope
     * @param close drains the scope
     * @param dispatch routes an HTTP request
     */
    private record GatewayApi(Class<?> type, Method open, Method close, Method dispatch) {}
}
