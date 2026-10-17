---
name: bx-jruby-rack
description: "Use this skill when hosting Rack, Sinatra, or Rails applications in BoxLang with bx-jruby-rack: CommandBox startup, servlet gateway setup, streaming/SSE, scoped mounts, Bundler configuration, managed workers, lifecycle, status, and explicit Rake tasks."
---

# bx-jruby-rack: Host Ruby Web Applications in BoxLang

## Installation

Requires BoxLang 1.18.0+, Java 21+, and a Jakarta Servlet 5-compatible host.
The ForgeBox slug is `bx-jruby-rack`; runtime settings and reloads use
`bxjrubyrack`. The package installs its pinned `bx-jruby` dependency, which owns
all JRuby runtimes. The modules are siblings.

```sh
install-bx-module bx-jruby-rack
# CommandBox: from your BoxLang web application's root
box install bx-jruby-rack
```

For CommandBox, copy the installed `examples/commandbox` directory into an app
folder, then run `box install`, `box task run`, and `box server start`. The starter
uses `app.libDirs`, explicit servlet mappings and `web.servletPassPredicate=true`
so extensionless mounts reach the filter. See [deployment](docs/deployment.md).

For generic Servlet hosts, install into their module directory. Copy the installed
module's `gateway/` JAR into `WEB-INF/lib` and register
`modules.bxjrubyrack.gateway.RackGateway` before BoxLang and body-parsing filters.
Map it to `/*` for `REQUEST` dispatches. Keep BoxLang's ordinary extension
mappings, such as `*.bxm`.

For the complete `web.xml` example, read [README.md](https://github.com/tonyjunkes/bx-jruby-rack/blob/main/README.md#install-and-configure-the-gateway).
Set the filter's `boxlang-home` and `boxlang-config-path` to the same values as
the BoxLang servlet. Each servlet context needs a unique fixed `scope`; the
default is `default`. Scopes come from deployment configuration, not request
Host headers.

The gateway owns mounted requests before BoxLang reads their raw bodies. These
requests bypass Application.bx lifecycle callbacks. Boot declarative mounts at
gateway initialization; explicit creation requires an already active gateway.
The module does not automatically inject this filter into arbitrary servers.

## BIFs and App Methods

| Function | Behavior |
| --- | --- |
| `jrubyRack(config)` | Validate, boot and register an app; return its managed handle. |
| `jrubyRackGet(id, scope="default")` | Find a registered app or raise a lookup error. |
| `jrubyRackTask(config, task, args=[])` | Run an explicit Rake task in a fresh core-owned runtime; no HTTP gateway required. |

App methods are Java methods; pass arguments positionally.

| Method | Returns |
| --- | --- |
| `status()` | State, framework versions, worker/queue counts and cumulative metrics; excludes environment values. |
| `configuration()` | Detached resolved settings reusable for tasks or app replacement; includes environment values. |
| `close()` | Removes routing, rejects queued work and drains workers; returns void. |

## Prepare a Ruby App

Provide `Gemfile`, `Gemfile.lock`, and `config.ru` in the app directory. For a
minimal application, declare `gem 'rack', '~> 3.2'` in the Gemfile and use:

```ruby
run ->(env) { [200, {'content-type' => 'text/plain'}, ['Hello from Ruby']] }
```

Install gems before startup using the JRuby bundled by core or a matching JRuby
toolchain. See [README.md](https://github.com/tonyjunkes/bx-jruby-rack/README.md#prepare-your-ruby-application) for bundled
JRuby commands. Use an absolute external bundle directory and the same
`BUNDLE_PATH` during installation and runtime boot. Production defaults to
frozen Bundler setup and requires a lockfile. App boot and requests never install
gems, compile assets, generate frameworks, or run migrations.

Applications supply their own Rack/framework gems. Use pure Ruby or Java-platform
gems; MRI C extensions are unsupported. Verified framework combinations are
listed in [docs/compatibility.md](https://github.com/tonyjunkes/bx-jruby-rack/docs/compatibility.md).

## Declarative Mounts

Configure `boxlang.json`:

```json
{
  "modulesDirectory": ["${boxlang-home}/modules"],
  "modules": {
    "bxjrubyrack": {
      "settings": {
        "apps": [{
          "id": "ruby-app",
          "scope": "default",
          "root": "/srv/ruby-app",
          "mount": "/ruby",
          "runtime": { "env": { "BUNDLE_PATH": "/srv/bundles/ruby-app" } }
        }]
      }
    }
  }
}
```

Replace paths with physical directories on the host; declarative roots must be
absolute. Required boot failures fail gateway initialization. `/ruby` and
`/ruby/...` belong to this mount; `/rubygems` continues through the servlet chain.
A Rack 404 remains a Rack response. The servlet context and mount contribute to
`SCRIPT_NAME`; the remaining raw URL path becomes `PATH_INFO`.

## Explicit Ownership and Tasks

Create an app after its scope's gateway is active:

```js
app = jrubyRack({
    id: "orders", scope: "default",
    root: expandPath("./ruby-app"), mount: "/orders",
    runtime: { env: { BUNDLE_PATH: "/srv/bundles/orders" } }
});
state = app.status();
sameApp = jrubyRackGet("orders", "default");
taskConfig = app.configuration();
app.close();
```

IDs and mounts must be unique within a scope; overlapping mounts are rejected.
`configuration()` returns a copy: editing it does not change the live app.
Treat its environment values as potentially sensitive; use `status()` for
diagnostic endpoints.

Run migrations explicitly, including before HTTP hosting begins:

```js
result = jrubyRackTask({
    id: "rails-migration", root: "/srv/rails-app",
    runtime: { env: {
        BUNDLE_PATH: "/srv/bundles/rails-app", RAILS_ENV: "production"
    } }
}, "db:migrate", []);
print(result.stdout);
```

The task loads `Rakefile`, its imports and the configured bundle. Pass task
arguments as an array of strings; they are structured Rake arguments, not a shell
command. Results contain `value`, `stdout`, and `stderr`. Task failures preserve
the original Ruby cause; normal Ruby exit is reported as failure and embedded
`exit!` is rejected. Execute trusted application code only.

## Configuration and Worker Ownership

`id` and an existing `root` are required. `rackup` defaults to `config.ru`;
relative rackup and Gemfile paths cannot use `..` to escape the root. Explicit
absolute overrides are allowed. Explicit factory roots resolve once from the
caller; runtime paths resolve from the app root.

- `environment` defaults to `production` and generates `RACK_ENV`.
- `bundler.enabled` defaults to whether a Gemfile exists; `bundler.gemfile`
  defaults to `Gemfile`; `bundler.frozen` defaults to true in production.
- `runtime.env` supplies string values and replaces core's default environment
  overlay. Generated `RACK_ENV`, `BUNDLE_GEMFILE`, and `BUNDLE_FROZEN` reject
  conflicting values. `RAILS_ENV` may be supplied explicitly.
- `runtime.gemHome`, `gemPaths`, and `loadPaths` follow core configuration rules.
- `workers` defaults to 1. Every worker owns one JRuby runtime on one dedicated
  thread; additional workers have independent Ruby state and connection pools.
- `queueCapacity` defaults to 32 and `acquireTimeoutMs` to 5000. Queue overflow
  and unstarted-work timeout return 503; timeout does not cancel executing Ruby.
- `maxRequestBodyBytes` defaults to 16777216 and
  `requestMemoryThresholdBytes` to 65536. Larger admitted input spills to a
  temporary file; actual and declared oversized bodies return 413.
- `shutdownGraceMs` defaults to 30000. Close waits up to this budget; continuing
  work remains owned in `DRAINING` until cleanup and runtime termination finish.

Close is idempotent. Module/core unload waits for owned work without forcibly
terminating Ruby. Replace an app by closing and creating it again, allowing for
a downtime window. Measure the actual framework before increasing worker count.

## Response Profile and Troubleshooting

Supported responses are Rack 3 enumerable or callable bodies, including SSE. Use a
mutable `[status, headers, body]` array, lowercase string header names, string
or array header values, and string body chunks. Binary chunks preserve bytes.
Repeated cookies use arrays. Transport framing belongs to the servlet host.
Bodies close after success, failure, HEAD and bodyless responses;
`rack.response_finished` callbacks run in reverse order.

WebSockets, hijacking, upgrades, early hints, asynchronous multiplexing,
dynamic pool resizing, and automatic Ruby reload are outside this profile.
Configure trusted proxies and transport timeouts in the servlet host.

Bridge failures return generic HTTP errors. Consult host logs for causes,
backtraces and separately captured Ruby stdout/stderr. Captures are bounded to
64 Ki characters per stdout/stderr stream per operation and logged after completion, including
failed operations; exit-handler output is logged separately. Output is batch
capture rather than a live tail.

When boot fails, check core activation, the gateway scope, physical app paths,
JRuby-compatible gems, the lockfile, bundle location and framework environment.

## Streaming (1.1.0)

Each SSE/callable response holds one worker. Allocate extra workers or a dedicated
mount for long-lived streams. Callable streams expose buffered reads and raw
writes/flush/close; enumerable SSE chunks auto-flush. Track `status().streams`.
Use `ensure` for producer cleanup, heartbeat writes and disabled proxy buffering.
Drain rejects streaming I/O and waits for cleanup; Ruby that never returns or
performs I/O can remain DRAINING. Read [deployment](docs/deployment.md) and
[capabilities](docs/capabilities.md) before claiming another framework or protocol.
The Rails example uses a public fixture secret and disables CSRF for test API
requests; configure appropriate secrets and request protection in production.
