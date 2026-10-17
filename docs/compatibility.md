# Verified compatibility

Candidate verification on Windows, 2026-10-16:

| Component | Version/configuration |
| --- | --- |
| Java | Temurin 21.0.12.1 |
| BoxLang runtime | 1.18.0 |
| BoxLang Servlet | 1.18.6 / Jakarta Servlet 5 |
| Servlet test host | Jetty 12.0.36 EE9 |
| CommandBox / Runwar | 6.3.5 / Jakarta 6.1.8; engine boxlang@1.18.6+62 |
| Module pair | core 1.2.0 / Rack 1.1.0 candidate ZIPs |
| JRuby / Ruby / Bundler | 10.1.2.0 / 4.0.0 / 4.0.21 |
| Rack / Sinatra / Rails | 3.2.7 (Rack::Lint) / 4.2.1 / 7.2.4 |
| Active Record JDBC | 72.1-java (SQLite and PostgreSQL adapters) |
| SQLite driver | jdbc-sqlite3 3.46.1.1 |
| PostgreSQL server / driver | PostgreSQL 17.11 / jdbc-postgres 42.7.11 |

Separate fixture bundles have committed lockfile checksums. Rails retains IRB 1.13.1
and RDoc 6.14.2 to avoid a native Prism extension chain; tzinfo-data 1.2026.5 supports
Windows. CSRF is enabled; example secrets remain public test values.

HTTP tests load extracted sibling ZIPs without core, JRuby or Rack implementation
classes on the host classpath. Only the gateway is host-visible. CLI tasks run
without Servlet classes. The gateway works in CommandBox's parent library loader
and rebinds cached public entry points on module reload.

The profile includes enumerable/callable output and SSE with one worker per stream.
Input is bounded and buffered. Full-duplex sockets, hijacking, upgrades, early hints,
WebSockets and Action Cable are not advertised. Raw paths/query remain undecoded;
SCRIPT_NAME includes context and mount. Scopes are configured independently of Host headers.

Read [capabilities](capabilities.md) and [deployment](deployment.md) for coverage,
remaining gaps, proxies, secrets, assets and worker/database sizing.

## Release and platform evidence

Core 1.1.0 and Rack 1.0.0 were published on 2026-10-10. Their release workflows passed
Windows/Linux checks. This candidate adds Windows local CommandBox/PostgreSQL
verification and new Windows/Linux startup plus Linux PostgreSQL workflow jobs.
Those new workflow runs remain pending until changes are submitted upstream.

The manifest pins core 1.2.0 so CommandBox and the OS installer can resolve it. Release
core first, then check Rack against that tag and publish Rack. New-version ForgeBox
installation is a post-publication check; candidate ZIP installation is covered locally.
This work performs no remote publication.

## Sources

- [Rack 3.2.7 specification](https://github.com/rack/rack/blob/v3.2.7/SPEC.rdoc)
- [BoxLang module development](https://boxlang.ortusbooks.com/boxlang-framework/module-development)
- [CommandBox servlet pass predicate](https://commandbox.ortusbooks.com/embedded-server/multi-site-support/servlet-pass-predicate)
- [CommandBox custom libraries](https://commandbox.ortusbooks.com/embedded-server/configuring-your-server/adding-custom-libs)
- [Active Record JDBC matrix](https://github.com/jruby/activerecord-jdbc-adapter#readme)
