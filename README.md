# BoxLang JRuby Rack Support

Host Ruby Rack applications inside BoxLang, using **bx-jruby 1.2.0** for runtime ownership. It supports Rack 3 enumerable and callable responses, including SSE, through a Jakarta Servlet gateway. The repository includes locked custom Rack, Sinatra, and Rails/JDBC fixtures.

Requires Java 21+, BoxLang 1.18.0+, and the gateway described below. See [compatibility](docs/compatibility.md) for exact versions and limitations and the [validation record](docs/validation.md) for test results.

For LLM coding agents, [SKILL.md](SKILL.md) describes setup, APIs, ownership and
common deployment pitfalls. It is included in the installed module.

## Installation

Install from ForgeBox using BoxLang's module installer or CommandBox:

```sh
# BoxLang
install-bx-module bx-jruby-rack

# CommandBox: run from your BoxLang web application's root
box install bx-jruby-rack
```

The package installs its tested bx-jruby dependency and includes the compiled
module and servlet gateway. No source checkout, Gradle build, or separate JRuby
installation is needed. Install into the same BoxLang home used by your web host.
For local installations, point the host's `modulesDirectory` at the installed
module directory. See [BoxLang module installation](https://boxlang.ortusbooks.com/getting-started/installation/modules)
for host-specific paths.

To get started, configure the gateway below, prepare a Ruby app with `Gemfile`
and `config.ru`, and add its mount to `boxlang.json`. Then start the servlet host
and visit `/ruby`.

## CommandBox quick start

Install the module from ForgeBox, then copy its packaged `examples/commandbox` folder
into a new application directory. The starter's `box.json` installs both sibling modules.
From the copied directory run:

```sh
box install
box task run
box server start
```

Open http://localhost:8080/ for BoxLang, `/ruby` for Ruby and `/ruby/events` for SSE.
CommandBox 6.2+ is required; the starter pins the verified servlet package
`boxlang@1.18.6+62`, containing BoxLang runtime 1.18.0. Setup explicitly installs the
locked Ruby bundle with core's JRuby and generates the gateway configuration.

Only `public/` is served. Modules, app source, gems and configuration remain outside
it. The starter uses `app.libDirs` for the gateway and `web.servletPassPredicate=true`
to route extensionless Ruby requests through the servlet filter. It supplies explicit
BoxLang servlet mappings and welcome files in the override. Read the
[starter instructions](examples/commandbox/README.md) before customizing it.

## Generic Jakarta Servlet installation

### Install and configure the gateway

Locate `bx-jruby-rack` in the modules directory populated by the installer. Its `gateway/` directory contains the host gateway JAR. The core and Rack modules load together from that installation.

Copy the JAR in the installed module's `gateway/` directory into the servlet
application's `WEB-INF/lib`. Add this filter **before body-parsing filters and
BoxLang** in `WEB-INF/web.xml`, using your host's actual BoxLang home/config paths:

```xml
<filter>
  <filter-name>RubyRack</filter-name>
  <filter-class>modules.bxjrubyrack.gateway.RackGateway</filter-class>
  <init-param><param-name>scope</param-name><param-value>default</param-value></init-param>
  <init-param><param-name>boxlang-home</param-name><param-value>/srv/boxlang-home</param-value></init-param>
  <init-param><param-name>boxlang-config-path</param-name><param-value>/srv/boxlang.json</param-value></init-param>
</filter>
<filter-mapping>
  <filter-name>RubyRack</filter-name>
  <url-pattern>/*</url-pattern>
  <dispatcher>REQUEST</dispatcher>
</filter-mapping>
```

Use the same BoxLang home/config paths for the BoxLang servlet. Keep its normal extension mappings (for example `*.bxm`). Do not map it as a path-prefix servlet (`/*`), which changes its servlet-path interpretation. Unmatched requests continue down the filter chain. Matched Rack 404s remain Rack responses.

The gateway is necessary: BoxLang's form scope may consume the raw body before the early web-executor interception event. An Application.bx `onRequestStart` adapter would be too late. Mounted Rack requests therefore do **not** execute BoxLang application lifecycle callbacks. Configure mounts declaratively, or create them explicitly after the gateway is active.

Each servlet context needs a unique, fixed `scope` string. The scope is an operator configuration, independent of request Host headers. The factory and lookup API accept an explicit scope; there is no implicit Application.bx owner namespace.

## Prepare your Ruby application

Create a `Gemfile` in your Ruby application directory:

```ruby
source 'https://rubygems.org'
gem 'rack', '~> 3.2'
```

Add `config.ru`:

```ruby
run ->(env) { [200, {'content-type' => 'text/plain'}, ["Hello from Ruby"]] }
```

Install the application's gems using JRuby bundled in the installed `bx-jruby`
module. Replace `/path/to/modules` with your host's modules directory:

```sh
cd /srv/ruby-app
java -jar /path/to/modules/bx-jruby/libs/jruby-complete-10.1.2.0.jar -S bundle config set --local path /srv/bundles/ruby-app
java -jar /path/to/modules/bx-jruby/libs/jruby-complete-10.1.2.0.jar -S bundle install
```

Keep the generated `Gemfile.lock` with your app. A matching JRuby toolchain can
also run `bundle` directly. Use an absolute `BUNDLE_PATH` in `runtime.env` for the
external bundle directory. Pure Ruby and Java-platform gems are supported;
MRI C extensions are not. Gems are installed before the server starts.

Configure the BoxLang module:

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
          "environment": "production",
          "runtime": { "env": { "BUNDLE_PATH": "/srv/bundles/ruby-app" } }
        }]
      }
    }
  }
}
```

Declarative roots must be absolute. Apps boot when their gateway initializes; required boot failures fail gateway initialization. Ensure the servlet deployment reports healthy only after filter initialization. The gateway is part of the deployment, not automatically injected into an arbitrary server.

## Explicit applications and tasks

```js
app = jrubyRack({
    id: "another-app", scope: "default",
    root: expandPath("./ruby-app"), mount: "/another",
    runtime: { env: { BUNDLE_PATH: "/srv/bundles/ruby-app" } }
});
status = app.status();
sameApp = jrubyRackGet("another-app", "default");
taskConfig = app.configuration();
app.close();
```

`close()` is idempotent. It removes the mount, rejects queued work, and waits up to the shutdown grace budget. If Ruby is still running, status remains `DRAINING`; the worker stays owned until response cleanup and runtime termination finish. Module/core unload waits for that work without forcefully terminating Ruby. Replace an app with close-and-create; expect a downtime window.

`configuration()` returns a detached copy of the resolved settings, suitable for
`jrubyRackTask(taskConfig, "task_name", [])` or close-and-create replacement.
Editing the copy does not reconfigure the running app. It includes environment
values, so use `status()` for public diagnostics instead.

Run tasks without starting an HTTP server:

```js
config = {
    id: "rails-task", root: "/srv/rails-app",
    runtime: { env: {
        BUNDLE_PATH: "/srv/bundles/rails-app",
        RAILS_ENV: "production"
    } }
};
result = jrubyRackTask(config, "db:migrate", []);
print(result.stdout);
```

Tasks load the application's `Rakefile`, resolve its imports, and invoke the named Rake task with structured string arguments. They run in a fresh, core-managed runtime using the same configuration rules. No shell command is built. Migrations occur only when explicitly called. Normal Ruby `exit` becomes an error and `exit!` is rejected in these runtimes; this is not a sandbox against trusted application code invoking JVM APIs directly.

## Configuration

| Field | Default / behavior |
| --- | --- |
| `id`, `root` | Required; existing root directory |
| `scope` | `default`; registered servlet gateway scope |
| `rackup` | `config.ru`; relative paths stay inside root; explicit absolute overrides allowed |
| `mount` | `/`; segment-boundary match; overlapping mounts in a scope rejected |
| `environment` | `production`; owns `RACK_ENV` |
| `bundler.enabled` | Auto when Gemfile exists |
| `bundler.gemfile` | `Gemfile`, relative to root |
| `bundler.frozen` | True in production; requires Gemfile.lock |
| `runtime.env` | Immutable string map; overrides core's default env map |
| `runtime.gemHome`, `gemPaths`, `loadPaths` | Core options; relative paths resolve from app root |
| `workers` | `1`; one isolated runtime per dedicated thread |
| `queueCapacity` | `32`; additional admitted requests waiting for a worker |
| `acquireTimeoutMs` | `5000`; queue wait only |
| `shutdownGraceMs` | `30000`; never an execution cancellation deadline |
| `maxRequestBodyBytes` | `16777216`; counts actual bytes, including unknown-length input |
| `requestMemoryThresholdBytes` | `65536`; larger bodies spill to temporary files |

Unknown fields, invalid ranges, duplicate IDs, overlapping mounts, and conflicting generated environment keys are rejected. Explicit `RAILS_ENV` is allowed; otherwise Rails can use `RACK_ENV`. The bridge supports one Rack 3.x bundle per worker, selected by the application, and does not globally activate a Rack version.

Admission occurs before reading the body. Queued requests retain servlet connections but do not allocate upload buffers. Overflow/queue timeout returns 503; oversized input returns 413. Response chunks are written incrementally with servlet backpressure. Applications themselves can allocate memory or spawn threads, so body bounds are not a Ruby memory limit.

`status()` exposes state, versions, boot time, worker/admission counts, completed/failed request counts, transferred bytes, and cumulative queue/request time. It excludes environment values and request bodies. Ruby stdout/stderr use separate bounded captures (64 Ki characters each per operation) and are logged after completion. Exit-handler output is logged separately. This is bounded batch logging, not live tailing of an in-progress Ruby operation.

## Deployment limits

Configure transport timeouts and trusted proxies in the servlet host. The bridge uses the host's resolved scheme/address and does not apply its own forwarded-header trust rules. Encoded path/query text is passed through; ambiguous mounts are rejected. Underscore-containing incoming header names are ignored to avoid CGI-name collisions. Repeated Cookie fields use `; ` and other repeated fields use comma joining.

Enumerable and callable Rack 3 bodies are supported, including SSE. Callable streams expose buffered request reads and raw response writes/flush/close; SSE enumerable chunks flush immediately. Each open stream occupies one Ruby worker. WebSockets, hijacking, early hints, protocol upgrades, dynamic resizing, hot reload of Ruby files, and automatic assets/gems/migrations remain outside this release. HEAD and bodyless responses close the body without sending it. Application-returned headers must follow Rack 3 lowercase/array conventions; transfer framing belongs to the servlet container. See [deployment and streaming](docs/deployment.md), [capabilities](docs/capabilities.md), and [validation](docs/validation.md).

Production bridge failures return generic responses. Detailed causes/backtraces remain available in host logs and task exception causes. An error after response commitment fails the transport path; no BoxLang error template is appended. Application-generated error pages are governed by the application's framework configuration.

Start with one worker per app and measure your own workload. Each additional worker boots another complete Rails/Sinatra runtime, with its own Ruby state and connection pools. Deploy precompiled assets separately or explicitly enable the application's static middleware. No asset compilation happens during boot.
