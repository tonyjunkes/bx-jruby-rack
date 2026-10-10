# bx-jruby-rack: Architecture, Requirements, and Agent Implementation Plan

**Status:** Proposed implementation specification; APIs below are not implemented.
**Prepared:** 2026-10-09
**Core repository:** https://github.com/tonyjunkes/bx-jruby
**Inspected core revision:** `3cc95f3d74a02bfdb8db1cfee829de25d8e66de5`
**Audience:** An implementation agent working on BoxLang modules and embedded JRuby.

## 1. Objective and agreed direction

Build an optional BoxLang module, `bx-jruby-rack`, that hosts JRuby-compatible Rack applications through BoxLang's HTTP environment. A developer should install the module, provide an application root containing `config.ru`, configure a mount, and serve the application without starting a separate Ruby HTTP server.

Keep `bx-jruby` focused on Ruby interoperability: evaluation, persistent sessions, Java interop, value conversion, and runtime/gem configuration. The Rack module depends on core and owns web hosting, Bundler bootstrapping, HTTP translation, application lifecycle, runtime scheduling, and framework compatibility.

The project name remains **bx-jruby**, not bx-ruby. Earlier discussion used bx-ruby generically; this specification follows the actual repository naming.

Framework support belongs in the Rack module by default. Include generic capabilities and small framework helpers when useful. Create a framework-specific module only when substantial extra integration or dependencies require a separate opt-in, such as a future Rails ActiveRecord–BoxLang ORM bridge. Running Rails, configuring its environment, and executing migrations do not by themselves justify another module.

### Architectural principles

1. Host applications through the Rack interface rather than implementing Rails or Sinatra routing in Java.
2. Prefer conventional `config.ru` and Gemfile discovery, with explicit overrides.
3. Prove framework compatibility with real applications and installed distributions.
4. Preserve existing core APIs and ordinary evaluation behavior.
5. Keep application framework gems outside the module's bundled dependencies.
6. Offer a simple default with explicit controls for advanced lifecycle and deployment needs.
7. Report unsupported capabilities clearly; never imply that every Rack application will work merely because a framework implements Rack.

## 2. Evidence and constraints from the existing core

The following findings come from the inspected repository, not from an implementation of Rack support:

| Area | Existing behavior | Consequence for this project |
| --- | --- | --- |
| Runtime baseline | BoxLang 1.18.0+, Java 21+, bundled JRuby 10.1.2.0 | Begin with these versions; recheck supported combinations before release. |
| Public APIs | `jrubyEval`, `jrubyEvalFile`, `jrubySession`, `bx:jruby` | Preserve names, signatures, output capture, and ownership semantics. |
| Sessions | `RubySession` lazily creates a `ScriptingContainer` using `LocalContextScope.SINGLETHREAD`; public operations synchronize | Do not infer safe concurrent Rack use or correct worker affinity from synchronization alone. |
| Configuration | Working directory, load paths, gem home, gem paths | Runtime-local arbitrary environment configuration is an additive requirement. |
| Environment setup | A new environment map starts from `System.getenv()` and applies gem settings before runtime initialization | Extend this mechanism instead of mutating JVM-wide properties or process environment. |
| Value conversion | Ruby strings convert to Java strings; custom Ruby values use session-owned handles | HTTP bytes and IO need a dedicated bridge; ordinary conversion is unsuitable for binary responses. |
| Capture | Each session operation replaces stdout/stderr writers and returns captures | Long-lived web hosting needs a deliberate logging strategy and bounded captures. |
| Ownership | `RubyManager` tracks sessions; module unload closes them | Rack runtime ownership must participate in core shutdown and respect unload ordering. |
| Close | New work is rejected and existing synchronized work is allowed to finish; no forced cancellation | Drain and timeout behavior must acknowledge non-cooperative Ruby code. |
| Packaging | Gradle, ForgeBox module ZIP, compiled JARs, packaged consumer checks, JUnit and TestBox | Follow this delivery pattern and prove cross-module loading from installed ZIPs. |

Relevant inspected files:

- `README.md`, `box.json`, `gradle.properties`, `build.gradle`.
- `src/main/bx/ModuleConfig.bx`.
- `src/main/java/modules/bxjruby/{JRubyRuntime,RubyManager,RubySession,RubyOptions,RubyValues}.java`.
- `src/test/java/modules/bxjruby/RubySessionTest.java` and packaged-consumer configuration.

An agent must reread these at its actual checkout. The baseline may change. No BoxLang HTTP interception API, classloader dependency facility, or framework version combination is declared verified by this document.

## 3. Module boundaries and repository organization

| Component | Owns | Must not own |
| --- | --- | --- |
| `bx-jruby` / runtime name `bxjruby` | JRuby distribution, runtime creation/termination, generic environment options, optional extension SPI, existing eval/session APIs | HTTP mounts, Rack response rules, Rails boot conventions, framework dependency installation |
| `bx-jruby-rack` / proposed runtime name `bxjrubyrack` | App registry, Rack boot, Bundler setup, web adapters, scheduling, lifecycle, task execution, framework examples | Another JRuby distribution, alternative eval implementation, application database schema management |
| Application bundle | Rack/framework gems, Gemfile/lockfile, Ruby code, framework configuration, DB adapters, assets | BoxLang module internals |

Default to a separate `bx-jruby-rack` repository and ForgeBox package, with narrowly scoped additive changes in the existing core repository. Independent packaging matters more than whether development later uses a monorepo. Do not reorganize core as part of this work unless a demonstrated build requirement demands it.

Declare core as an install-time and runtime dependency using mechanisms verified against BoxLang and ForgeBox. Publish a minimum compatible core version once the extension mechanism is settled. Test missing, disabled, and incompatible core dependencies.

**Classloading requirement:** The installed Rack module must resolve the core's types and JRuby through supported module dependency mechanisms. Do not put a second `jruby-complete` JAR or copied core classes in the Rack ZIP. Compile-time dependency declarations alone do not establish runtime visibility. Never rely on a developer's test classpath to hide packaging failures.

## 4. Release scope

### Proof of concept

- One explicitly managed app, one persistent runtime, one verified BoxLang web host.
- Load a custom `config.ru`, then a bundled Sinatra fixture with preinstalled gems.
- Serve actual HTTP GET and POST requests, status, headers, cookies, and raw body bytes.
- Preserve Ruby application state across requests and release resources on close.
- Demonstrate the installed two-module distribution, not only direct Java invocation.

### v1 requirements

- Explicit app creation plus declarative module settings and automatic lifecycle management.
- Multiple registered apps with independent roots, bundles, environment maps, and workers.
- Validated mounts and correct coexistence with BoxLang pages/routes.
- Synchronous Rack HTTP hosting with bounded request storage and backpressure.
- Fixed worker pools, defaulting to one exclusive runtime per app.
- Bundler-aware boot and meaningful startup diagnostics without automatic installation.
- Tested custom Rack, Sinatra, and at least one actual Rails configuration.
- Generic app task execution, including a tested Rails migration workflow.
- Graceful draining, cleanup, metrics/status, logging, packaged checks, and documentation.

The initial required response profile is **enumerable Rack bodies for synchronous HTTP**. If the selected Rack version also defines callable/streaming bodies, implement them only after validating its full stream contract; otherwise publish the exact restricted profile and reject unsupported body types before response commitment where possible. This limitation must be visible in the compatibility documentation, not hidden behind a blanket Rack-conformance claim.

### Deferred capabilities

- WebSockets, connection hijacking, protocol upgrades, async callable bodies, SSE guarantees, HTTP early hints, HTTP/2 server push.
- Shared concurrent calls into one JRuby runtime, dynamic pool resizing, and automatic hot reload.
- Automatic gem installation, framework generation, database migration at startup, and asset compilation.
- Transparent BoxLang/Ruby session sharing, ORM integration, native MRI extension support, and untrusted-code sandboxing.
- Hosting in non-web runtimes, such as CLI-only or Lambda environments, unless explicitly added and tested later.

Do not silently advertise these capabilities through Rack environment keys. Ordinary finite, chunked body iteration is part of v1; indefinite streaming and async transport support require a separate design.

## 5. Required discovery and decision records

Before production implementation, record brief architecture decisions with the exact dependency versions and source references:

| Decision | Required investigation | Preferred outcome |
| --- | --- | --- |
| HTTP entry point | BoxLang web lifecycle/interception, raw request/response availability, path rewrites, body parsing timing | A supported early dispatch hook plus a tested explicit gateway fallback |
| Runtime extension | Existing `RubySession` operations versus an additive extension SPI | Smallest generic interface that preserves ownership, byte access, and atomic dispatch |
| Worker execution | `SINGLETHREAD` behavior, Java thread affinity, Ruby teardown and classloader context | Dedicated executor thread per exclusive runtime worker |
| Rack profile | Exact installed gem specification and `Rack::Lint` behavior | One pinned compatible Rack 3.x baseline if feasible; version-specific adapters only when tested |
| Existing JRuby-Rack | Released artifact, Servlet namespace, Rack baseline, core runtime ownership | Reuse only if it meets the chosen constraints; otherwise a small direct Rack bridge |
| Dependency visibility | BoxLang module loader and ForgeBox dependency declaration | One core-owned JRuby distribution with packaged cross-module tests |
| Rails baseline | Bundler resolution against bundled JRuby's Ruby version and chosen JDBC adapter | A reproducible, locked Rails HTTP + persistence fixture |

JRuby-Rack is a relevant implementation reference, not an automatically suitable dependency. Its documentation retrieved during planning describes Rack 2.2 and Servlet API differences across release lines. Recheck the current released artifacts: development branches and planned Jakarta support are not proof of compatibility with this host.

If a key host capability cannot be implemented through existing extension points, document the exact missing API and a minimal upstream change. Do not compensate with brittle reflective access, private fields, or deployment claims that were not tested.

## 6. Proposed developer API

Names and examples are **design targets**. Final public APIs must follow validated BoxLang conventions. Session/app methods exposed as Java methods use positional arguments.

### Explicit application ownership

```javascript
// Application.bx sketch: host dispatch registration is handled by the Rack module.
class {
    this.name = "MyBoxLangApp";

    function onApplicationStart() {
        application.rubyApp = jrubyRack({
            id: "ruby-app",
            root: expandPath("./ruby-app"),
            mount: "/ruby",
            environment: "development"
        });
        return true;
    }

    function onApplicationEnd(applicationScope) {
        applicationScope.rubyApp.close();
    }
}
```

The agent must verify the actual web lifecycle signatures and dispatch timing before presenting this as working code. A mount attached to a BoxLang application must be registered only within that application's host scope. The early routing path must still establish any owner application context required for application-managed mounts. If this is impossible, provide an explicit gateway pattern and document the lifecycle order.

### Public surface

| Proposed entry point | Contract |
| --- | --- |
| `jrubyRack(config)` | Validate, boot, register, and return a managed app handle; fail without leaving a mount/runtime behind. |
| `jrubyRackGet(id)` | Return a handle in the current host/owner scope or raise a clear lookup error. |
| `app.status()` | Return state, versions, worker/queue counts, and sanitized diagnostics. |
| `app.close()` | Idempotently stop admission, drain, unregister, and release resources. |
| `jrubyRackTask(config, task, args=[])` | Run an explicit app task in a separate temporary runtime using the same resolved bundle and environment. |

Avoid adding restart, arbitrary mutation, and async job APIs in v1. Use close-and-create as the documented application replacement mechanism, with a clear downtime window. Provide an internal normalized request/response adapter for testing; HTTP dispatch need not be a public BIF.

Task configuration can be obtained from a managed app's immutable resolved configuration or supplied directly, so deployments can run migrations before starting HTTP hosting. Do not require a running web server for tasks.

### Declarative startup

```json
{
  "modules": {
    "bxjrubyrack": {
      "enabled": true,
      "settings": {
        "apps": [
          {
            "id": "ruby-app",
            "root": "./ruby-app",
            "mount": "/ruby",
            "environment": "production",
            "workers": 1
          }
        ]
      }
    }
  }
}
```

Resolve module-configured relative roots from the configuration file's documented base; never from whichever request first reaches a mount. Explicit factory roots resolve against the caller once, then become immutable physical paths. Verify that the config base is available through a supported API; require an absolute root if it is not.

Declarative apps start when both core and the web adapter are ready and stop on module unload. Failed required app startup fails readiness rather than leaving an apparently healthy mount. Declaring HTTP mounts in a host without the required web adapter produces a clear configuration error. An empty apps array leaves core evaluation unaffected.

### Configuration contract

| Field | Proposed default | Rules |
| --- | --- | --- |
| `id` | Required | Unique within owner/host scope; never derived from an arbitrary request. |
| `root` | Required | Existing directory; resolve once. |
| `rackup` | `config.ru` | Relative to root; existing file; escape outside root requires explicit absolute configuration. |
| `mount` | `/` | Normalize once; root mount uses empty Rack script name; validate collisions. |
| `environment` | `production` | Explicit runtime `RACK_ENV`; Rails helper may default `RAILS_ENV` to the same value. |
| `bundler.enabled` | Auto when Gemfile exists | Explicit true requires a Gemfile; false intentionally disables setup. |
| `bundler.gemfile` | `Gemfile` | Relative to root; runtime-local `BUNDLE_GEMFILE`. |
| `bundler.frozen` | true in production | Preserve lockfile; require it for the reproducible production profile. |
| `runtime.gemHome`, `runtime.gemPaths`, `runtime.loadPaths` | Core-compatible defaults | Resolve from root and document inheritance/override semantics. |
| `runtime.env` | `{}` | Immutable map of string keys/values, applied before initialization. |
| `workers` | `1` | Fixed positive number of fully booted, exclusive runtimes. |
| `queueCapacity` | `32` | Bounded per app; overflow fails fast. |
| `acquireTimeoutMs` | `5000` | Maximum queue wait, not a Ruby execution cancellation guarantee. |
| `shutdownGraceMs` | `30000` | Drain budget; expiration leaves active work tracked until completion. |
| `maxRequestBodyBytes` | `16777216` | Enforced for declared and unknown/chunked lengths. |
| `requestMemoryThresholdBytes` | `65536` | Spill larger input to a private tempfile; clean up on every exit path. |

These numeric defaults are starting choices, not benchmark results. Validate ranges and unknown fields. Report conflicting environment sources rather than silently overriding them: `environment`, gem paths, and Bundler fields own their corresponding generated environment keys; arbitrary `runtime.env` must not redefine those keys inconsistently. Explicit Rails environment overrides must be deliberate and documented.

## 7. Core extension and runtime isolation

First prototype the Rack bridge using existing sessions with a Ruby helper and Java request/response proxies. A helper can call the Rack app, write raw byte chunks through a dedicated sink, close the body, and return a simple metadata result in one serialized operation. This may avoid exposing JRuby internals.

If existing APIs cannot provide correct atomic IO, lifecycle, or output handling, add a small generic extension SPI. Its exact Java signatures are a discovery deliverable, not mandated here. Required properties:

- Runtime creation with fixed directory, classloader, load paths, gems, and environment before the first Ruby operation.
- Extension-owned persistent runtimes tracked by core.
- One exclusive operation spanning Rack call, body iteration, callbacks, and cleanup.
- Byte-safe IO without changing existing `RubyValues` string conversion.
- Output/log sink selection for extension runtimes; existing eval captures retain their behavior.
- Deterministic ownership checks, closing state, exception propagation, and reload invalidation.
- No public unrestricted access that allows extensions to bypass ownership accidentally.

Add `env` as a generic core runtime option if required. Absent configuration preserves today's inherited environment behavior. Explicit option maps replace corresponding module-default maps, matching existing override semantics; overlay the resolved map onto a fresh inherited environment for that runtime. Validate values and test two sessions with different values simultaneously.

Never use process-wide `System.setProperty`, shared environment mutation, or process-wide working-directory changes for per-app configuration. Verify embedded JRuby environment/current-directory behavior under simultaneous apps; these runtimes are not security sandboxes and cannot isolate every JVM-level side effect.

Each app worker boots its own Ruby app object exactly once. One admitted request exclusively owns that worker until output and cleanup finish. Use a dedicated worker thread from initialization through termination to avoid assuming `SINGLETHREAD` can be migrated safely across servlet threads. Request streams may be prepared on the request thread and passed through a tested adapter; retain no request context after completion.

Restore thread context classloaders in `finally`. Ruby handles never cross workers, apps, reloads, or task runtimes. Framework background threads must be covered by documented shutdown hooks; runtime termination alone is not evidence that an arbitrary framework stopped all its threads.

## 8. Application bootstrap and Bundler

Boot in this order:

1. Resolve and validate app config and host ownership; reserve but do not activate its mount.
2. Create each worker with the core-provided runtime configuration.
3. Apply runtime-local `RACK_ENV`, optional `RAILS_ENV`, gem locations, and `BUNDLE_GEMFILE` before requiring RubyGems/Bundler/framework code.
4. Run any explicit trusted initialization hook needed before framework boot. Hooks are server configuration, never request input.
5. If enabled, require `bundler/setup` and activate the selected bundle before requiring Rack.
6. Require Rack and load the configured rackup using the API appropriate to the pinned version. Capture its callable application; do not start a Rack server.
7. Apply test/development linting and optional documented framework helpers.
8. Mark workers ready and activate the mount only after all required workers boot.

On any failure, close all created workers, release the reservation, preserve root cause/backtrace, and redact secrets. Missing gems, unsupported Ruby requirements, and native-extension failures must be distinguishable from a generic boot failure.

Dependency installation is a deployment step using a compatible JRuby toolchain. Document exact commands and gem/bundle locations after validation. Do not assume a developer's MRI-installed bundle works in JRuby. Include the Java platform in locked fixture bundles where required, and use JRuby-compatible database adapters.

Never download gems during an HTTP request or production startup. Do not globally force a Rack gem version that conflicts with the application's bundle. Unsupported Rack major versions produce a precise startup error.

## 9. HTTP integration and mounts

Separate transport-neutral Rack translation from host-specific request/response access. v1 needs one concrete, supported BoxLang web host; record its exact implementation and versions. CommandBox/Servlet hosting is the preferred first investigation target. MiniServer support requires its own actual HTTP tests and must not be inferred from a common BoxLang runtime.

Mount dispatch must happen before template output and request-body consumption. If BoxLang eagerly parses forms or multipart data before the available hook, implement a supported earlier raw-body capture or explicit gateway. Reconstructing bytes from parsed form scopes is unacceptable.

For prefix `/ruby`, handle `/ruby` and `/ruby/...`, but not `/rubygems`. Use segment boundaries and reject ambiguous/overlapping mounts within one owner scope in v1. Distinct applications/virtual hosts may reuse the same mount through separate namespaces. Root mounts own all eligible paths in that scope. Decide and test exact behavior for `/ruby` versus `/ruby/` without a redirect that changes POST semantics.

Unmatched requests continue through BoxLang normally. Matched requests are fully owned by Rack; a Rack 404 is not a signal to rerun BoxLang. Do not append `.html`, reinterpret framework routes as templates, or execute a BoxLang template after a Rack response.

### Request environment

Construct a fresh native Ruby hash per request with string keys. Populate CGI fields and Rack IO/capability fields according to the **pinned** specification. Keep transport metadata separate from application parameters.

| Concern | Required adapter behavior |
| --- | --- |
| Mount path | Derive `SCRIPT_NAME` and `PATH_INFO` from host context plus module mount; test non-root servlet contexts. |
| Query | Preserve raw query text; do not decode and reencode it through BoxLang scopes. |
| Headers | Correct CGI header mapping and version-appropriate handling of repeated fields. |
| Body | Provide raw bytes through a Ruby IO-compatible adapter; no character conversion. |
| Scheme/host | Use validated transport metadata and the selected host's trusted-proxy policy; never trust arbitrary forwarded headers. |
| Errors | Provide a Ruby-compatible error IO mapped to app logs. |
| Capabilities | Advertise only facilities actually implemented for that Rack version and host. |

For mount `/ruby` and request `/ruby/orders?open=1`, outside an additional context root, the target mapping is `SCRIPT_NAME=/ruby`, `PATH_INFO=/orders`, `QUERY_STRING=open=1`. At root `/`, `SCRIPT_NAME` is empty.

Do not put cookies/form/session scope objects into reserved Rack environment fields. Optional Java host objects should use a unique extension namespace and be exposed only if a concrete feature requires them.

### Input IO and resource limits

Implement the selected Rack input contract against bytes, including EOF and optional buffer arguments. Provide rewind through bounded memory/tempfile spooling for compatibility even where a selected Rack version does not require it. Test Rack's own multipart parsing against this IO.

Check declared content length early, then count actual bytes so unknown lengths cannot bypass limits. Reject oversized input with 413. Avoid reading unbounded uploads from queued requests; admission and input preparation must share an explicit resource policy. Delete private tempfiles after successful responses, Ruby errors, timeout in the queue, client disconnect, and shutdown cancellation of queued work.

### Response adapter

Consume the returned status/headers/body inside the worker's exclusive operation. Use a byte sink for Ruby string bytes; do not route chunks through `asJavaString()` and BoxLang text output.

- Validate the selected Rack response shape and sanitize transport-level header injection before commit.
- Preserve repeated response headers and separate `Set-Cookie` values.
- Apply version-specific header conventions; do not implement Rack 2 newline-valued headers as though they were Rack 3 arrays.
- Respect HEAD and bodyless-status behavior; do not manufacture invalid content-length/content-type combinations.
- Write finite enumerable chunks incrementally with transport backpressure rather than collecting the entire response.
- Invoke body close and applicable response-finished hooks according to the selected Rack version, including failures and disconnects.
- Keep ownership until cleanup ends. A response body handle must not survive worker release.
- Avoid double compression or chunk framing; let the underlying host own transfer framing.

If output already committed when Ruby fails, log and terminate the transport's response path appropriately; do not write an HTML error page into a partial file/JSON stream. If the response is still uncommitted, map bridge failures to a generic 500. App-returned valid statuses remain application decisions.

## 10. Scheduling, lifecycle, and errors

Application states: `NEW -> STARTING -> READY -> DRAINING -> CLOSED`; failed boot ends in `FAILED` after resource cleanup. Only READY apps accept new work. Make transitions and registry operations atomic.

With fixed workers, admission uses a bounded queue and exclusive leases. Queue overflow or acquire timeout returns 503 with a stable error code and optional Retry-After. Account for disconnected queued clients. A caller timing out does not mean a still-running Ruby request stopped: never make its worker available early.

Do not promise hard Ruby execution timeouts. Java interruption and Future cancellation are not sufficient proof of safe termination. v1 may log slow requests and stop accepting work during shutdown, but stuck code may require process-level intervention.

Closing an app removes it from routing/admission, cancels queued requests with controlled responses, drains active leases, runs framework cleanup hooks, and terminates workers. If the grace budget expires, retain ownership of active runtimes until they finish and expose the pending drain state. Do not force termination while code is still executing or claim resources are released prematurely.

Core unload must occur after dependent Rack cleanup where the host supports dependency ordering. Otherwise register a generic pre-close notification in core. Test actual unload/reload behavior, including active requests and Ruby `at_exit` output, without acquiring locks in an order that deadlocks.

Use structured errors for configuration, missing dependencies, boot, unsupported capability, queue saturation, closed app, task failure, and request/response bridge failure. Retain Ruby cause/backtrace in logs and trusted programmatic errors; production HTTP responses exclude filesystem paths, source, and secrets. Framework-produced error pages follow the framework's own environment settings.

Status/metrics should cover app state, boot duration, loaded versions, active/available workers, queue depth, queue wait, request duration, failures, and bytes transferred. Exclude environment values and request bodies by default. Keep stdout/stderr distinct from HTTP response bytes. Bound captured task output and persistent log buffering.

## 11. Framework compatibility and app tasks

| Framework | Planned route | Evidence required |
| --- | --- | --- |
| Custom Rack | Standard `config.ru` | HTTP and protocol fixture suite under pinned `Rack::Lint` |
| Sinatra | App-owned gems plus rackup; prevent framework self-hosting | Routing, POST, cookie, redirect, error, and mounted URL fixture |
| Rails | App-owned gems plus rackup; small environment/logging helpers only where needed | Real boot, controller request, mount/asset URLs, session cookie, DB round trip, task/migration |
| Roda | Same generic contract | Optional follow-up smoke suite before claiming tested support |
| Hanami | Only a selected JRuby/Rack-compatible version/configuration | Optional follow-up boot and HTTP suite before claiming tested support |

A compatibility matrix must identify exact Java, BoxLang host, JRuby, Rack, Bundler, framework, and DB-adapter versions. Record separately: tested, expected from the generic contract, unsupported, and unknown. The Rails v1 gate cannot be satisfied by a Ruby class named Rails or a mocked framework.

Rails DB support should use a validated JDBC/JRuby adapter combination. Select a reproducible local/test DB or CI service based on that combination; do not assume MRI SQLite dependencies will load. Exercise an actual persisted record plus an explicit migration task. Asset generation is performed ahead of deployment; document serving prebuilt assets through Rails middleware, a correctly configured public mount, or a reverse proxy.

Generic task execution must use the same resolved root, environment, and Bundler settings in a fresh, managed runtime. For Rake tasks, load Rake and the application's Rakefile, invoke an explicit task with structured arguments, report failure, and close in `finally`. Do not concatenate task names or arguments into a shell command or interpolated Ruby source.

Rails migrations are an example of this API, not an automatic startup action. Running tasks may modify a real database and should occur only through explicit operator invocation. Tasks that call process exit must be investigated; choose embedded-safe APIs or reject them with documentation rather than risking the BoxLang JVM.

## 12. Implementation phases and acceptance gates

Complete phases in order. Keep implementation reviewable, follow repository AGENTS.md if present, and record deviations with evidence. Discovery uncertainty is a reason to prototype a boundary, not to replace the overall goal with generic advice.

### Phase 0 — Audit and architecture decisions

- Inspect the current core, build/tests, module loading, web host sources, Rack specs, and framework requirements.
- Choose a tested baseline, verify early raw-body access, and document loader visibility.
- Decide existing-session bridge versus generic SPI and direct Rack versus JRuby-Rack.
- Define host scope, lifecycle ownership, and the worker threading model.

**Gate:** Short decision records, pinned target matrix, smallest integration experiment, and exact unresolved blockers. No invented host hook names.

### Phase 1 — Generic core capabilities

- Add only required environment/extension/output capabilities.
- Preserve public behavior and ownership checks.
- Test independent runtime environments, shutdown tracking, classloader restoration, and byte bridge access.

**Gate:** Existing core build, packaged consumers, and TestBox pass; new generic behavior works without Rack installed. If no SPI is needed, record that decision and avoid adding one.

### Phase 2 — Custom Rack hosting proof of concept

- Create the separately packaged module and explicit app factory.
- Boot a single dedicated worker through config.ru; wire one real BoxLang HTTP gateway.
- Implement minimal correct byte request/response paths and close.

**Gate:** Installed ZIPs serve GET, POST, binary bytes, repeated cookies, and persistent Ruby state; unmatched BoxLang requests still work. Neither app boot nor request handling starts another HTTP server.

### Phase 3 — Bundler and Sinatra

- Add ordered Bundler bootstrap, lockfile checks, diagnostics, app isolation, and Sinatra fixture.
- Test preinstalled pure Ruby/Java gems and clear failures for missing/native/incompatible gems.
- Verify mounts, redirects, middleware, request bodies, and environment separation.

**Gate:** Reproducible Sinatra HTTP tests through the installed module with no dependency downloads during boot/request handling.

### Phase 4 — Complete v1 HTTP/lifecycle behavior

- Add fixed worker pools, admission bounds, body spooling, robust cleanup, registry states, and module-configured apps.
- Add the full supported Rack profile tests, production error handling, logs/status, and shutdown/reload tests.
- Verify the explicit lifecycle sketch or replace it with a working documented gateway pattern.

**Gate:** Real HTTP tests cover the matrix below; no worker or tempfile is released incorrectly on disconnect or timeout. Unsupported streaming/upgrade paths are visible and deterministic.

### Phase 5 — Rails and generic tasks

- Select and lock a real compatible Rails bundle and JDBC adapter.
- Boot its config.ru, serve routes, test mount URLs/cookies, and persist a record.
- Implement generic task execution and validate explicit migration success/failure.
- Add helpers only where a framework needs behavior not supplied by generic config or the app itself.

**Gate:** Real Rails fixture passes through BoxLang with documented dependency, DB, and asset setup. If blocked, publish the exact reproducer and ship only a clearly scoped prerelease; do not claim complete v1 Rails support.

### Phase 6 — Distribution and documentation

- Produce reproducible ZIP/checksum, licenses/notices, dependency metadata, examples, and release notes.
- Run fresh installed-consumer tests and host tests on Linux/Windows where supported.
- Document setup, production deployment, pool memory costs, resource limits, tasks, limitations, and the verified compatibility matrix.

**Gate:** A fresh consumer can follow the documented setup for custom Rack, Sinatra, and Rails using packaged modules. The ordinary bx-jruby consumer remains fully functional without the Rack module.

## 13. Required verification matrix

| Area | Minimum meaningful checks |
| --- | --- |
| Routing | Root mount, segment prefix, exact mount, trailing slash, context root, raw query, encoded path policy, unmatched BoxLang page, Rack 404 |
| Methods/input | GET, POST, PUT/PATCH, DELETE, HEAD, OPTIONS; empty/chunked/unknown-length input; JSON, form, multipart with binary upload |
| Input bounds | Declared oversized input, actual limit exceeded, spool threshold crossing, tempfile cleanup on each failure path |
| Response | Text encoding, non-UTF-8 binary bytes including NUL, large finite enumerable body, redirects, repeat cookies, content length and bodyless statuses |
| Rack profile | Pinned `Rack::Lint`, Ruby IO EOF/buffer behavior, body close after success/error/disconnect, supported response-finished hooks |
| Unsupported features | Callable body if excluded, hijack, upgrade, early hints; no advertised unsupported capability |
| Isolation | Different app env values, different roots/bundles, separate Ruby state, no accidental cross-worker handles |
| Scheduling | One-runtime serialization, multiworker concurrency, queue overflow, acquire timeout, client disconnect while queued/active |
| Lifecycle | Partial boot failure, id collision, close twice, close with active work, grace expiration, unload/reload, stale handles, `at_exit` |
| Frameworks | Locked Sinatra and Rails bundles, Rails DB round trip, migrations, mount URLs, session cookies |
| Tasks | Correct bundle/env, structured arguments, failure/backtrace, output bounds, cleanup, no JVM exit |
| Packaging | Rack ZIP excludes JRuby/core copies; install/load both ZIPs; absent/incompatible core; reload classloader behavior |
| Regression | Existing core JUnit, consumer tests, TestBox; plain eval with Rack disabled; both modules installed together |

Follow core's existing Gradle/JUnit/TestBox patterns. Add a real-host HTTP integration task rather than replacing integration coverage with Java mocks. Pin fixture dependencies and install them in a distinct CI preparation step. A skipped Rails/host test does not count as a passing release gate.

Measure startup time, memory at one versus multiple workers, and ordinary concurrent request throughput. Use observations to recommend pool sizes; do not invent performance targets before measuring the host. Cover Windows path separators and gem paths in addition to Linux deployment.

## 14. Deliverables and definition of done

The implementation agent should produce:

1. A minimal additive core change, if needed, with documented compatibility and regression verification.
2. A separately installable `bx-jruby-rack` module with declared core dependency and no duplicate JRuby runtime JAR.
3. Decision records covering HTTP hooks, loaders, runtime scheduling, Rack profile, and exact framework versions.
4. Custom Rack, Sinatra, and Rails fixtures with reproducible locked dependency setup.
5. Unit, packaged consumer, TestBox, and real HTTP integration checks with commands and actual results.
6. Working explicit/declarative configuration examples, lifecycle and task examples, and production troubleshooting.
7. A tested compatibility matrix and explicit deferred capabilities.

**Done means:** A developer can install the two modules, prepare a compatible Ruby app bundle, point the Rack module at its config.ru, and serve the documented custom Rack/Sinatra/Rails configurations through BoxLang with minimal host configuration. Core-only evaluation remains unchanged. Cleanup, byte handling, and dependency isolation work in installed distributions.

Do not publish artifacts, push changes, or release packages solely because this spec was supplied. Implementation scope is source changes and reviewable validation unless the operator separately authorizes publication.

## 15. Agent handoff prompt

> Implement this specification in ordered phases. First inspect the current bx-jruby checkout and applicable AGENTS.md instructions, then validate the host hook, module dependency loading, Rack/JRuby versions, and runtime ownership model. Preserve existing evaluation/session APIs and keep framework hosting in a separate bx-jruby-rack module. Start with an installed-module custom Rack proof of concept, then Bundler/Sinatra, lifecycle and HTTP hardening, and a real Rails/JDBC/task fixture. Use actual HTTP and packaged-consumer tests as acceptance evidence. Treat proposed API names as design targets; verify concrete BoxLang syntax and APIs before documenting them as working. Record exact blockers and scoped compatibility without making untested framework claims. Do not install gems during runtime startup or requests, duplicate JRuby in the Rack package, globally mutate per-app environment, or release a runtime while its response body is still executing. Finish with reviewable changes, exact validation results, and any remaining limitations.

## 16. Primary references

Read the applicable pinned versions when implementing; moving branch links are research entry points, not lockfiles.

- Core repository and source baseline: https://github.com/tonyjunkes/bx-jruby/tree/3cc95f3d74a02bfdb8db1cfee829de25d8e66de5
- Rack interface specification: https://github.com/rack/rack/blob/main/SPEC.rdoc
- Rack implementation and linting: https://github.com/rack/rack/blob/main/lib/rack/lint.rb
- Rack version changes: https://github.com/rack/rack/blob/main/UPGRADE-GUIDE.md
- JRuby embedding source: https://github.com/jruby/jruby/tree/master/core/src/main/java/org/jruby/embed
- Existing Servlet adapter: https://github.com/jruby/jruby-rack
- Bundler documentation: https://bundler.io/docs.html
- Rails on Rack: https://guides.rubyonrails.org/rails_on_rack.html
- Sinatra documentation: https://sinatrarb.com/intro.html
- BoxLang web hosts and modules: https://boxlang.ortusbooks.com/

The repository baseline was directly inspected. Rack and JRuby-Rack references were consulted during planning. Other links identify required implementation research; this spec does not assert that those sources establish a tested version combination.
