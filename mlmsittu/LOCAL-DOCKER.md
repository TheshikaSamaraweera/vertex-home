# Running MLM Sittu in Docker, locally

Backend, frontend and database in three containers on your own machine. Your native setup is not
touched — PostgreSQL keeps 5432, the Gradle backend keeps 8080, Vite keeps 5173.

```
  http://localhost:8081   web    nginx — the built frontend, and /api proxied to the backend
  http://localhost:8082   app    Spring Boot (backend)
        localhost:5433    db     PostgreSQL 18
```

**This has been run end to end and works.** All three containers healthy, your data loaded, login
verified through nginx. What that took is in [section 9](#9-what-went-wrong-the-first-time) — worth
reading, because three of the four bugs were in the production setup too.

---

## 1. The files

| File | What it is |
|---|---|
| `docker-compose.local.yml` | the three services: `db`, `app`, `web` |
| `.env` | every credential and port, read automatically by Compose |
| `mlmsittu/src/main/resources/application-local.properties` | the Spring side of those credentials |
| `load-data.ps1` | loads data into the container database |
| `dump-native.ps1` | dumps your native database into a file `load-data.ps1` can load |
| `db-snapshot/mlmsitty-local.sql` | your data, already dumped and ready |

`docker-compose.yml` (no `.local`) is the **EC2 deployment** and is not used here.

---

## 2. Credentials

Two places, doing different jobs. Worth understanding once.

### `.env` — the values

Compose reads `.env` from this folder automatically. You never pass it on the command line.

```ini
DB_NAME=mlmsitty
DB_MIGRATION_USER=postgres
DB_MIGRATION_PASSWORD=postgres
DB_USER=mlmsittu_app
DB_PASSWORD=mlmsittu_app

SECURITY_NIC_PEPPER=dev-only-pepper-change-me
SECURITY_NIC_ENCRYPTION_KEY=dev-only-encryption-key-change-me

WEB_PORT=8081
API_PORT=8082
DB_PORT=5433
```

### `application-local.properties` — the wiring

The property file never holds a password. It names an environment variable and a fallback:

```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://db:5432/mlmsitty}
spring.datasource.username=${DB_USER:mlmsittu_app}
spring.datasource.password=${DB_PASSWORD:mlmsittu_app}
```

That is what makes one image runnable in three places. A password written into the property file
would be baked into the image and be the same password everywhere it ever runs.

### How a value travels

```
  .env                     docker-compose.local.yml            Spring
  ─────────────────────    ──────────────────────────────      ───────────────────────────
  DB_PASSWORD=secret  ──▶  DB_PASSWORD: ${DB_PASSWORD}   ──▶   ${DB_PASSWORD} in
                           (an env var inside the app)         application-local.properties
```

### Adding a credential of your own

1. **Put the value in `.env`.**
2. **Pass it to the container** in `docker-compose.local.yml` under `app: environment:`.
3. **Usually nothing else.** `SPRING_MAIL_PASSWORD` maps onto `spring.mail.password` by Spring's
   relaxed binding — underscores become dots, upper case becomes lower. Any property Spring
   already knows needs no line in the properties file at all. For a name of your own,
   `REPORT_FOOTER_TEXT: ${REPORT_FOOTER_TEXT}` binds to `report.footer.text` the same way.

**Changing a value needs a recreate, not a restart.** Environment variables are handed to a
container when it is created:

```powershell
docker compose -f docker-compose.local.yml up -d
```

`docker compose restart` will **not** pick up a new `.env` value.

### Turning on email

The five `SPRING_MAIL_*` / `NOTIFICATIONS_MAIL_FROM` lines in `docker-compose.local.yml` are
**commented out**, not set to empty. Fill in `.env`, uncomment them, then `up -d`.

They are commented rather than blank for a reason that cost an hour the first time — see
[section 9](#9-what-went-wrong-the-first-time). With them absent there is no mail sender at all,
messages queue in `outbox_message` and appear in `docker compose logs app`, and nothing is lost:
turn mail on later and the backlog is delivered on the next poll.

### Three warnings

**`DB_USER` / `DB_PASSWORD` must match `mlmsittu/db/bootstrap/01-app-role.sql`.** That script
creates the role and runs **once**, on the database's first ever start. Changing the password in
`.env` afterwards does not change the role, and the app fails to authenticate.

**The two NIC values must not change.** The pepper is baked into every stored `nic_hash` and the
plaintext NIC is never kept, so old hashes cannot be recomputed. Change them and every NIC becomes
unreadable and every duplicate check silently stops matching — with no error.

**This `.env` is not the server's.** Every value in it is a published development default. The
server gets its own, built from `.env.example` with real secrets. Never copy this one there.

---

## 3. Build

```powershell
cd E:\MLM-Sittu\mlmsittu
docker compose -f docker-compose.local.yml build
```

**The first build took 12 minutes** on this machine and went quiet for minutes at a time. It has
not hung. Measured:

| Stage | Actual |
|---|---|
| pulling `node:24-alpine` | 297 s |
| pulling `eclipse-temurin:21-jdk-alpine` (158 MB) | 353 s |
| frontend `npm ci` + `npm run build` | ~170 s |
| backend Gradle + `bootJar` | ~115 s |

Most of that is downloading base images over your connection, and it only happens once. Rebuilds
after a code change take 1–2 minutes, because the dependency layers are cached. Result:

```
mlmsittu-app:latest   573 MB
mlmsittu-web:latest   77.7 MB
```

Rebuild one service:

```powershell
docker compose -f docker-compose.local.yml build app
docker compose -f docker-compose.local.yml build web
```

> Tests are not run during the build — a build container has no database, and a silently skipped
> suite is worse than an honestly absent one. Run `.\gradlew test` against your native PostgreSQL.
>
> The frontend build *does* run `tsc -b` before Vite, so a type error fails the image rather than
> shipping a bundle that does not match the API.

---

## 4. Run

```powershell
docker compose -f docker-compose.local.yml up -d
```

`-d` runs in the background. Drop it to see all three logs in the terminal.

The database starts first and the backend waits on its health check, so Flyway never runs against a
database that is not accepting connections. On an empty volume Flyway then migrates V1 → V22.

```powershell
docker compose -f docker-compose.local.yml logs -f app
```

Wait for `Started MlmsittuApplication` — **37 seconds** on this machine. Ctrl-C stops following the
log; it does not stop the container.

```powershell
docker compose -f docker-compose.local.yml ps
curl.exe -s http://localhost:8081/actuator/health
```

All three `healthy`, and `{"status":"UP"}`.

> Use `curl.exe`, **with the extension**. Bare `curl` in PowerShell 5.1 is an alias for
> `Invoke-WebRequest`, which takes completely different arguments — that mismatch produced a
> confusing `400` during setup that looked like an application fault.

At this point the database is empty and you cannot log in. That is next.

### Stopping

```powershell
docker compose -f docker-compose.local.yml stop          # stop, keep everything
docker compose -f docker-compose.local.yml down          # remove containers, keep the data
docker compose -f docker-compose.local.yml down -v       # also DELETE the database and documents
```

---

## 5. Load data

```powershell
.\load-data.ps1
```

It loads `db-snapshot\mlmsitty-local.sql` and asks you to type `load` first, because it **replaces
everything** in the container database. `-Force` skips the prompt. Your native PostgreSQL on 5432
is never touched.

What it does:

1. Stops the backend — Hibernate holds connections to tables about to be dropped.
2. `DROP SCHEMA public CASCADE` and recreates it.
3. Loads the file.
4. Starts the backend again.
5. Prints what actually landed:

```
users        21
items        21
stores       2
orders       3
migrations   22
```

The backend then restarts and logs:

```
Successfully validated 22 migrations
Current version of schema "public": 22
Schema "public" is up to date. No migration necessary.
```

That is the restore working as designed. The dump carries **Flyway's history**, so Flyway sees all
22 migrations as already applied and does not re-run them — which is also why the dump must keep
its `GRANT` statements. The grants to `mlmsittu_app` normally come from the migrations, and those
will never run again. `dump-native.ps1` handles that and prints the grant count so you can see it.

### Why it replaces rather than appends

By the time you run this, the backend has started and Flyway has already created the tables. A full
dump on top would fail on the first `CREATE TABLE`. Dropping the schema first makes the load
repeatable instead of a one-time trick that only works on a fresh volume.

### Refreshing from your native database

The two databases **drift apart** from the moment you use both. A sale entered in the containers
does not appear in your native database, and the reverse.

```powershell
.\dump-native.ps1        # re-dump the native database (read-only)
.\load-data.ps1          # load it into the containers
```

### Snapshotting before you break something

```powershell
.\dump-native.ps1 -Out db-snapshot\before-testing.sql
.\load-data.ps1  -File db-snapshot\before-testing.sql
```

---

## 6. Everyday commands

From `E:\MLM-Sittu\mlmsittu`.

| | |
|---|---|
| `docker compose -f docker-compose.local.yml up -d` | start (and apply `.env` changes) |
| `docker compose -f docker-compose.local.yml ps` | what is running |
| `docker compose -f docker-compose.local.yml logs -f app` | follow the backend log |
| `docker compose -f docker-compose.local.yml logs -f db` | follow the database log |
| `docker compose -f docker-compose.local.yml restart app` | restart the backend |
| `docker compose -f docker-compose.local.yml exec db psql -U postgres -d mlmsitty` | database shell |
| `docker compose -f docker-compose.local.yml exec app sh` | shell in the backend container |
| `docker compose -f docker-compose.local.yml down` | stop and remove containers, keep data |

pgAdmin / DBeaver: host `localhost`, port **5433**, database `mlmsitty`, user `postgres`, password
`postgres`. Your native database is on 5432 with the same name — label the two connections clearly,
they look identical in every client.

### After changing code

Nothing hot-reloads. The containers run a built jar and a built bundle:

```powershell
docker compose -f docker-compose.local.yml up -d --build app     # backend change
docker compose -f docker-compose.local.yml up -d --build web     # frontend change
```

> Keep `./gradlew bootRun` and `npm run dev` for actually writing code — they reload instantly. Use
> Docker to check that what you built runs the way it will on the server.

---

## 7. When it goes wrong

**`port is already allocated`** — something holds 8081, 8082 or 5433. Find it with
`netstat -ano | Select-String ":8081|:8082|:5433"`, change the port in `.env`, `up -d` again.

**`.\load-data.ps1 : running scripts is disabled on this system`** — the default execution policy.
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`. Your account only, no administrator needed.

**`dependency failed to start: container mlmsittu-db is unhealthy`** — read the database log
(`logs db`). If it mentions `/var/lib/postgresql/data (unused mount/volume)`, see
[section 9](#9-what-went-wrong-the-first-time); the volume has to be deleted with `down -v`, not
just recreated.

**The app container keeps restarting** — `logs -f app`:
- `FlywayValidateException` / checksum mismatch → the dump and the code have drifted. `down -v`
  then `up -d` gives a clean database migrated from V1.
- `password authentication failed for user "mlmsittu_app"` → `DB_PASSWORD` no longer matches the
  role created on first start. `down -v` and start over, or change the role by hand.

**`502 Bad Gateway` from nginx** — the backend is not up yet. Normal for the first 30–60 seconds
after `up -d`, and for a minute after `load-data.ps1` restarts it. Check `logs -f app`.

**Health is `DOWN` but the app looks fine** — some optional integration is failing its health
check. `curl.exe -s http://localhost:8082/actuator/health` and check the log for
`health check failed`. A DOWN marks the container unhealthy, which stops anything waiting on it.

**A container is `unhealthy` but serves traffic perfectly** — suspect the probe, not the service.
`docker inspect <name> --format "{{json .State.Health}}"` shows the last five attempts and their
output.

**The frontend build fails on a type error** — intentional; `npm run build` runs `tsc -b` first.

**The build dies partway through** — memory. Raise Docker Desktop's limit in Settings → Resources.
3.8 GB was enough here, for both building and running.

---

## 8. How this differs from production

Three things, each a necessity rather than a convenience.

**The session cookie is not `Secure`.** Production marks it Secure, and browsers refuse to store a
Secure cookie that arrived over plain `http://`. You would enter correct credentials and be bounced
back to the login page with nothing useful logged. Verified here — the cookie comes back as
`MLMSESSION=…; Path=/; HttpOnly; SameSite=Lax`, with no `Secure`.

**The NIC secrets stay at their development values.** Production *refuses to start* on them,
deliberately. Locally they are exactly what you need — your data was hashed with them. The app logs
a warning about it at startup, which is correct and should not be silenced.

**`/v3/api-docs` stays enabled**, because `npm run api:sync` reads it. Production turns it off.

Everything else is identical to the server: the same Dockerfiles, both database accounts, the
append-only audit grants, `TZ=Asia/Colombo`, the document volume, the non-root container user.

**Do not run `docker-compose.local.yml` on EC2.** Use `docker-compose.yml` and `DEPLOYMENT.md`.

---

## 9. What went wrong the first time

Four bugs. **Three were in the production setup too**, so finding them here saved finding them on
the server. All are fixed; this section is why the fixes look the way they do.

### `postgres:18` changed where the data volume mounts

*Also affected `docker-compose.yml`.* The database refused to start:

```
Error: in 18+, these Docker images are configured to store database data in a
       format which is compatible with "pg_ctlcluster"...
       Counter to that, there appears to be PostgreSQL data in:
         /var/lib/postgresql/data (unused mount/volume)
```

PostgreSQL 18 images store data in a major-version subdirectory so `pg_upgrade --link` can work
without crossing a mount boundary. The mount must be `/var/lib/postgresql`, **not**
`/var/lib/postgresql/data` — which was correct for every earlier version and is what most guides
still say.

Fixing the compose file was not enough: the bad volume already existed and the container kept
finding it. It took `down -v --remove-orphans` to clear.

### An empty `SPRING_MAIL_HOST` is not the same as unset

Compose passed `SPRING_MAIL_HOST=""`. Spring saw the property as **present**, so Boot
autoconfigured a mailer pointing at `localhost:587`:

```
MailHealthIndicator : Mail health check failed
  Couldn't connect to host, port: localhost, 587
```

That took `/actuator/health` DOWN, which marked the container unhealthy. Worse and quieter:
`SmtpEmailTransport` is `@ConditionalOnProperty("spring.mail.host")`, and an empty string satisfies
that — so it had activated and would have tried to deliver real mail to nothing.

Two fixes. The mail variables are **commented out** rather than blank, so there is no
`JavaMailSender` at all. And `management.health.mail.enabled=false` in the local profile, so an
optional integration can never take the container down — and so a health probe does not open a
connection to your mail provider every 30 seconds.

### The nginx health check asked for `localhost`

*Also affects `docker-compose.yml`, same Dockerfile.* The web container sat `unhealthy` for 19
consecutive checks while serving pages perfectly:

```
wget: can't connect to remote host: Connection refused
```

`nginx.conf` has `listen 80;` — IPv4 only — while `localhost` inside the container resolves to
`::1` first. The probe now uses `127.0.0.1`.

Worth remembering as a class of bug: an unhealthy container that demonstrably works is usually a
broken probe, not a broken service.

### `load-data.ps1` aborted on success

```
docker : Container mlmsittu-app Stopping
    + FullyQualifiedErrorId : NativeCommandError
```

Windows PowerShell 5.1 wraps a native command's stderr in `ErrorRecord` objects, and
`docker compose` writes its ordinary progress to stderr. With `$ErrorActionPreference = 'Stop'`,
the script died on a step that had succeeded. Neither `2>&1` nor `2>$null` helps — the records are
created either way.

The script now uses `$ErrorActionPreference = 'Continue'` and decides success from
`$LASTEXITCODE`, which is what an exit code is for.

---

## 10. Verified

Run end to end on 29 Aug 2026. Docker Desktop 4.88.1, engine 29.7.2, 4 CPUs, 3.8 GB.

```
mlmsittu-app   Up (healthy)
mlmsittu-db    Up (healthy)
mlmsittu-web   Up (healthy)
```

Through nginx on 8081, exactly as a browser reaches it:

```
health   {"groups":["liveness","readiness"],"status":"UP"}
login    HTTP/1.1 200
         Set-Cookie: MLMSESSION=…; Path=/; HttpOnly; SameSite=Lax
         "fullName":"Sithara Super Admin","roles":["SUPER_ADMIN"]
items    SLV-008  Aloe Face Wash 100ml  …          (authenticated read)
```

Data in the container database: 22 items, 21 users, 2 stores, 3 purchase orders, 1 reward
entitlement. Flyway validated 22 migrations and reported the schema up to date.

---

## Appendix: how Docker got installed

Kept because the same trap will appear on any other Windows Home machine.

Docker Desktop on Windows 11 Home runs its engine inside WSL2, and installing Docker does **not**
fix a broken WSL. On this machine `wsl --status` failed with `REGDB_E_CLASSNOTREG` — the Windows
features underneath WSL were switched off, even though the WSL app package was installed and the
CPU had virtualisation enabled in firmware.

**1. In PowerShell as Administrator** (the title bar must say *Administrator*):

```powershell
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
```

> **Watch the spelling of `/norestart`.** A typo (`/norestar`) fails with `Error: 87` — and it is
> easy to miss, because the *first* command scrolls a success message right above it. That happened
> here: Virtual Machine Platform was never enabled, the reboot had nothing to apply, and WSL then
> reported "virtualisation is not enabled on this machine", which sounds like a BIOS problem and is
> not.

**2. Reboot.** The features do not exist until Windows restarts.

**3. Then:**

```powershell
wsl --update
wsl --set-default-version 2
wsl --status
```

If it still reports virtualisation not enabled, run `wsl --install --no-distribution` as
Administrator — it enables Virtual Machine Platform itself — and reboot again. That is what
finally worked here.

Check the real state rather than guessing:

```powershell
(Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled   # firmware — must be True
(Get-CimInstance Win32_ComputerSystem).HypervisorPresent          # True once VMP is on
```

`VirtualizationFirmwareEnabled = True` with `HypervisorPresent = False` after a reboot means the
Windows feature is missing, not the BIOS setting.

**4. Install Docker Desktop** from <https://www.docker.com/products/docker-desktop/>, leaving
**"Use WSL 2 instead of Hyper-V"** ticked. Start it and wait for the whale icon to stop animating.

`wsl --status` printing `Default Distribution: docker-desktop` is the sign the engine is up.

**5. Confirm:**

```powershell
docker --version
docker compose version
docker info
```

> Docker installed here to a **per-user** path,
> `C:\Users\<you>\AppData\Local\Programs\DockerDesktop\resources\bin`, not `C:\Program Files`. It
> is added to PATH on the first successful engine start — so if `docker` is "not recognized" in a
> terminal you opened earlier, open a new one.
