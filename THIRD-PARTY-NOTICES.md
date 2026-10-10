# Third-party notices

The Rack module and gateway are MIT licensed (see LICENSE). Their JARs do not bundle JRuby, core, BoxLang, Servlet, Jetty, or Ruby gems. The package installs bx-jruby as a dependency; its distribution includes JRuby and its third-party notices.

BoxLang and Jakarta Servlet are compile-time APIs supplied by the host. Jetty and JUnit are development/test dependencies, not distribution contents. Ruby fixture Gemfiles and locks describe application-owned dependencies installed only by explicit fixture preparation; those gems retain their own licenses. Review the locked gems' licenses when redistributing an application bundle.
