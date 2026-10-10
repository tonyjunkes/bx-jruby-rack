package modules.bxjrubyrack.gateway;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.lang.reflect.*;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.scopes.Key;

/** Deploy this small gateway JAR in WEB-INF/lib and map before BoxLang and body parsers. */
public final class RackGateway implements Filter {
    private String scope;
    private Class<?> bridge;
    private BoxRuntime runtime;

    /**
     * Initializes the shared BoxLang runtime and opens this filter's scope before accepting requests.
     *
     * @param config filter settings containing scope and shared BoxLang home/config paths
     * @throws ServletException if gateway startup or committed-request handling fails
     */
    @Override
    public void init(FilterConfig config) throws ServletException {
        scope = config.getInitParameter("scope");
        if (scope == null) scope = "default";
        runtime = BoxRuntime.getInstance(
            null,
            config.getInitParameter("boxlang-config-path"),
            config.getInitParameter("boxlang-home")
        )
        .waitForStart();
        try {
            refresh();
        } catch (RuntimeException e) {
            throw new ServletException("Rack gateway startup failed", e);
        }
    }

    /**
     * Dispatches mounted requests and delegates unmatched requests. Gateway failure returns 503 before
     * commitment or fails the transport afterward.
     *
     * @param request the incoming servlet request
     * @param response the corresponding servlet response
     * @param chain the downstream servlet chain for unmatched requests
     * @throws IOException if transport I/O or temporary-file cleanup fails
     * @throws ServletException if gateway startup or committed-request handling fails
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        boolean handled;
        try {
            refresh();
            handled = (Boolean) call(
                "dispatch",
                new Class<?>[] {String.class, Object.class, Object.class},
                scope,
                request,
                response
            );
        } catch (RuntimeException e) {
            System.getLogger("bx-jruby-rack").log(System.Logger.Level.ERROR, "Rack gateway unavailable", e);
            if (response.isCommitted()) throw new ServletException("Rack gateway failed after commitment", e);
            var http = (HttpServletResponse) response;
            http.reset();
            http.setStatus(503);
            http.setHeader("Retry-After", "1");
            http.setContentType("text/plain; charset=utf-8");
            http.getOutputStream().write("Service unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (!handled) chain.doFilter(request, response);
    }

    /**
     * Closes this filter's scope and waits for workers to release their runtimes.
     */
    @Override
    public void destroy() {
        if (bridge != null) call("closeGateway", new Class<?>[] {String.class}, scope);
    }

    /**
     * Resolves the active module loader and reopens the scope after reload, retaining the prior bridge on
     * failure.
     */
    private synchronized void refresh() {
        var module = runtime.getModuleService().getModuleRecord(Key.of("bxjrubyrack"));
        if (module == null || module.getModuleClassLoader() == null)
            throw new IllegalStateException("Install and enable bx-jruby-rack and bx-jruby");
        try {
            Class<?> latest = module.getModuleClassLoader().toClassLoader().loadClass("modules.bxjrubyrack.RackRuntime");
            if (latest != bridge) {
                Class<?> previous = bridge;
                bridge = latest;
                try {
                    call("openGateway", new Class<?>[] {String.class}, scope);
                } catch (RuntimeException error) {
                    bridge = previous;
                    throw error;
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Rack gateway API missing", e);
        }
    }

    /**
     * Invokes a public module entry point and unwraps runtime reflection failures.
     *
     * @param name the public Rack gateway entry-point name
     * @param signature the public parameter types for reflective lookup
     * @param arguments positional gateway entry-point arguments
     * @return the Rack entry point's return value
     */
    private Object call(String name, Class<?>[] signature, Object... arguments) {
        try {
            return bridge.getMethod(name, signature).invoke(null, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Incompatible Rack gateway API", e);
        }
    }
}
