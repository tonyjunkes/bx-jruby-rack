# CommandBox starter

Requires CommandBox 6.2+, Java 21+, and network access during explicit installation.
Copy this directory into your application folder, then run:

```sh
box install
box task run
box server start
```

Open http://localhost:8080/ for BoxLang, /ruby for Ruby, and /ruby/events for SSE.
The root package installs both modules from ForgeBox as siblings. Setup installs
the locked app bundle using core's bundled JRuby and generates absolute host paths.
Run setup again after moving the application. It replaces config/boxlang.json and
config/gateway.xml. Edit task.cfc for different mounts or app settings.
The packaged .gitignore excludes installed modules, gems and generated configuration;
keep it when copying the starter into your project.

The starter pins `boxlang@1.18.6+62` (BoxLang runtime 1.18.0). Its
`web.servletPassPredicate=true` sends extensionless Ruby routes through the gateway.
Keep that setting and the explicit servlet mappings when customizing startup.

Only public/ is served by CommandBox. Ruby source, gems, module JARs, and configuration
remain outside it. app.libDirs points to the installed gateway directory. Keep the
filter before other body-parsing filters in a customized web.xml override.

The example uses one Ruby worker. An open stream occupies it; allocate additional
workers or a dedicated mount before mixing long-lived streams and ordinary requests.
Set production secrets in your framework, prepare assets and gems before startup,
and consult the module's deployment documentation before production use.
