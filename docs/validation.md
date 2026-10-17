# Candidate validation record

Windows verification, 2026-10-16–17, using Java 21 and the exact dependency matrix in
[compatibility](compatibility.md). Core 1.1.0 and Rack 1.0.0 were already published;
their release workflows passed Windows/Linux checks on 2026-10-10.

| Check | Result |
| --- | --- |
| Core build/testBox | 31 JUnit tests, 16 TestBox specifications, installed consumers, override/reload checks, Javadoc and interop example |
| Rack build/testBox | 6 unit/dependency tests, 14 installed HTTP tests, 4 TestBox specifications, CLI and package checks |
| PostgreSQL | Real PostgreSQL 17.11: migrations, CRUD, rollback, Rake tasks, HTML/CSRF/uploads and Rails Live cleanup |
| CommandBox candidate startup | Extracted ZIPs: BoxLang page, Ruby page, SSE and 404 responses for source/config/module paths |
| Package and source checks | Metadata/dependency pin, SHA-256 artifacts, Javadoc, workflow lint, skill YAML and agent-file content checks |
| Performance | Repeated released/candidate core and Rack comparisons; see [performance](performance.md) |

Installed HTTP tests exclude core, JRuby and Rack implementation classes from the
host classpath. The gateway is the only host-visible module JAR. CLI consumers exclude
Servlet classes too. Native wrappers cover named/default arguments, caller-relative
paths, inline component scope/early exit, unload/reload and descriptor ownership.

Core interop checks exercise copied input/ARGV, EOF, binary/invalid encoding round
trips, symbols, temporal precision, handle ownership, Bundler conflicts/failure/retry,
Ruby blocks with keywords, late/cross-thread/reentrant callbacks, standard libraries,
Java interop and process-exit containment.

Real HTTP checks cover mounted paths, binary input/output, cookies, CSRF rejection,
sessions, uploads, static assets, malformed responses, body limits, admission/timeout,
reload, callable/enumerable precedence, first-event delivery before completion, HEAD,
stream saturation, disconnect cleanup, cooperative drain and Rails producer cleanup.
Module archives and Ruby fixtures are explicit Gradle Test inputs so protocol changes
cannot be skipped solely because implementation classes are absent from the host classpath.

Reports are generated under `build/reports/tests`, `build/test-results` and
`build/testbox`; performance observations are under `build/performance`. CommandBox
smoke runs retain their setup/start/stop logs in `build/commandbox-smoke-*`.

## Remaining external verification

The candidate's Windows/Linux CommandBox jobs and Linux PostgreSQL job run in the
reusable checks workflow. The first PR run could not check out the unreleased core
tag. A companion-core run also exposed a Windows installer archive mismatch and a
transient Maven Central HTTP 403; workflow fixes and a fresh run address those separately.
This Windows workspace has no installed WSL distribution.

For paired core/Rack development, set the Rack repository variable
`BX_JRUBY_PR_CORE_REF` to the reviewed companion core commit. Only PR checks read it;
manual checks accept `core_ref`, and release checks keep the exact default tag.
After core 1.2.0 is published, remove the variable and re-run Rack PR checks against
the tag before merging. Neither override changes the package's exact dependency pin.

Release core 1.2.0 first, run Rack against that tag, then publish Rack 1.1.0. Remote
installation of these new ForgeBox versions must follow publication. Candidate ZIP
installation and startup are verified locally; this work performs no publication.

An actual CommandBox install of indexed core 1.1.0 failed because ForgeBox had no
package binary location. Both candidate manifests now point at their versioned
compiled GitHub release ZIPs. Metadata checks enforce the location, and release
workflows upload assets before publishing ForgeBox metadata. Existing published
metadata was not modified; repair it or publish these candidates before relying
on that remote installation path.

The original implementation specification remains historical. Current capability and
compatibility documents describe this candidate rather than an unpublished 1.0.0.
