# bx-jruby-rack contributor guidance

Read `README.md` for setup, `SKILL.md` for integration, `docs/deployment.md` for
transport/lifecycle contracts, and `docs/capabilities.md` before expanding support.
`docs/implementation-spec.md` is historical; current code and compatibility docs define the release.

Full framework applications live in [bx-jruby-rack-examples](https://github.com/tonyjunkes/bx-jruby-rack-examples).
Keep the packaged CommandBox starter as the minimal gateway/streaming smoke example.

## Build and validation

- Use Java 21. Build the sibling core package before Rack: its exact version is in `gradle.properties`.
- `./gradlew.bat prepareFixtures` explicitly installs locked Ruby bundles; other hosts use `./gradlew`.
- `./gradlew.bat build testBox` verifies unit/dependency tests, installed HTTP/CLI consumers, package isolation and Javadoc.
- `./gradlew.bat benchmark` records local worker observations, without production capacity claims.
- `node scripts/commandbox-smoke.mjs` starts extracted candidates on CommandBox and checks Ruby, BoxLang, SSE and public-root isolation.
- PostgreSQL tests read `BX_RAILS_DATABASE_URL`; Linux CI supplies a PostgreSQL service. SQLite remains the default.
- Workflow checks default to core tag 1.2.0. Release core first; keep Rack's manifest dependency concrete and metadata consistent.
- PR checks alone accept the repository variable BX_JRUBY_PR_CORE_REF for companion core work; clear it after core publication and re-run against the tag. Release checks ignore this override.
- Keep the manifest's compiled GitHub ZIP location versioned; the release asset must exist before ForgeBox publication.

| Verified command | Purpose |
| --- | --- |
| `bash ./gradlew prepareFixtures` | Prepare locked gems |
| `bash ./gradlew build testBox` | Build and verify |
| `node scripts/commandbox-smoke.mjs` | Check CommandBox startup |

## Language and ownership

- Native BIFs and lifecycle glue live in `src/main/bx`; resolve bridges through the owning module loader.
- Java owns routing, gateway, immutable config, queueing, thread-affine workers, spooling and byte transport.
- Ruby protocol/task resources live in `src/main/resources/modules/bxjrubyrack`.
- Core alone creates JRuby sessions; Rack must not bundle core/JRuby implementation classes.
- The gateway can be parent-loaded above BoxLang. Resolve host APIs through the servlet webapp loader and cache activation-specific entry points.
- Worker ownership includes input preparation, response/body cleanup, request-binding removal and session shutdown.
- One stream retains one worker. Drain cooperatively rejects stream I/O; finite active responses finish normally. Never terminate a runtime under active Ruby.
- Matched requests bypass Application.bx and must reach the gateway before form parsing. Keep scoped mounts independent of request Host headers.

## Changes

- Preserve existing public arguments/defaults and detached snapshots. Session/app methods remain Java positional methods.
- Use readable four-space indentation, expanded blocks and blank lines between methods/tasks; document Java methods with Javadoc.
- Add protocol regressions to actual HTTP tests with implementation classes absent from the host classpath.
- Update README, docs, packaged examples, SKILL.md and this file with behavior/workflow changes.
- Fixtures contain test secrets; keep source, gems, modules and config outside starter public roots.
