package modules.bxjrubyrack;

import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;

/** Loaded only by servlet hosting, leaving offline tasks usable without the Servlet API. */
final class ServletAdapter {
    /**
     * Routes servlet requests to matching apps and maps uncommitted failures to safe HTTP responses.
     *
     * @param runtime the active Rack registry used for scope routing
     * @param scope the fixed servlet-context gateway namespace
     * @param rawRequest the Jakarta HTTP request, typed as Object for CLI linkage isolation
     * @param rawResponse the Jakarta HTTP response, typed as Object for CLI linkage isolation
     * @return true when Rack owns the request, including Rack error responses
     * @throws IllegalStateException if a committed response cannot be replaced
     */
    static boolean dispatch(RackRuntime runtime, String scope, Object rawRequest, Object rawResponse) {
        HttpServletRequest request = (HttpServletRequest) rawRequest;
        HttpServletResponse response = (HttpServletResponse) rawResponse;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        RackApp app = runtime.find(scope, path);
        if (app == null) return false;
        try {
            app.dispatch(
                new RackRequest(
                    request.getInputStream(),
                    request.getContentLengthLong(),
                    metadata(request, app.config()),
                    new RackResponse(response)
                )
            );
        } catch (Exception error) {
            if (error instanceof RackException rack && (rack.getStatus() == 413 || rack.getStatus() == 503))
                System.getLogger("bx-jruby-rack")
                    .log(System.Logger.Level.DEBUG, app.config().id() + ": " + rack.getCode());
            else
                System.getLogger("bx-jruby-rack")
                    .log(System.Logger.Level.ERROR, "Rack request failed: " + app.config().id(), error);

            if (response.isCommitted()) throw new IllegalStateException("Rack failed after response commitment", error);
            response.reset();
            int status = error instanceof RackException rack ? rack.getStatus() : 500;
            response.setStatus(status);
            if (status == 503) response.setHeader("Retry-After", "1");
            response.setContentType("text/plain; charset=utf-8");
            try {
                response.getOutputStream().write(
                    (status == 413
                        ? "Request body too large"
                        : status == 503 ? "Service unavailable" : "Internal server error")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return true;
    }

    /**
     * Builds a fresh CGI environment without decoding raw URIs or applying forwarded-header trust rules.
     *
     * @param r the host-resolved HTTP request
     * @param config the application configuration
     * @return request metadata including context-aware SCRIPT_NAME and PATH_INFO
     */
    private static Map<String, String> metadata(HttpServletRequest r, RackConfig config) {
        var env = new LinkedHashMap<String, String>();
        String context = r.getContextPath(), path = r.getRequestURI().substring(context.length());
        String mount = config.mount().equals("/") ? "" : config.mount();
        env.put("REQUEST_METHOD", r.getMethod());
        env.put("SCRIPT_NAME", context + mount);
        env.put("PATH_INFO", path.substring(mount.length()));
        env.put("QUERY_STRING", Objects.requireNonNullElse(r.getQueryString(), ""));
        env.put("SERVER_NAME", r.getServerName());
        env.put("SERVER_PORT", Integer.toString(r.getServerPort()));
        env.put("SERVER_PROTOCOL", r.getProtocol());
        env.put("REMOTE_ADDR", r.getRemoteAddr());
        env.put("rack.url_scheme", r.isSecure() ? "https" : "http");
        for (var names = r.getHeaderNames(); names.hasMoreElements(); ) {
            String name = names.nextElement(), lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("content-length") || lower.equals("content-type")) continue;
            // CGI maps hyphens to underscores; ignore ambiguous original underscores.
            if (name.indexOf('_') >= 0) continue;
            String separator = lower.equals("cookie") ? "; " : ",";
            env.put(
                "HTTP_" + name.toUpperCase(Locale.ROOT).replace('-', '_'),
                String.join(separator, Collections.list(r.getHeaders(name)))
            );
        }
        if (r.getContentType() != null) env.put("CONTENT_TYPE", r.getContentType());
        if (r.getContentLengthLong() >= 0) env.put("CONTENT_LENGTH", Long.toString(r.getContentLengthLong()));
        return env;
    }
}
