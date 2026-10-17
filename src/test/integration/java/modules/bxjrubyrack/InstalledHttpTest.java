package modules.bxjrubyrack;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.DispatcherType;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import modules.bxjrubyrack.gateway.RackGateway;
import org.eclipse.jetty.ee9.servlet.*;
import org.eclipse.jetty.server.*;
import org.junit.jupiter.api.*;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.servlet.BoxLangServlet;

/** Module implementation/core/JRuby are intentionally absent from this test's application classpath. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class InstalledHttpTest {
    static Server server;
    static BoxRuntime runtime;
    static String base;
    static ScriptingRequestBoxContext script;
    static HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static Path railsStreamClosed;

    /**
     * Creates a custom fixture app through the installed BoxLang BIF with explicit overrides.
     *
     * @param id the application identifier within the selected scope
     * @param extra explicit configuration overrides for the installed fixture app
     * @return the app handle loaded by the module's own class loader
     */
    static Object createApp(String id, Map<String, Object> extra) {
        var values = new HashMap<String, Object>(extra);
        values.put("id", id);
        values.put("root", Path.of("src/test/fixtures/custom").toAbsolutePath().toString());
        values.putIfAbsent("mount", "/" + id);
        values.putIfAbsent(
            "runtime",
            Struct.of(
                "env",
                Struct.of("BUNDLE_PATH",
                Path.of("build/bundles/custom").toAbsolutePath().toString())
            )
        );
        script.getScopeNearby(ortus.boxlang.runtime.scopes.VariablesScope.name)
                .put("newConfig", Struct.fromMap(values));
        return runtime.executeSource("jrubyRack(newConfig);", script);
    }

    /**
     * Invokes an installed app's public no-argument method through reflection.
     *
     * @param app the installed app handle from its module class loader
     * @param name the public no-argument app method name
     * @return the method's result
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    static Object invoke(Object app, String name) throws Exception {
        return app.getClass().getMethod(name).invoke(app);
    }

    /**
     * Reads an installed app's status without linking its implementation into the test host.
     *
     * @param app the installed app handle from its module class loader
     * @return the status struct
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    static IStruct status(Object app) throws Exception {
        return (IStruct) invoke(app, "status");
    }

    /**
     * Waits up to the test deadline for an asynchronous condition, failing if it remains false.
     *
     * @param predicate the asynchronous condition to observe
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    static void waitFor(java.util.concurrent.Callable<Boolean> predicate) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!predicate.call()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for condition");
            Thread.sleep(10);
        }
    }

    /**
     * Loads sibling distribution ZIPs, prepares Rails persistence and starts real servlet contexts and gateways.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @BeforeAll
    static void start() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("modules.bxjruby.JRubyRuntime"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("modules.bxjrubyrack.RackRuntime"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.jruby.Ruby"));
        Path root = Path.of("build/http").toAbsolutePath();
        Files.createDirectories(root.resolve("web"));
        railsStreamClosed = root.resolve("rails-stream-" + UUID.randomUUID() + ".closed");
        Files.writeString(root.resolve("web/hello.bxm"), "<bx:output>BoxLang says #6 * 7#</bx:output>");
        String modules = Path.of("build/consumer/modules").toAbsolutePath().toString().replace('\\', '/');
        String appRoot = Path.of("src/test/fixtures/custom").toAbsolutePath().toString().replace('\\', '/');
        String bundle = Path.of("build/bundles/custom").toAbsolutePath().toString().replace('\\', '/');
        String sinatraRoot = Path.of("src/test/fixtures/sinatra").toAbsolutePath().toString().replace('\\', '/');
        String sinatraBundle = Path.of("build/bundles/sinatra").toAbsolutePath().toString().replace('\\', '/');
        Path config = root.resolve("boxlang.json");

        Files.writeString(
                config,
                """
                {"modulesDirectory":["%s"],"modules":{"bxjrubyrack":{"settings":{"apps":[
                  {"id":"custom","root":"%s","mount":"/ruby","runtime":{"env":{"BUNDLE_PATH":"%s"}}},
                  {"id":"sinatra","root":"%s","mount":"/sinatra","runtime":{"env":{"BUNDLE_PATH":"%s"}}}
                ]}}}}
                """
                        .formatted(modules, appRoot, bundle, sinatraRoot, sinatraBundle));
        runtime = BoxRuntime.getInstance(false, config.toString(), root.resolve("home").toString()).waitForStart();
        script = new ScriptingRequestBoxContext(runtime.getRuntimeContext());
        script.getScopeNearby(ortus.boxlang.runtime.scopes.VariablesScope.name)
                .put(
                        "railsConfig",
                        Struct.of(
                                "id",
                                "rails",
                                "root",
                                Path.of("src/test/fixtures/rails").toAbsolutePath().toString(),
                                "mount",
                                "/rails",
                                "runtime",
                                Struct.of(
                                        "env",
                                        Struct.of(
                                                "BUNDLE_PATH",
                                                Path.of("build/bundles/rails").toAbsolutePath().toString(),
                                                "RAILS_ENV",
                                                "production",
                                                "BX_RAILS_DATABASE",
                                                root.resolve("fixture.sqlite3").toString(),
                                                "BX_RAILS_STREAM_CLOSED", railsStreamClosed.toString(),
                                                "BX_RAILS_DATABASE_URL", System.getenv().getOrDefault("BX_RAILS_DATABASE_URL", "")))));
        if (System.getenv("BX_RAILS_DATABASE_URL") == null) {
            ((IStruct) ((IStruct) ((IStruct) script.getScopeNearby(ortus.boxlang.runtime.scopes.VariablesScope.name)
                .get("railsConfig")).get("runtime")).get("env")).remove("BX_RAILS_DATABASE_URL");
        }
        runtime.executeSource("jrubyRackTask(railsConfig, 'db:migrate');", script);
        server = new Server(0);

        var context = new ServletContextHandler();
        context.setContextPath("/ctx");
        context.setBaseResourceAsPath(root.resolve("web"));

        var servlet = new ServletHolder(new BoxLangServlet());
        servlet.setInitParameter("boxlang-home", root.resolve("home").toString());
        servlet.setInitParameter("boxlang-config-path", config.toString());
        context.addServlet(servlet, "*.bxm");
        context.addFilter(new FilterHolder(new RackGateway()), "/*", EnumSet.of(DispatcherType.REQUEST));

        var rootContext = new ServletContextHandler();
        rootContext.setContextPath("/");
        rootContext.addServlet(DefaultServlet.class, "/");

        var rootGateway = new FilterHolder(new RackGateway());
        rootGateway.setInitParameter("scope", "root-scope");
        rootContext.addFilter(rootGateway, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(
                new org.eclipse.jetty.server.handler.ContextHandlerCollection(
                        context.getCoreContextHandler(), rootContext.getCoreContextHandler()));
        server.start();
        runtime.executeSource("railsApp = jrubyRack(railsConfig);", script);
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/ctx";
    }

    /**
     * Verifies real Sinatra routes, binary echo, cookies, redirects and sanitized failures over HTTP.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void realSinatraRoutesCookiesAndRedirects() throws Exception {
        var health = request("/sinatra/", "GET", new byte[0]);
        assertEquals(200, health.statusCode(), text(health));
        assertEquals("Sinatra 4.2.1", text(health));
        byte[] bytes = new byte[] {0, -1, -128, 42};
        assertArrayEquals(bytes, request("/sinatra/echo", "POST", bytes).body());
        var cookie = request("/sinatra/cookie", "GET", new byte[0]);
        assertTrue(cookie.headers().firstValue("set-cookie").orElse("").contains("path=/ctx/sinatra"));
        var redirect = request("/sinatra/redirect", "GET", new byte[0]);
        assertEquals(302, redirect.statusCode());
        assertTrue(redirect.headers().firstValue("location").orElse("").endsWith("/ctx/sinatra/"));
        var failure = request("/sinatra/failure", "GET", new byte[0]);
        assertEquals(500, failure.statusCode());
        assertEquals("Internal server error", text(failure));
    }

    /**
     * Verifies callable I/O, enumerable precedence, first-event flushing, disconnect cleanup and drain.
     * @throws Exception if transport or fixture cleanup fails
     */
    @Test
    void streamingFlushesAndCleansUpOnDisconnectAndDrain() throws Exception {
        Path root = Path.of("build/http/streams").toAbsolutePath();
        Files.createDirectories(root);
        Path gate = root.resolve("gate");
        Path closed = root.resolve("closed");
        Path finished = root.resolve("finished");
        Files.writeString(gate, "hold");
        Files.deleteIfExists(closed);
        Files.deleteIfExists(finished);
        Object app = createApp("streaming", Map.of("queueCapacity", 0, "runtime", Struct.of("env", Struct.of(
            "BUNDLE_PATH", Path.of("build/bundles/custom").toAbsolutePath().toString(),
            "BX_STREAM_GATE", gate.toString(), "BX_STREAM_CLOSED", closed.toString(),
            "BX_STREAM_FINISHED", finished.toString()))));
        try {
            assertEquals("enumerable", text(request("/streaming/both", "GET", new byte[0])));
            byte[] bytes = {0, -1, 65};
            assertArrayEquals(bytes, request("/streaming/stream-io", "POST", bytes).body());
            waitFor(() -> ((Number) status(app).get("active")).intValue() == 0);
            assertEquals("half", text(request("/streaming/stream-half", "POST", "request".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            waitFor(() -> ((Number) status(app).get("active")).intValue() == 0);
            assertEquals(200, request("/streaming/stream-long", "HEAD", new byte[0]).statusCode());
            waitFor(() -> ((Number) status(app).get("active")).intValue() == 0);
            Files.deleteIfExists(closed);
            Files.deleteIfExists(finished);
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/streaming/stream-gated"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) {
                assertEquals(200, response.statusCode());
                assertEquals("data: first", input.readLine());
                assertTrue(Files.exists(gate), "first event must arrive while the producer is still blocked");
                assertEquals(1, ((Number) status(app).get("streams")).intValue());
                assertEquals(503, request("/streaming/state", "GET", new byte[0]).statusCode());
                Files.delete(gate);
                assertEquals("", input.readLine());
                assertEquals("data: second", input.readLine());
                assertEquals("", input.readLine());
                assertNull(input.readLine());
            }
            waitFor(() -> Files.exists(closed) && Files.exists(finished));
            waitFor(() -> ((Number) status(app).get("active")).intValue() == 0);
            Files.delete(closed);
            Files.delete(finished);
            var disconnected = client.send(HttpRequest.newBuilder(URI.create(base + "/streaming/stream-long"))
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            disconnected.body().readNBytes(16);
            disconnected.body().close();
            waitFor(() -> Files.exists(closed) && Files.exists(finished));
            waitFor(() -> ((Number) status(app).get("active")).intValue() == 0);
            Files.delete(closed);
            Files.delete(finished);
            var draining = client.send(HttpRequest.newBuilder(URI.create(base + "/streaming/stream-long"))
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            draining.body().readNBytes(16);
            invoke(app, "close");
            draining.body().close();
            waitFor(() -> Files.exists(closed) && Files.exists(finished));
            assertEquals("CLOSED", status(app).get("state"));
            assertEquals(0, ((Number) status(app).get("streams")).intValue());
        } finally {
            Files.deleteIfExists(gate);
            invoke(app, "close");
        }
    }

    /**
     * Verifies Rails migrations, sessions, routing and persisted records through the installed gateway.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void realRailsPersistsRecordAndRunsExplicitTasks() throws Exception {
        var health = request("/rails/health", "GET", new byte[0]);
        assertEquals(200, health.statusCode(), text(health));
        assertTrue(text(health).contains("7.2.4"));
        assertTrue(text(health).contains("/ctx/rails"));
        String cookie = health.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var csrfMatch = java.util.regex.Pattern.compile("\"csrf\":\"([^\"]+)\"").matcher(text(health));
        assertTrue(csrfMatch.find());
        String csrf = csrfMatch.group(1);
        var next =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/rails/health"))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(text(next).contains("\"visits\":2"), text(next));
        var created =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/rails/notes"))
                                .header("Content-Type", "application/json")
                                .header("Cookie", cookie)
                                .header("X-CSRF-Token", csrf)
                                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"persisted from BoxLang\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, created.statusCode(), text(created));
        var match = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(text(created));
        assertTrue(match.find());
        var fetched = request("/rails/notes/" + match.group(1), "GET", new byte[0]);
        assertTrue(text(fetched).contains("persisted from BoxLang"), text(fetched));
        assertTrue(text(created).contains("/ctx/rails/notes/"), text(created));
        Object app = runtime.executeSource("jrubyRackGet('rails');", script);
        script.getScopeNearby(ortus.boxlang.runtime.scopes.VariablesScope.name)
            .put("resolvedRailsConfig", invoke(app, "configuration"));
        var result =
                runtime.executeSource("jrubyRackTask(resolvedRailsConfig, 'fixture_args', ['a;b', 'quoted value']);", script);
        assertTrue(result.toString().contains("a;b|quoted value"), result.toString());
        var transaction = runtime.executeSource("jrubyRackTask(resolvedRailsConfig, 'fixture_transaction');", script);
        assertTrue(transaction.toString().contains("transaction rolled back"));
        assertThrows(
                RuntimeException.class,
                () -> runtime.executeSource("jrubyRackTask(railsConfig, 'fixture_failure');", script));
        assertEquals(200, request("/rails/health", "GET", new byte[0]).statusCode());
    }

    /**
     * Stops the servlet host and BoxLang runtime after installed-package tests.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @AfterAll
    static void stop() throws Exception {
        if (server != null) server.stop();
        if (runtime != null) runtime.shutdown();
    }

    /**
     * Sends a synchronous HTTP request to the fixture servlet context.
     *
     * @param path the request path relative to the fixture context
     * @param method the HTTP request method
     * @param body the raw binary request body
     * @return the response with raw binary body bytes
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    static HttpResponse<byte[]> request(String path, String method, byte[] body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/octet-stream")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Decodes a response body as UTF-8 for text assertions.
     *
     * @param response the binary response to decode
     * @return the decoded response text
     */
    static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Verifies binary bodies, duplicate cookies, persistent app state and servlet-context mount metadata.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void actualHttpPreservesBinaryBodyCookiesStateAndContext() throws Exception {
        var response = request("/ruby/path?x=a%2Bb&x=two", "GET", new byte[0]);
        assertEquals(200, response.statusCode(), text(response));
        assertTrue(text(response).contains("\"script\":\"/ctx/ruby\""), text(response));
        assertTrue(text(response).contains("\"query\":\"x=a%2Bb&x=two\""), text(response));
        var binary = request("/ruby/binary", "GET", new byte[0]);
        assertArrayEquals(new byte[] {0, -1, -128, 'h', 'e', 'l', 'l', 'o'}, binary.body(), text(binary));
        byte[] upload = new byte[100000];
        new Random(42).nextBytes(upload);
        assertArrayEquals(upload, request("/ruby/echo", "POST", upload).body());
        assertEquals(2, request("/ruby/cookies", "GET", new byte[0]).headers().allValues("set-cookie").size());
        assertEquals(204, request("/ruby/empty", "GET", new byte[0]).statusCode());
        assertFalse(request("/ruby/empty", "GET", new byte[0]).headers().firstValue("content-type").isPresent());
        assertEquals("rack missing", text(request("/ruby/missing", "GET", new byte[0])));
        var head = request("/ruby/binary", "HEAD", new byte[0]);
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertTrue(text(request("/ruby", "GET", new byte[0])).contains("\"path\":\"\""));
        var ordinary = request("/hello.bxm", "GET", new byte[0]);
        assertEquals(200, ordinary.statusCode(), text(ordinary));
        assertTrue(text(ordinary).contains("42"), text(ordinary));
        assertEquals(404, request("/rubygems", "GET", new byte[0]).statusCode());
    }

    /**
     * Verifies Rack input semantics, binary multipart parsing and rejection of unsupported response bodies.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void inputProfileMultipartAndUnsupportedBodies() throws Exception {
        var io = request("/ruby/io", "POST", new byte[] {0, -1, 65, 66});
        assertEquals(200, io.statusCode(), text(io));
        assertEquals("io ok", text(io));
        byte[] file = new byte[] {0, -1, 127, -128};
        var out = new java.io.ByteArrayOutputStream();
        out.write(
                "--BxBoundary\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"bytes.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"
                        .getBytes());
        out.write(file);
        out.write("\r\n--BxBoundary--\r\n".getBytes());
        var multipart =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/ruby/multipart"))
                                .header("Content-Type", "multipart/form-data; boundary=BxBoundary")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, multipart.statusCode(), text(multipart));
        assertArrayEquals(file, multipart.body());
        var unknown =
                client.send(
                        HttpRequest.newBuilder(URI.create(base + "/ruby/echo"))
                                .POST(
                                        HttpRequest.BodyPublishers.ofInputStream(
                                                () -> new java.io.ByteArrayInputStream(file)))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(file, unknown.body());
        for (String method : List.of("PUT", "PATCH", "DELETE", "OPTIONS"))
            assertEquals(200, request("/ruby/echo", method, file).statusCode());
        assertEquals(200, request("/ruby/hooks", "GET", new byte[0]).statusCode());
        String state = text(request("/ruby/state", "GET", new byte[0]));
        assertTrue(state.contains("second:true\",\"first:true"), state);
        var callable = request("/ruby/callable", "GET", new byte[0]);
        assertEquals(200, callable.statusCode());
        assertEquals("callable", text(callable));
    }

    /**
     * Verifies overload and queue timeouts do not release active runtimes during drain.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void boundsQueueTimeoutAndDrainKeepWorkerOwned() throws Exception {
        Path gate = Path.of("build/http/lease.gate").toAbsolutePath();
        Files.writeString(gate, "wait");
        Files.deleteIfExists(Path.of(gate + ".started"));
        var env =
                Struct.of(
                        "BUNDLE_PATH",
                        Path.of("build/bundles/custom").toAbsolutePath().toString(),
                        "BX_GATE",
                        gate.toString());
        Object app =
                createApp(
                        "leases",
                        Map.of(
                                "runtime",
                                Struct.of("env", env),
                                "queueCapacity",
                                1,
                                "acquireTimeoutMs",
                                250,
                                "shutdownGraceMs",
                                20,
                                "maxRequestBodyBytes",
                                16,
                                "requestMemoryThresholdBytes",
                                4));
        try {
            assertEquals(413, request("/leases/echo", "POST", new byte[17]).statusCode());
            var unknown =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base + "/leases/echo"))
                                    .POST(
                                            HttpRequest.BodyPublishers.ofInputStream(
                                                    () -> new java.io.ByteArrayInputStream(new byte[17])))
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(413, unknown.statusCode());
            var held =
                    client.sendAsync(
                            HttpRequest.newBuilder(URI.create(base + "/leases/hold")).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            waitFor(() -> Files.exists(Path.of(gate + ".started")));
            var waiting =
                    client.sendAsync(
                            HttpRequest.newBuilder(URI.create(base + "/leases/state")).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            waitFor(() -> ((Number) status(app).get("queued")).intValue() == 1);
            assertEquals(503, request("/leases/state", "GET", new byte[0]).statusCode());
            assertEquals(503, waiting.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals(1, ((Number) status(app).get("active")).intValue());
            invoke(app, "close");
            assertEquals("DRAINING", status(app).get("state"));
            assertFalse(held.isDone());
            Files.delete(gate);
            assertEquals(200, held.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            waitFor(() -> status(app).get("state").equals("CLOSED"));
            invoke(app, "close");
        } finally {
            Files.deleteIfExists(gate);
            invoke(app, "close");
        }
    }

    /**
     * Verifies separate apps and worker runtimes retain isolated state and exclusive ownership.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void independentAppsAndMultipleWorkersHaveExclusiveState() throws Exception {
        Object first =
                createApp(
                        "isolatedone",
                        Map.of(
                                "workers",
                                2,
                                "runtime",
                                Struct.of(
                                        "env",
                                        Struct.of(
                                                "BUNDLE_PATH",
                                                Path.of("build/bundles/custom").toAbsolutePath().toString(),
                                                "BX_ISOLATED",
                                                "one"))));
        Object second =
                createApp(
                        "isolatedtwo",
                        Map.of(
                                "runtime",
                                Struct.of(
                                        "env",
                                        Struct.of(
                                                "BUNDLE_PATH",
                                                Path.of("build/bundles/custom").toAbsolutePath().toString(),
                                                "BX_ISOLATED",
                                                "two"))));
        try {
            IStruct versions = (IStruct) status(first).get("versions");
            Object rackVersion = versions.get("rack");
            assertEquals("3.2.7", rackVersion);
            versions.put("rack", "caller mutation");
            assertEquals(rackVersion, ((IStruct) status(first).get("versions")).get("rack"));
            var responses = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
            for (int i = 0; i < 20; i++)
                responses.add(
                        client.sendAsync(
                                HttpRequest.newBuilder(URI.create(base + "/isolatedone/state")).GET().build(),
                                HttpResponse.BodyHandlers.ofByteArray()));
            var threads = new HashSet<String>();
            for (var future : responses) {
                String result = text(future.get());
                assertTrue(result.contains("\"env\":\"one\""), result);
                var match = java.util.regex.Pattern.compile("\"thread\":\"([^\"]+)\"").matcher(result);
                assertTrue(match.find());
                threads.add(match.group(1));
            }
            assertEquals(2, threads.size());
            assertTrue(text(request("/isolatedtwo/state", "GET", new byte[0])).contains("\"env\":\"two\""));
            assertNull(System.getenv("BX_ISOLATED"));
            assertThrows(RuntimeException.class, () -> createApp("isolatedone", Map.of()));
        } finally {
            invoke(first, "close");
            invoke(second, "close");
        }
    }

    /**
     * Verifies disconnects and response-body failures run cleanup before worker reuse.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void disconnectAndBodyFailureCloseBeforeWorkerReuse() throws Exception {
        Object app = createApp("disconnect", Map.of());
        try {
            var failed = request("/disconnect/bodyfail", "GET", new byte[0]);
            assertEquals(500, failed.statusCode());
            assertEquals("Internal server error", text(failed));
            assertTrue(text(request("/disconnect/state", "GET", new byte[0])).contains("\"closed\":1"));
            URI target = URI.create(base);
            try (var socket = new java.net.Socket(target.getHost(), target.getPort())) {
                socket.setSoTimeout(5000);
                socket.setSoLinger(true, 0);
                socket.getOutputStream()
                        .write(
                                ("GET /ctx/disconnect/large HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertTrue(socket.getInputStream().read(new byte[1024]) > 0);
            }
            waitFor(
                    () ->
                            ((Number) status(app).get("failures")).intValue() >= 2
                                    && ((Number) status(app).get("active")).intValue() == 0);
            assertTrue(text(request("/disconnect/state", "GET", new byte[0])).contains("\"closed\":2"));
            assertEquals(500, request("/disconnect/exit", "GET", new byte[0]).statusCode());
            assertEquals(200, request("/disconnect/state", "GET", new byte[0]).statusCode());
        } finally {
            invoke(app, "close");
        }
    }

    /**
     * Verifies root mounts, raw URI/query handling and independent servlet-context scopes.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void rootMountUsesEmptyScriptNameAndSeparateGatewayScope() throws Exception {
        Object rootApp = createApp("custom", Map.of("scope", "root-scope", "mount", "/"));
        try {
            var uri = URI.create(base).resolve("/encoded%20path?q=a%2Bb");
            var response =
                    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode(), text(response));
            assertTrue(text(response).contains("\"script\":\"\""), text(response));
            assertTrue(text(response).contains("\"path\":\"/encoded%20path\""), text(response));
            assertTrue(text(response).contains("q=a%2Bb"), text(response));
            assertTrue(text(request("/ruby/path", "GET", new byte[0])).contains("/ctx/ruby"));
            assertThrows(
                    RuntimeException.class,
                    () -> createApp("collision", Map.of("scope", "root-scope", "mount", "/nested")));
        } finally {
            invoke(rootApp, "close");
        }
    }

    /**
     * Verifies malformed statuses, headers and lengths fail safely and still close response bodies.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void invalidResponsesAreRejectedBeforeCommitAndAlwaysClosed() throws Exception {
        Object app = createApp("protocol", Map.of());
        try {
            for (String invalid : List.of("header", "uppercase", "status", "length", "short", "hijack")) {
                var response = request("/protocol/invalid-" + invalid, "GET", new byte[0]);
                assertEquals(500, response.statusCode(), invalid + ": " + text(response));
                assertEquals("Internal server error", text(response));
                assertTrue(response.headers().firstValue("x-injected").isEmpty());
            }
            assertTrue(text(request("/protocol/state", "GET", new byte[0])).contains("\"closed\":6"));
        } finally {
            invoke(app, "close");
        }
    }

    /**
     * Verifies a failed worker releases its mount and stale cleanup cannot remove its replacement.
     *
     * @throws Exception if fixture execution or cleanup fails
     */
    @Test
    void failedWorkerCannotUnregisterReplacement() throws Exception {
        Object failed = createApp("workerfailure", Map.of());
        Object replacement = null;
        try {
            Thread worker = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("bx-rack-workerfailure-0"))
                .findFirst().orElseThrow();
            worker.interrupt();
            waitFor(() -> status(failed).get("state").equals("FAILED"));
            invoke(failed, "close");
            replacement = createApp("workerfailure", Map.of());
            invoke(failed, "close");
            assertEquals("READY", status(replacement).get("state"));
            assertEquals(200, request("/workerfailure/state", "GET", new byte[0]).statusCode());
            assertSame(replacement, runtime.executeSource("jrubyRackGet('workerfailure');", script));
        } finally {
            invoke(failed, "close");
            if (replacement != null) invoke(replacement, "close");
        }
    }

    /**
     * Verifies failed multiworker startup leaves the ID and mount available for a corrected app.
     *
     * @throws Exception if fixture execution or cleanup fails
     */
    @Test
    void failedStartupReleasesIdAndMount() throws Exception {
        assertThrows(RuntimeException.class,
            () -> createApp("bootfailure", Map.of("workers", 2, "rackup", "missing.ru")));
        Object replacement = createApp("bootfailure", Map.of());
        try {
            assertEquals(200, request("/bootfailure/state", "GET", new byte[0]).statusCode());
        } finally {
            invoke(replacement, "close");
        }
    }

    /**
     * Verifies core reload waits for active work and the servlet gateway rebinds after Rack reload.
     *
     * @throws Exception if fixture setup, execution or cleanup fails
     */
    @Test
    void zCoreReloadDrainsActiveWorkAndGatewayRebindsRack() throws Exception {
        Path gate = Path.of("build/http/reload.gate").toAbsolutePath(),
                marker = Path.of("build/http/at-exit.txt").toAbsolutePath();
        Files.writeString(gate, "wait");
        Files.deleteIfExists(Path.of(gate + ".started"));
        Files.deleteIfExists(marker);
        Object app =
                createApp(
                        "reloadlease",
                        Map.of(
                                "shutdownGraceMs",
                                20,
                                "runtime",
                                Struct.of(
                                        "env",
                                        Struct.of(
                                                "BUNDLE_PATH",
                                                Path.of("build/bundles/custom").toAbsolutePath().toString(),
                                                "BX_GATE",
                                                gate.toString(),
                                                "BX_AT_EXIT",
                                                marker.toString()))));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var held =
                    client.sendAsync(
                            HttpRequest.newBuilder(URI.create(base + "/reloadlease/hold")).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            waitFor(() -> Files.exists(Path.of(gate + ".started")));
            var reload =
                    executor.submit(
                            () -> runtime.getModuleService().reload(ortus.boxlang.runtime.scopes.Key.of("bxjruby")));
            waitFor(() -> status(app).get("state").equals("DRAINING"));
            assertFalse(reload.isDone());
            assertFalse(Files.exists(marker));
            Files.delete(gate);
            assertEquals(200, held.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            reload.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("CLOSED", status(app).get("state"));
            assertTrue(Files.readString(marker).startsWith("bx-rack-reloadlease-"));
            runtime.getModuleService().reload(ortus.boxlang.runtime.scopes.Key.of("bxjrubyrack"));
            assertEquals(200, request("/ruby/state", "GET", new byte[0]).statusCode());
            assertEquals(200, request("/sinatra/", "GET", new byte[0]).statusCode());
        } finally {
            Files.deleteIfExists(gate);
            executor.shutdown();
            invoke(app, "close");
        }
    }
    /**
     * Exercises real HTML forms and framework CSRF middleware, uploads, assets, and Live/Sinatra streaming.
     * @throws Exception if HTTP or fixture operations fail
     */
    @Test
    void realFrameworkHtmlFormsAssetsUploadsAndStreaming() throws Exception {
        for (String framework : List.of("sinatra", "rails")) {
            String formPath = framework.equals("rails") ? "/rails/" : "/sinatra/form";
            var form = request(formPath, "GET", new byte[0]);
            assertEquals(200, form.statusCode(), text(form));
            String cookie = form.headers().allValues("set-cookie").stream()
                .map(value -> value.split(";", 2)[0]).collect(java.util.stream.Collectors.joining("; "));
            var token = java.util.regex.Pattern.compile("name=\"authenticity_token\"[^>]*value=\"([^\"]+)\"").matcher(text(form));
            assertTrue(token.find(), text(form));
            String csrf = token.group(1);
            String uploadCsrf = token.find() ? token.group(1) : csrf;
            var bad = client.send(HttpRequest.newBuilder(URI.create(base + "/" + framework + "/form"))
                .header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("text=forbidden")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(framework.equals("rails") ? 422 : 403, bad.statusCode(), text(bad));
            var saved = client.send(HttpRequest.newBuilder(URI.create(base + "/" + framework + "/form"))
                .header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("text=html-note&authenticity_token=" + URLEncoder.encode(csrf, java.nio.charset.StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(303, saved.statusCode(), text(saved));
            assertTrue(saved.headers().firstValue("location").orElseThrow().contains("/ctx/" + framework));
            String nextCookie = saved.headers().firstValue("set-cookie").map(value -> value.split(";", 2)[0]).orElse(cookie);
            var shown = client.send(HttpRequest.newBuilder(URI.create(base + formPath)).header("Cookie", nextCookie).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(text(shown).contains("html-note"), text(shown));
            String boundary = "bxFrameworkUpload";
            String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"authenticity_token\"\r\n\r\n" + uploadCsrf
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"sample.txt\"\r\nContent-Type: text/plain\r\n\r\nuploaded\r\n--" + boundary + "--\r\n";
            var upload = client.send(HttpRequest.newBuilder(URI.create(base + "/" + framework + "/upload"))
                .header("Cookie", cookie).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, upload.statusCode(), text(upload));
            assertEquals("uploaded", text(upload));
            String asset = framework.equals("rails") ? "example-6fb31f.css" : "example.css";
            assertEquals(200, request("/" + framework + "/assets/" + asset, "GET", new byte[0]).statusCode());
            var events = client.send(HttpRequest.newBuilder(URI.create(base + "/" + framework + "/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(events.body()))) {
                assertEquals(200, events.statusCode());
                assertEquals("data: 0", reader.readLine());
                assertEquals("", reader.readLine());
                assertEquals("data: 1", reader.readLine());
                assertEquals("", reader.readLine());
                assertNull(reader.readLine());
            }
        }
        assertFalse(Files.exists(railsStreamClosed), "finite responses must not publish the disconnect marker");
        var live = client.send(HttpRequest.newBuilder(URI.create(base + "/rails/events?infinite=1")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        live.body().readNBytes(32);
        live.body().close();
        waitFor(() -> Files.exists(railsStreamClosed));
        assertEquals("closed", Files.readString(railsStreamClosed));
    }

}
