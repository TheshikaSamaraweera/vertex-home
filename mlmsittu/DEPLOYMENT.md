# Deploying MLM Sittu to EC2

Three containers on one instance: PostgreSQL, the Spring application, and nginx serving the
frontend and proxying the API.

```
                    :80/:443
                       │
                 ┌─────▼─────┐
                 │    web    │  nginx — serves the SPA, proxies /api
                 └─────┬─────┘
                       │  app:8080
                 ┌─────▼─────┐
                 │    app    │  Spring Boot, profile=prod
                 └─────┬─────┘
                       │  db:5432
                 ┌─────▼─────┐
                 │     db    │  PostgreSQL 18
                 └───────────┘

    volumes: pgdata (database) · documents (uploaded NIC images and bank slips)
```

No Redis and no MinIO. Phase 7 put sessions and rate limits in PostgreSQL, and documents live on a
mounted volume, so there is nothing else to run.

> **Rehearse this locally first.** `LOCAL-DOCKER.md` runs these same three containers, built
> from these same Dockerfiles, on your own machine via `docker-compose.local.yml`. Doing that
> found three bugs that would otherwise have surfaced here — see section 9. It deliberately
> relaxes the production settings that make no sense on `http://localhost`, so never use that
> compose file on a server.

---

## 1. The instance

### Launching it

| Setting | Value | Why |
|---|---|---|
| AMI | Amazon Linux 2023 or Ubuntu 24.04 LTS | Both are covered below. Ubuntu needs Docker's own apt repository, not the distribution package. |
| Type | `t3.small` | The JVM takes ~70% of its container's memory, PostgreSQL a few hundred MB, nginx nothing. `t3.micro` (1 GB) runs but leaves no headroom, and the build is what runs out first. |
| Storage | 30 GB gp3 | The default 8 GB does not survive Docker images plus the database plus uploaded documents. Growing it later means resizing a live filesystem. |
| Key pair | create and download | The only way in. There is no password login. |
| Elastic IP | allocate and associate | A stopped instance gets a **new** public IP on restart, which silently breaks `APP_BASE_URL` and every verification link already emailed. |

**Security group inbound:** 22 from your own IP only, 80 and 443 from anywhere.
**Do not open 5432.** The database is not published outside the compose network and must stay that
way — the compose file exposes it to `app` alone.

### Docker

**Amazon Linux 2023:**

```bash
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
```

**Ubuntu** — use Docker's own repository, not `apt install docker.io`. The distribution package
does not carry the Compose v2 plugin, so `docker compose` does not exist afterwards and you end up
on the deprecated standalone `docker-compose` v1, which reads these files differently.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc]   https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable"   | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
```

Then, on either:

```bash
newgrp docker            # or log out and back in
docker compose version   # must print v2.x — not docker-compose 1.x
```

`usermod` does not affect the shell you are already in. If the next `docker` command says
`permission denied ... /var/run/docker.sock`, that is why, and `newgrp docker` fixes it without
reconnecting.

### Swap, on a t3.small

```bash
sudo dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Not optional in practice. `docker compose build` runs Gradle and Vite, both of which are memory
hungry, and on 2 GB with no swap the kernel's OOM killer takes the build out — usually reported as
an opaque non-zero exit rather than anything mentioning memory.

### Getting the code across

The repository is private, so the instance needs its own read-only credential. A **deploy key** is
the right one: it grants access to this repository alone, and revoking it cannot lock anyone out
of anything else.

```bash
ssh-keygen -t ed25519 -C "mlmsittu-ec2" -f ~/.ssh/id_ed25519 -N ''
cat ~/.ssh/id_ed25519.pub
```

Paste that into GitHub → the repository → Settings → Deploy keys → Add deploy key. Leave **Allow
write access unchecked** — the server only ever reads.

```bash
sudo mkdir -p /opt/mlmsittu && sudo chown ec2-user:ec2-user /opt/mlmsittu
git clone git@github.com:TheshikaSamaraweera/vertex-home.git /opt/mlmsittu
cd /opt/mlmsittu/mlmsittu          # the compose files live one level down
```

Pasting the source over SCP works too, but then `git pull` in section 7 does not, and updates
become a manual copy every time.

---

## 2. Configuration

```bash
cd /opt/mlmsittu/mlmsittu
cp .env.example .env
chmod 600 .env
```

`.env` is gitignored, so it is never in the clone and never overwritten by `git pull`. Do **not**
copy the development `.env` from your own machine: it carries the placeholder NIC keys published
in this repository, and the prod profile refuses to start with them — which is the check working,
not a bug.

### `APP_BASE_URL` — set this or every verification email is wasted

```
APP_BASE_URL=http://<your-elastic-ip>        # https://your-domain.lk once TLS is up
```

No trailing slash. It is what the account-confirmation link is built from, and the code falls back
to `http://localhost:5173` — a developer's Vite server. Get it wrong and the send still succeeds,
the log still says `Email sent`, the outbox row still says `sent`; the recipient is the only one
who ever finds out. Change it again the moment you put a domain in front, and note that anything
already emailed keeps the old address.

Generate the two NIC secrets separately:

```bash
openssl rand -base64 48    # SECURITY_NIC_PEPPER
openssl rand -base64 48    # SECURITY_NIC_ENCRYPTION_KEY
```

> ### These two can never be changed
>
> The pepper is baked into every stored NIC hash and the plaintext is never kept, so there is no
> way to recompute the old hashes. Rotating either one orphans every registration you have.
>
> **Back them up somewhere that is not this server.** Losing them is losing the ability to check
> whether an NIC is already registered.
>
> The application **refuses to start** under the prod profile while they are unset. That is
> deliberate: a container that came up with the development keys would encrypt real identity
> numbers with a key published in this repository, and would look perfectly healthy doing it.

If you change `DB_PASSWORD`, change it in `mlmsittu/db/bootstrap/01-app-role.sql` too — that
script creates the role, and it only runs once, on first start.

---

## 3. First start

```bash
docker compose up -d --build
docker compose logs -f app
```

What should happen, in order:

1. `db` comes up and runs `01-app-role.sql`, creating the `mlmsittu_app` role.
2. `app` waits for the database to pass its health check, then Flyway migrates from V1 to V24.
3. `Started MlmsittuApplication` — expect 30–60 seconds on a small instance.
4. `web` serves on port 80.

```bash
curl -fsS http://localhost/actuator/health     # {"status":"UP"}
```

If any of that does not happen, section 9 covers the failures worth predicting.

```bash
docker compose ps                              # all three should be healthy
```

### Two accounts, and why

Flyway migrates as **postgres** because migrations create tables and extensions. The application
runs as **mlmsittu_app**, which has no DDL rights and — critically — no `UPDATE` or `DELETE` on
`audit_log`. That is what makes the audit trail append-only in fact rather than by convention. If
you ever "simplify" this by pointing the app at the superuser, every one of those protections
silently disappears.

---

## 4. TLS

The application sets `Secure` on the session cookie under the prod profile, so **over plain http
the browser refuses to store it and nobody can log in.** That is the intended failure — it is far
louder than quietly sending session cookies in clear text.

Either terminate TLS at an ALB pointing at port 80, or on the instance:

```bash
sudo dnf install -y certbot python3-certbot-nginx
# then proxy 443 -> the `web` container, or run certbot's nginx on the host in front of it
```

nginx already forwards `X-Forwarded-Proto`, and the application reads it
(`server.forward-headers-strategy=framework`), so it will know the request arrived over https.

---

## 5. Email

Four messages leave the system: account verification, a purchase order to a supplier, "a pack is
ready to issue" to administrators, and "your pack has been issued" to the distributor. All four go
through the outbox — queued inside the transaction that caused them, delivered seconds later — so
a rollback cannot leave a sent email behind and a dead mail server cannot fail a purchase order.

**Email is off until you turn it on, and that is a fine state to deploy in.** Messages queue in
`outbox_message` and go to the application log. Nothing is lost.

### Turning it on

The seven mail lines in `docker-compose.yml` are **commented out**. Fill in `.env`:

```
SPRING_MAIL_HOST=email-smtp.ap-south-1.amazonaws.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=...
SPRING_MAIL_PASSWORD=...
NOTIFICATIONS_MAIL_FROM=orders@yourcompany.lk
```

then **uncomment those lines** and `docker compose up -d app`.

Anything already queued is delivered on the next poll, so messages that were only logged while
mail was off are not lost — they were waiting. Expect a burst on the day you first configure it.

> ### Why commented out rather than left empty
>
> They used to default to empty (`${SPRING_MAIL_HOST:-}`). Running these same images locally
> showed why that is wrong: **an empty value is not an absent one.** Spring saw the property as
> present, Boot autoconfigured a mailer aimed at `localhost:587`, its health check failed, and
> `/actuator/health` went DOWN — marking the container unhealthy with nothing actually wrong.
> `SmtpEmailTransport` activated too, and tried to deliver real mail to a server that was not
> there.
>
> The mail health indicator is also switched off in the prod profile. With a real provider it
> would open a connection on every probe, so an SES blip or a rate limit would make a healthy
> application look broken — and undelivered mail is already handled properly by the outbox.

### Three things that will actually catch you out

**AWS blocks outbound port 25 on EC2 by default.** Use port 587 (submission). Removal of the
restriction can be requested, but is rarely worth it.

**Gmail needs an App Password**, not the account password, and App Passwords require 2-Step
Verification on that Google account. The normal password is rejected without saying why.

**SES needs the From address verified**, and a new SES account is in the sandbox — it delivers
only to addresses you have also verified until you request production access. A supplier receives
nothing until then, and **the send appears to succeed.**

### Checking it works

```bash
docker compose exec db psql -U postgres -d mlmsitty -c \
  "SELECT recipient, subject, status, attempts, last_error
     FROM outbox_message ORDER BY created_at DESC LIMIT 5;"
```

- `pending` with `attempts = 0` → not tried yet; wait five seconds.
- `sent` → delivered to your provider.
- `pending` with a `last_error` → failed, and will retry, backing off 1, 2, 4, 8 minutes.
- `failed` → gave up after five attempts. `last_error` says why; fix it and requeue with
  `UPDATE outbox_message SET status='pending', attempts=0, next_attempt_at=now() WHERE id='…';`

A failed send never loses the business change that caused it. The purchase order is still sent,
the pack is still issued — only the notification is outstanding.

---

## 6. Creating the first administrator

**A fresh database has no accounts at all.** No migration seeds one, deliberately — a shipped
account with a known password is a back door in every deployment that forgets to remove it. So the
first administrator is made by hand, and until you do it nobody can sign in.

Do **not** write the password hash yourself. Argon2id parameters have to match the application's
encoder exactly, and a mismatch fails at login looking indistinguishable from a wrong password.
Let the application hash it, by using the ordinary signup endpoint and then promoting the account:

```bash
# 1. The application creates the account and hashes the password with its own encoder.
curl -sS -X POST http://localhost/api/v1/auth/register      -H 'Content-Type: application/json'      -d '{"fullName":"Your Name","email":"you@yourcompany.lk","password":"<a long password>"}'
```

It answers `If that address can be registered, a confirmation link is on its way.` whatever
happens — that endpoint deliberately refuses to reveal whether an address is already taken, so it
is not a way to enumerate accounts. The account exists but is `unverified`, and signup also grants
it `DISTRIBUTOR`, which an administrator has no business holding.

```bash
docker compose exec db psql -U postgres -d mlmsitty
```

```sql
-- 2. Activate it. Skips the emailed link, which matters because mail may not be on yet, and the
--    person doing this is standing at the server anyway.
UPDATE app_user SET status = 'active', email_verified = true
 WHERE email = 'you@yourcompany.lk';

-- 3. Promote. No hash is written here, which is the whole point.
INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u, app_role r
 WHERE u.email = 'you@yourcompany.lk' AND r.code = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- 4. Drop the DISTRIBUTOR role signup added. Leaving it makes an administrator show up in the
--    customer network as a person who could be referred, which is not a thing they are.
DELETE FROM user_role ur USING app_user u, app_role r
 WHERE ur.user_id = u.id AND ur.role_id = r.id
   AND u.email = 'you@yourcompany.lk' AND r.code = 'DISTRIBUTOR';
```

Confirm it before you close the terminal:

```bash
curl -sS -X POST http://localhost/api/v1/auth/login      -H 'Content-Type: application/json'      -d '{"email":"you@yourcompany.lk","password":"<the same password>"}'
```

`"roles":["SUPER_ADMIN"]` and HTTP 200 means it worked.

### TOTP

`SUPER_ADMIN` and `ADMIN` do **not** require it — that was turned off at the client's request, and
`app_role.requires_mfa` is `false` for both. The staff roles still do: `FINANCE_OFFICER`,
`INVENTORY_CLERK`, `KYC_REVIEWER`, `PROCUREMENT_OFFICER` and `SUPPORT_AGENT` are handed an
enrolment QR on first login and cannot sign in until they scan it.

To require it of administrators too, one statement and a restart is all it takes:

```sql
UPDATE app_role SET requires_mfa = true WHERE code IN ('SUPER_ADMIN', 'ADMIN');
```

---

## 7. Updating

```bash
git pull                              # or copy the new source across
docker compose up -d --build app web
```

Flyway runs at startup and is forward-only. A migration that fails leaves the container
restarting; the previous version is still in the image, so:

```bash
docker compose logs app | grep -i migration
```

**Before deploying a migration to a database with real data, take a backup** (below). There is no
automatic rollback — that is the deliberate trade of forward-only migrations, and it is only safe
if the backup is recent.

---

## 8. Backups

The database and the uploaded documents are separate, and **both** are needed to restore.

```bash
# database
docker compose exec -T db pg_dump -U postgres mlmsitty | gzip > mlmsitty-$(date +%F).sql.gz

# documents (NIC images, bank slips) — the KYC evidence
docker run --rm -v mlmsittu_documents:/d -v "$PWD":/out alpine \
    tar czf /out/documents-$(date +%F).tar.gz -C /d .
```

Copy both off the instance — to S3, on a schedule. A backup that lives on the machine it protects
is not a backup.

**Practise the restore before you need it**, and write down how long it took: that number is your
recovery time, and guessing it is how an outage becomes a crisis.

---

## 9. If it does not come up

These are not hypothetical. The same images were run locally first, and every one of these
happened there — which is the point of doing that before touching a server.

### `dependency failed to start: container mlmsittu-db is unhealthy`

```bash
docker compose logs db
```

If it says `/var/lib/postgresql/data (unused mount/volume)`:

**PostgreSQL 18 images changed where the data volume mounts.** They store data in a
major-version subdirectory so `pg_upgrade --link` works without crossing a mount boundary, so the
volume goes at `/var/lib/postgresql`, **not** `/var/lib/postgresql/data` — which was correct for
every earlier version and is what most guides still say. The compose file here is already right;
this note is for when you adapt it.

Correcting the mount is not enough on its own. The bad volume persists and the container keeps
finding it, so it has to be removed:

```bash
docker compose down -v        # DELETES the database - only safe before there is real data
```

**If the database already holds real data, do not run that.** Take a backup first (section 8),
then restore into the corrected volume.

### A container is `unhealthy` but the site works

Suspect the probe before the service.

```bash
docker inspect mlmsittu-web --format "{{json .State.Health}}"
```

That prints the last five attempts with their output. The web container failed 19 checks in a row
locally while serving pages perfectly: the probe asked for `localhost`, which resolves to `::1`
inside the container, and `nginx.conf` has `listen 80;` — IPv4 only. It now uses `127.0.0.1`.

### `502 Bad Gateway` from nginx

The application is not up yet. Normal for the first 30-60 seconds after `up -d`, and after any
restart of `app`. `docker compose logs -f app` and wait for `Started MlmsittuApplication`.

If it persists, the app container is not running at all — see below.

### The app container restarts in a loop

```bash
docker compose logs app | tail -50
```

- **`Could not resolve placeholder 'DB_URL'`** — a variable missing from `.env`.
- **`NIC protection is still using development secrets`** — the deliberate refusal from section 2.
  Set both secrets and restart.
- **`FlywayValidateException` / checksum mismatch** — a migration file changed after it was
  applied. Read the two checksums in the log, then work out *what* changed before doing anything:

  - **The statements changed.** The database no longer matches what the file says it should be.
    Restore the backup and deploy again. Editing `flyway_schema_history` here hides a real
    divergence and every later migration builds on the lie.
  - **Only comments changed, or a guard that provably does nothing on this data.** Then the
    database is already correct and the checksum is simply stale. Repairing it is the right fix:

    ```sql
    UPDATE flyway_schema_history SET checksum = <resolved locally> WHERE version = '<n>';
    ```

    That is precisely what `flyway repair` does. Verify the claim first — check the schema really
    does have the change — rather than assuming the edit was harmless.

  The way to avoid the question entirely: **never edit a migration that has been applied
  anywhere.** Write the next one instead.
- **`password authentication failed for user "mlmsittu_app"`** — `DB_PASSWORD` no longer matches
  the role. `01-app-role.sql` runs **once**, on the database's first ever start, so changing the
  password in `.env` later does not change the role. Change it in the database by hand:
  `ALTER ROLE mlmsittu_app PASSWORD '...';`

### Health says DOWN but the application seems fine

```bash
curl -fsS http://localhost/actuator/health
```

Something optional is failing its check, and because the Dockerfile's HEALTHCHECK uses this
endpoint, a DOWN marks the whole container unhealthy. Mail was the culprit locally, and is now
excluded from the health check for exactly that reason (section 5).

---

## 10. Operating notes

| Task | Command |
|---|---|
| Logs | `docker compose logs -f app` |
| Restart just the app | `docker compose restart app` |
| Database shell | `docker compose exec db psql -U postgres -d mlmsitty` |
| Disk usage | `docker system df` |
| Stop everything | `docker compose down` (volumes survive) |
| **Destroy the data** | `docker compose down -v` — this deletes the database and documents |

**Sessions survive a restart** now that they live in PostgreSQL, so `docker compose restart app`
does not log everyone out.

**Running two app containers** is supported — sessions, rate limits and the outbox are all shared
through the database — but set `JOBS_SCHEDULER_ENABLED=false` on all but one, or the reorder scan
and session cleanup run once per container.

---

## What is deliberately not here

The plan's Phase 8 also called for a CI pipeline, a separate staging environment, and blue-green
deployment with automatic rollback. Those were dropped at your request in favour of deploying by
hand. Worth knowing what that costs, so it is a choice rather than a surprise:

- **No automated checks before a deploy.** Run `./gradlew test` and `npm run build` yourself first;
  the image build deliberately skips tests, because a build container has no database and a
  silently skipped test suite is worse than an honestly absent one.
- **No rollback.** `docker compose up -d --build` replaces the running container. Keep the previous
  image (`docker image ls`) so you can retag and restart if a deploy goes wrong.
- **Brief downtime on every deploy** — a few seconds while the new container starts.

None of that blocks going live. It is the difference between a deployment you watch and one that
watches itself.
