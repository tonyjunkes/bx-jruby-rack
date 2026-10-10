# Changelog

## 1.0.0

- Detached app configuration snapshots for explicit tasks and app replacement.
- Worker startup/failure coordination and cleanup that cannot unregister a replacement app.
- Early validation of malformed configuration and retained logging of failed Ruby captures.
- Detached status version metadata, preserving internal diagnostics when callers edit a snapshot.
- Packaged LLM agent skill covering gateway deployment, APIs, tasks and lifecycle.
- Preserve the packaged example applications when publishing the compiled module to ForgeBox.

- Separate Rack module using core-owned, thread-affine JRuby sessions.
- Servlet gateway, scoped mounts, declarative/explicit applications, and offline Rake tasks.
- Bundler-aware boot, bounded admission and uploads, byte-safe responses, drain lifecycle, and sanitized diagnostics.
- Locked Rack 3.2.7, Sinatra 4.2.1, and Rails 7.2.4/JDBC SQLite fixtures, installed-package HTTP tests, and core regression checks.
- Independent PR/check/release workflows with BoxLang version checks, annotated tags, GitHub release artifacts and ForgeBox publication.
- Local TestBox development dependency and integration sources grouped under `src/test/integration/java` with an isolated runtime classpath.
- ForgeBox-first getting-started instructions and a concrete core dependency for automatic installation with the BoxLang installer.
- Javadoc for all declared Java methods and constructors, including lifecycle and ownership contracts, validated during builds.
