# Deployment and streaming

## CommandBox

Copy the packaged `examples/commandbox` starter, then run `box install`, `box task run`
and `box server start`. Setup explicitly installs the locked bundle and generates
absolute paths; run it again after moving the app. Core 1.2.0 is pinned for both installers.

CommandBox 6.2+ supplies Jakarta hosting. The verified engine is `boxlang@1.18.6+62`
(runtime 1.18.0). `app.libDirs` includes the installed gateway directory; core/JRuby
remain module-private. `web.servletPassPredicate=true` passes extensionless Ruby
requests into the servlet deployment. Otherwise CommandBox's static resource handler
bypasses the filter. Supply explicit BoxLang servlet mappings and welcome files in
the override. See [servlet pass predicates](https://commandbox.ortusbooks.com/embedded-server/multi-site-support/servlet-pass-predicate).

Generic Jakarta Servlet hosts can use `WEB-INF/lib`. The gateway also works above the
webapp loader: it resolves BoxLang through `ServletContext.getClassLoader()`, then
Rack through its module loader. Keep BoxLang/JRuby classes out of the gateway JAR.
Mounts bypass `Application.bx` callbacks and run before form parsing.

## Streaming/SSE

Enumerable bodies take precedence over callable bodies. Callable streams expose
`read`, `write`, `<<`, `flush`, `close`, `close_read`, `close_write` and `closed?`.
`write` returns bytes written; `<<` returns the stream. Reads use the already buffered
HTTP body, without socket/full-duplex access. Callable bodies flush explicitly;
enumerable `text/event-stream` chunks flush immediately. The container owns framing.
HEAD, 204 and 304 suppress consumption but still close the body. Length validation
and `body.close`/`response_finished` cleanup also apply to streaming.

An open stream retains one worker and servlet request until cleanup finishes. Use
a dedicated mount or sufficient workers when mixing SSE and ordinary requests.
Bound `queueCapacity` and `acquireTimeoutMs`. `status().streams` reports active
callable/SSE responses. Blocking transport backpressure bounds output buffering.

Close removes routing/queued work. Further streaming writes/flushes fail or `closed?`
becomes true so Ruby can clean up on its owning worker. Disconnects similarly fail I/O.
Response-finished callbacks run in reverse order after body close. A Ruby loop that
never checks state or performs I/O may remain DRAINING beyond the grace period;
module unload waits for ownership. Active Ruby is never forcibly terminated. Release
producer resources in `ensure` and emit heartbeats while otherwise idle.

## Framework deployment

Supply real secrets: `SESSION_SECRET` for Sinatra and Rails `secret_key_base`/credentials.
Packaged fixtures use public test secrets. CSRF is enabled; clients need a session
cookie and valid token unless an application deliberately defines an authenticated
API policy. Rails' example health endpoint exposes a token for integration tests.

Install Java-platform gems and precompile assets before startup. Tests verify static
and fingerprinted file delivery, not an automatic asset pipeline. Serve only `public/`;
keep Ruby source, lockfiles, credentials, modules and bundles outside it.

Rails 7.2/JDBC SQLite and PostgreSQL are covered. Set `BX_RAILS_DATABASE_URL` to a
`postgresql://` URL for the fixture, or use `BX_RAILS_DATABASE` for SQLite. Each worker
has its own Active Record pool. Budget connections using worker count times Rails
pool size, plus explicit task runtimes and other processes. Run migrations explicitly
through `jrubyRackTask`. Use external durable job processors for reliable background
work; Rack reload and web workers do not provide job supervision.

## Proxies

Rack metadata uses the host-resolved scheme/name/port/remote address. Configure trusted
proxies in the host and framework; strip client-supplied forwarding headers at the edge.
The module adds no blanket trust policy. Disable SSE response buffering at proxies
(for example `proxy_buffering off` in the relevant Nginx location), choose timeouts
compatible with heartbeat intervals, and verify delivery through the actual chain.

## MiniServer follow-up

The [standalone framework examples](https://github.com/tonyjunkes/bx-jruby-rack-examples)
target CommandBox's servlet host. They include explicit local database preparation
and reset commands; their demo identity selector is not authentication. The packaged
CommandBox starter remains the smallest gateway and streaming example.

MiniServer has no verified early module hook in its released handler chain. A future
upstream hook must allow claiming requests before static routing, template lookup
and form parsing, with untouched bytes, raw URI/query, status/headers, byte writes,
flush, disconnect reporting and lifecycle cleanup. `RackRequest`/`RackSink` can bridge
that transport; a Servlet filter alone cannot enable MiniServer hosting.
