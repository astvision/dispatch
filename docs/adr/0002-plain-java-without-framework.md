# Plain Java without a framework

Dispatch is plain Java 21+ (Maven, one fat jar) with components wired explicitly in `Main`, not Spring Boot, even though Spring Boot is our default stack. Dispatch is a small daemon that serves no HTTP and needs none of Spring's main strengths (web, data access, security); explicit wiring keeps startup, lifecycle and failure paths visible in one place and keeps dependencies to Jackson, a database driver and logging. Revisit if Dispatch grows an HTTP API or web UI.
