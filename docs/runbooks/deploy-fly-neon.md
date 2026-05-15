# Deploy to Fly.io with Neon Postgres

Production-ish public deployment of the scanner. ~10 minutes start to URL.

## Prerequisites

- A Fly.io account (`https://fly.io`)
- A Neon account (`https://neon.tech`) with a project created
- The repo's `.env` populated with `INFURA_API_KEY`, `ETHERSCAN_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`

## Step 1: install + auth flyctl

```bash
brew install flyctl                # macOS
# or:
curl -L https://fly.io/install.sh | sh
fly auth login                     # opens browser
```

## Step 2: create the Neon Postgres project

In the Neon UI:

1. New project, name `scanner`, region close to your Fly region
2. Wait for provisioning (10 sec)
3. Open SQL Editor, run once: `CREATE EXTENSION IF NOT EXISTS vector;`
4. From the dashboard "Connection details" panel, copy:
   - The pooled connection string (host has `-pooler`)
   - The username, password, host, database name

Neon's standard string format:
```
postgresql://<user>:<pass>@<host>.<region>.aws.neon.tech/<db>?sslmode=require
```

JDBC equivalent the scanner needs:
```
jdbc:postgresql://<host>.<region>.aws.neon.tech/<db>?sslmode=require
```

(Username and password go in separate `DATABASE_USERNAME` / `DATABASE_PASSWORD` env vars.)

## Step 3: launch the Fly app

From the repo root:

```bash
fly launch --no-deploy --copy-config
```

`--copy-config` reuses our existing `fly.toml`. Answer the prompts:

- App name: pick something globally unique, e.g. `scanner-<your-handle>`
- Region: pick the region closest to your Neon region
- Postgres: **No** (we're using Neon)
- Redis: No
- Deploy now: No

flyctl edits `fly.toml` with the chosen app name.

## Step 4: set secrets

```bash
source .env
fly secrets set \
  DATABASE_URL="jdbc:postgresql://<neon-host>/<db>?sslmode=require" \
  DATABASE_USERNAME="<neon-user>" \
  DATABASE_PASSWORD="<neon-password>" \
  INFURA_API_KEY="$INFURA_API_KEY" \
  ETHERSCAN_API_KEY="$ETHERSCAN_API_KEY" \
  OPENAI_API_KEY="$OPENAI_API_KEY" \
  ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
  ADMIN_USERNAME=admin \
  ADMIN_PASSWORD="<pick-something-real>"
```

Verify: `fly secrets list` shows nine entries.

## Step 5: deploy

```bash
fly deploy --remote-only
```

`--remote-only` builds the Docker image on Fly's builders rather than locally (faster, no local Docker pull). First deploy takes ~5 min (downloading base image + Gradle deps). Subsequent deploys are 1-2 min thanks to layer caching.

Watch the logs:

```bash
fly logs
```

Look for `Started ScannerApplicationKt in N seconds` and `Tomcat started on port 8080`.

## Step 6: smoke test the deployed instance

```bash
fly status                              # shows the *.fly.dev URL
APP_URL=https://$(fly status -j | jq -r .Hostname)
curl -fsS $APP_URL/actuator/health
curl -fsS -o /dev/null -w "%{http_code}\n" $APP_URL/
curl -fsS -u admin:$ADMIN_PASSWORD -o /dev/null -w "%{http_code}\n" $APP_URL/admin/queue
curl -fsS -u admin:$ADMIN_PASSWORD -X POST $APP_URL/admin/corpus/refresh?limit=20
curl -fsS -u admin:$ADMIN_PASSWORD -X POST $APP_URL/admin/demo-seed
open $APP_URL                           # macOS: open in browser
```

Expected:

- `/actuator/health` → `{"status":"UP"}`
- `/` → `200`, dashboard renders
- `/admin/queue` → `200` (with auth) / `401` (without)
- `/admin/corpus/refresh` → `302` redirect, ~30s wall-clock for 20 tokens
- `/admin/demo-seed` → `302`, fills the dashboard with 50 synthetic detections

## Troubleshooting

- **`Caused by: PSQLException: SSL is required for this server`** → add `?sslmode=require` to `DATABASE_URL`.
- **`error: relation "corpus_entries" does not exist`** → Flyway didn't run. Check `fly logs` for the migration step; verify the Neon role can `CREATE TABLE`.
- **`extension "vector" is not available`** → re-run `CREATE EXTENSION vector;` in the Neon SQL Editor; some regions need it run from the project owner role.
- **App crashing OOM** → `[[vm]] memory = "1gb"` in `fly.toml` is the floor; bump to `2gb` if Hibernate + Reactor both load eagerly.

## Cost ballpark

- Fly: free 3 shared-cpu-1x machines, this app uses 1. $0/mo at this load.
- Neon: free tier covers 3 GB storage + 191 compute hours/mo. The scanner is well under both.
- OpenAI: ~$0.0002 per 100-token corpus refresh; ~$0.0001 per 1000 metadata-similarity scores.
- Anthropic: per-pool evidence summary ~$0.001 per call; cached, so per-page-view cost trends to zero.

## Rollback

```bash
fly releases                   # find the last good version
fly deploy --image <prior-image-ref>
# or:
fly machine restart <id>       # if the issue is transient
```
