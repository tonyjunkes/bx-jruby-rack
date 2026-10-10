# Local validation record

Verified on Windows with Java 21 on 2026-10-09. See [compatibility](compatibility.md) for exact dependencies and the supported hosting profile.

| Check | Result |
| --- | --- |
| Core `gradlew build testBox` | 24 JUnit tests and 16 TestBox specifications passed; packaged consumers passed |
| Rack `gradlew prepareFixtures` | Three independent frozen bundles installed successfully |
| Rack `gradlew build` | 6 unit/dependency tests, 12 installed HTTP tests, CLI consumer and package checks passed |
| Rack `gradlew testBox` | 4 public API specifications passed |
| Rack `gradlew benchmark` | Local one-worker and two-worker observations completed |

The HTTP checks use extracted distribution ZIPs with the core and Rack implementations absent from the host application classpath. They exercise actual Jetty requests, Rack::Lint, Sinatra routes and Rails database migration/persistence. They also cover admission limits, disconnect cleanup, malformed responses, root/context mounts, scope isolation and reloading core during an active request.

Reports are generated under each repository's `build/reports/tests`; TestBox JSON reports are under `build/testbox`. Benchmark observations are written to `build/benchmark.json`. These generated files are intentionally excluded from source control.

After the formatting and test-layout changes, core `build` and Rack `build testBox` passed again. Rack now uses its own TestBox installation. All three Rack workflows passed `actionlint`. The release workflow's package preparation script also ran locally against the distribution, verifying the checksum, matching descriptors, module JAR and gateway JAR. An invalid release version was rejected by `verifyModuleMetadata`. GitHub tag/release creation and ForgeBox publication were not invoked.

After the ForgeBox installation guide and Javadoc changes, both `build` tasks passed again: 23 core JUnit tests, 4 Rack unit/dependency tests, 10 Rack HTTP tests, packaged consumers and package checks. All 203 explicitly declared Java methods and constructors across production and test sources have Javadoc. Both builds validate public and private implementation Javadoc with doclint; an AST comparison confirmed the documentation edits preserved Java behavior. Rack's packaged dependency is pinned to core `1.1.0`, and metadata validation enforces that pin. ForgeBox installation instructions were checked against the BoxLang installer implementation; an actual remote installation remains a release gate.

The final 1.0.0 review added regressions for captured-close admission, failed worker replacement, partial startup rollback, malformed configuration and reusable detached configuration snapshots. Both `build testBox` runs passed. The Rack agent skill and updated core skill passed the skill validator. Workflow lint passed for both repositories. Core tag `1.1.0` was absent from the upstream remote at review time; the paired release order remains a gate. Review fixes also retain failed Ruby captures in host logs and prevent status snapshots from exposing mutable internal version metadata.

## Worker sizing observation

A warmed custom Rack fixture handled 80 requests at client concurrency 8. These short local runs are useful only as a starting point for measuring a real application.

| Workers | App boot | Requests/second | Process used heap after boot | Observed heap increase |
| --- | --- | --- | --- | --- |
| 1 | 763 ms | 338 | 219 MB | 26 MB |
| 2 | 855 ms | 936 | 247 MB | 29 MB |

Heap readings use garbage-collection samples and include the shared test host and previously initialized modules. They are not isolated per-runtime memory guarantees. Boot figures use already installed bundles; throughput excludes startup and is not representative of Rails or production capacity. Measure the intended workload before selecting worker count, queue capacity and request limits.

## Release gates

This is a tested `1.0.0` implementation paired with unpublished core `1.1.0` changes. Linux CI, ForgeBox remote installation and CommandBox/Runwar hosting remain unverified. CI defaults to core tag `1.1.0`; manual checks can select a core ref containing the companion changes. Publish core before the first Rack release and configure the Rack repository's `FORGEBOX_API_TOKEN` secret. No releases or remote repository changes were made during local implementation.
