/** Explicit setup generates host configuration and installs the Ruby bundle before startup. */
component {
    /** Re-running setup replaces generated config files; Ruby source and server.json stay editable. */
    function run(boolean installGems=true, string bundlePath="") {
        var root = getDirectoryFromPath(getCurrentTemplatePath());
        var modules = root & "modules/";
        var jars = directoryList(modules & "bx-jruby/libs", false, "path", "jruby-complete-*.jar");
        if (arrayLen(jars) != 1) {
            throw(message="Run box install before setup");
        }
        var bundle = len(arguments.bundlePath) ? arguments.bundlePath : root & "bundles/ruby-app";
        var home = root & ".boxlang";
        var configPath = root & "config/boxlang.json";
        directoryCreate(root & "config", true, true);
        var config = {
            "modulesDirectory": [modules],
            "modules": {"bxjrubyrack": {"settings": {"apps": [{
                "id": "ruby", "root": root & "app", "mount": "/ruby",
                "environment": "development",
                "runtime": {"env": {"BUNDLE_PATH": bundle}}
            }]}}}
        };
        fileWrite(configPath, serializeJSON(config));
        var params = '<init-param><param-name>boxlang-home</param-name><param-value>' & xmlFormat(home) & '</param-value></init-param>'
            & '<init-param><param-name>boxlang-config-path</param-name><param-value>' & xmlFormat(configPath) & '</param-value></init-param>';
        fileWrite(root & "config/gateway.xml", '<?xml version="1.0" encoding="UTF-8"?>'
            & '<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="5.0">'
            & '<filter><filter-name>RubyRack</filter-name><filter-class>modules.bxjrubyrack.gateway.RackGateway</filter-class>'
            & params & '</filter><filter-mapping><filter-name>RubyRack</filter-name><url-pattern>/*</url-pattern><dispatcher>REQUEST</dispatcher></filter-mapping>'
            & '<servlet><servlet-name>BoxLangServlet</servlet-name><servlet-class>ortus.boxlang.servlet.BoxLangServlet</servlet-class>'
            & params & '<load-on-startup>1</load-on-startup></servlet>'
            & '<servlet-mapping><servlet-name>BoxLangServlet</servlet-name><url-pattern>*.bxm</url-pattern><url-pattern>*.bxs</url-pattern><url-pattern>*.bx</url-pattern></servlet-mapping>'
            & '<welcome-file-list><welcome-file>index.bxm</welcome-file></welcome-file-list></web-app>');
        if (arguments.installGems) {
            var java = createObject("java", "java.lang.System").getProperty("java.home") & "/bin/java";
            var builder = createObject("java", "java.lang.ProcessBuilder").init([
                java, "-jar", jars[1], "-S", "bundle", "install"
            ]);
            builder.directory(createObject("java", "java.io.File").init(root & "app"));
            builder.environment().put("BUNDLE_PATH", bundle);
            builder.environment().put("BUNDLE_FROZEN", "true");
            builder.inheritIO();
            if (builder.start().waitFor() != 0) {
                throw(message="Ruby bundle installation failed");
            }
        }
        print.line("Configured BoxLang and Rack. Run box server start, then open http://localhost:8080/ruby");
    }
}
