# Verified compatibility and release scope

Local verification on Windows, 2026-10-09:

| Component | Tested version/configuration |
| --- | --- |
| Java | Eclipse Temurin 21.0.12.1 |
| BoxLang | 1.18.0 |
| BoxLang Servlet | 1.18.6, Jakarta Servlet 5 |
| Test host | Jetty 12.0.36, EE9 servlet context |
| Core module | bx-jruby 1.1.0 source changes in the sibling checkout |
| JRuby / Ruby | 10.1.2.0 / 4.0.0 |
| Bundler | 4.0.21 |
| Rack | 3.2.7 with Rack::Lint |
| Sinatra | 4.2.1 |
| Rails | 7.2.4, Action Controller + Active Record |
| JDBC adapter | activerecord-jdbcsqlite3-adapter / activerecord-jdbc-adapter 72.1-java |
| Database | jdbc-sqlite3 3.46.1.1; real local file, migration and persisted record |

The framework fixtures have separate bundles and committed Gemfile.lock checksums. Rails pins IRB 1.13.1 and RDoc 6.14.2 because the newer resolved dependency chain attempted to install a native Prism extension. tzinfo-data 1.2026.5 supplies time zones on Windows. The fixture disables CSRF for API test requests and uses a public test secret; replace both choices for a production app.

The HTTP suite loads both extracted module ZIPs with neither core, JRuby, nor Rack implementation classes on the Java application classpath. The small gateway JAR is the only module-related host JAR. It tests context-root mounts, binary input/output, cookies, Rack 404 ownership, ordinary BoxLang pages, multipart parsing, unknown-length bodies, bounds, queue overflow/timeout, multiple workers/apps, close/drain, disconnect/body cleanup, Rails persistence and tasks, and core/Rack reload.

The CLI consumer has only BoxLang on its application classpath. It proves tasks work without Servlet classes, ordinary core evaluation remains available, and HTTP factories reject an absent gateway. Separate startup subprocess tests reject missing, disabled and incompatible core modules. TestBox covers installed public APIs and normal Ruby exit containment.

## Restricted profile

This is synchronous **enumerable Rack 3 HTTP hosting**, not full Rack 3 streaming conformance. Application bundles own their Rack version; only the version above is verified. The bridge rejects callable-only bodies, informational/upgrade statuses, Rack protocol/hijack headers, and transport-owned framing headers. It advertises no hijacking, early hints, or WebSocket facilities. No SSE or indefinite-streaming guarantees are made.

Gateway scopes are fixed servlet-context namespaces. Automatic Application.bx lifecycle mounts are intentionally replaced by the early servlet gateway because BoxLang parses form data before the web-executor hook. Mount `/ruby` handles `/ruby` and `/ruby/...`, with an empty PATH_INFO at the exact mount. `/rubygems` is unmatched. The servlet context contributes to SCRIPT_NAME; percent-encoded path and raw query text are not decoded/reencoded by the bridge.

## Not yet verified

- MiniServer, Lambda, Roda, Hanami, other Rails versions, other JDBC adapters, production asset serving, proxy configurations, and external authentication middleware have no compatibility claim.
- The benchmark is a small local observation of custom Rack workers, not Rails production capacity or an SLA.

The reusable checks workflow defaults to core tag `1.1.0`; manual checks accept a compatible `core_ref`. Core must be released first so the default checkout and published package dependency can resolve. Rack has independent PR and release workflows, including TestBox and BoxLang version checks. No source or packages have been published by this implementation.

The ForgeBox manifest pins core `1.1.0`, matching the tested baseline. This also
lets the BoxLang module installer resolve the dependency directly: its dependency
installer accepts concrete versions rather than CommandBox semver ranges.

## Sources

- [Rack 3.2.7 specification](https://github.com/rack/rack/blob/v3.2.7/SPEC.rdoc)
- [Rack 3.2.7 lint implementation](https://github.com/rack/rack/blob/v3.2.7/lib/rack/lint.rb)
- [JRuby embedding](https://github.com/jruby/jruby/tree/10.1.2.0/core/src/main/java/org/jruby/embed)
- [Rails Rack interface](https://guides.rubyonrails.org/rails_on_rack.html)
- [Active Record JDBC version matrix](https://github.com/jruby/activerecord-jdbc-adapter)
- [Module inception and loader direction](https://boxlang.ortusbooks.com/boxlang-framework/module-development/module-inception)
- [BoxLang module installation](https://boxlang.ortusbooks.com/getting-started/installation/modules)
- [BoxLang installer dependency resolution](https://github.com/ortus-boxlang/boxlang-quick-installer/blob/main/src/install-bx-module.sh)

Exact acceptance evidence is in the generated JUnit/TestBox reports, not inferred from these upstream descriptions.
