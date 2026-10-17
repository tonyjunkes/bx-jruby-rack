# Capability review: Rack 1.1.0

| Status | Capabilities |
| --- | --- |
| Implemented and tested | Rack 3 enumerable/callable HTTP; SSE flush; buffered binary input; uploads; headers/cookies; scoped mounts; bounded workers/queue/body sizes; cooperative stream drain; disconnect cleanup; reload; offline Rake tasks; detached configuration/status. |
| Framework integration tested | Sinatra 4.2.1 and Rails 7.2.4: HTML templates, forms/CSRF, sessions, redirects, uploads, static/fingerprinted assets, SSE and Rails Live producer cleanup. Rails migrations, CRUD and rollback use SQLite and PostgreSQL. |
| Available through Ruby/frameworks | Rack middleware, application routing, authentication libraries, compatible Java/pure Ruby gems, rendering and business logic. They remain application dependencies. |
| Unverified | Rails 8, Roda, Hanami, other database adapters/framework versions, asset-build pipelines, arbitrary proxies and production capacity. |
| Unsupported | WebSockets/Action Cable, hijacking, early hints/upgrades, asynchronous multiplexing, live full-duplex input, automatic dependency installs/migrations/assets, MiniServer/Lambda adapters and hot Ruby-file reload. |

One open stream owns one worker. HTTP streaming does not imply all optional Rack
protocol extensions. Use Rack::Lint and the exact matrix in [compatibility](compatibility.md).

Rails 8 assessment: the upstream Active Record JDBC matrix marks 80.x and 81.x as
under development and unsupported. Rails 7.2/72.1-java remains the supported baseline.
JRuby language compatibility alone does not establish framework/database support.
[Upstream matrix](https://github.com/jruby/activerecord-jdbc-adapter#readme).

Native BoxLang supplies BIF arguments, lifecycle wiring and registration cleanup.
Java retains the gateway, registry, immutable config, workers/admission, spooling,
bytes and cross-module ownership. Ruby resources own Rack protocol and Rake bootstrap.
Core alone owns JRuby classes and runtime creation.
