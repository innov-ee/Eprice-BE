# Eprice Backend

## Config
- declare `ENTSOE_API_KEY` in /.env

### Running locally
- start (with changes): docker-compose up --build
- stop: Ctrl + C
> Alternatively in IntelliJ IDEA you can use `Run Server.run.xml` gradle config to run it directly on local JVM.

### Verify
- unit tests: `./gradlew test`
- monitor dashboard: http://localhost:8080/monitor.html
- raw monitor status: http://localhost:8080/monitor
- prices (EE): http://localhost:8080/api/prices
- price stats: http://localhost:8080/api/prices/EE/stats 
- yesterday stats: http://localhost:8080/api/prices/EE/stats?range=yesterday

## Deployment and releases

Pull requests run tests. Merging into `main` runs tests, builds a commit-tagged
Docker image and deploys it to **acc** at https://api-acc.innov.ee.
Rerunning the workflow rebuilds and replaces the image under the same commit tag.
Feature branch pushes do not deploy. The acc admin panel is
https://api-acc.innov.ee/monitor.html; it uses the existing admin credentials.

After testing acc, run **Release** in GitHub Actions from `main`. The version bump
defaults to `minor`; choose `major` for an initial `v1.0.0`. The optional commit
field accepts a full tested commit SHA; blank uses the commit captured when that
workflow run started. The workflow requires that commit to belong to `main`. It pulls the existing
image tagged with that commit SHA and deploys it to prod; a missing image causes
the deployment to fail. It creates the version tag and GitHub Release after
success. Acceptance deployment history is not checked.

You can also release from Git, using a new version tag on a tested commit:

```sh
git tag -a v1.0.0 <tested-commit-sha> -m "Release v1.0.0"
git push origin v1.0.0
```

Both annotated and lightweight version tags work. A pushed tag uses its exact
version and commit; it does not bump or rebuild. Prod remains at
https://api.innov.ee until an explicit release.

For rollback, run **Rollback production** from `main` with an earlier release tag
that has a recorded GitHub Release.
It deploys the previous commit image without rebuilding or changing tags. Release
and rollback share a deployment lock. Keep existing commit images in GHCR.

If a release fails, use **Re-run failed jobs** so the already-selected commit and
version are preserved. If only GitHub release metadata failed after deployment,
rerun that failed job rather than start a new release/version bump.

Provision the ACC directory, Compose configuration, deployment script and HTTPS
with `Eprice-infra`'s `acc.yml` before merging. For recovery to a prod image that
predates release tags, use the VPS deployment script with its full commit image tag.
The workflows use the existing repository secrets and log in to GHCR on each
deployment; a separate permanent registry token is not required for Actions.

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
