# Eprice Backend

## Config
- declare `ENTSOE_API_KEY` in /.env

### Running locally
- start (with changes): docker-compose up --build
- stop: Ctrl + C
> Alternatively in IntelliJ IDEA you can use `Run Server.run.xml` gradle config to run it directly on local JVM.

### Verify
- unit tests: `./gradlew test`
- prices (EE): http://localhost:8080/api/prices
- status: http://localhost:8080/monitor
- price stats: http://localhost:8080/api/prices/EE/stats 
- yesterday stats: http://localhost:8080/api/prices/EE/stats?range=yesterday

---

## Big picture
This project contains the source for Kotlin/Ktor based server which is used to provide energy prices in efficient manner.

The server app.jar is built by gradle in a bespoke docker container for reproduceability.

The app.jar is copied to a runtime docker container to be executed on the VPS as:
```
┌──────────────────────────────────────────────────────────────────┐
│ Host VPS: Ubuntu Linux (Kernel, systemd, fail2ban, UFW firewall) │
├──────────────────────────────────────────────────────────────────┤
│ Reverse Proxy: Host Nginx (SSL termination via Certbot)          │
├──────────────────────────────────────────────────────────────────┤
│ Docker Engine (Shares host Linux Kernel)                         │
│   └─ Container: eprice-be-prod (bound to 127.0.0.1:8080)         │
│        ├─ User Space OS Layer: Alpine Linux                      │
│        ├─ Java Runtime: OpenJDK / Temurin 21 JRE                 │
│        └─ The Application: app.jar (Ktor + Kotlin)               │
└──────────────────────────────────────────────────────────────────┘
```
