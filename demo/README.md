# SyncTank demo kit (Day 13)

Scripts that put the live demo into a known state, run its two segments, and put everything back.
Nothing here is used by CI or by the platform. Every script is bash-3.2-safe (the macOS default)
and is run from the repo root as `bash demo/<script>`.

## Three layers of demo insurance

| Layer | What | Needs | Start with |
|---|---|---|---|
| 1 | Live, platform on AWS (ECS behind the ALB) | AWS up, venue IP allowlisted, internet | `demo/cloud-up.sh` |
| 2 | Recorded screencast of both segments | a video player | the recordings folder |
| 3 | Live, platform on this laptop (docker compose) | Docker, internet for the agent only | `demo/local-up.sh` |

Slide 3's compile break needs **no network** in any layer.

## Terminals

| | Where | Runs |
|---|---|---|
| T1 | `orders-frontend/` | `npm start` — the dashboard on http://localhost:4200 (use exactly that address: CORS) |
| T2 | repo root, `source infra/aws/deploy/00-env.sh` | all `demo/*.sh` scripts |

The Order Detail panel's orders-backend runs in the background on **:8082** (`demo/backend.sh`),
never :8080 — `mvn verify` starts its own copy on :8080 to write the spec.

## Scripts

| Script | Does |
|---|---|
| `target.sh cloud\|local\|show\|restore` | points `public/config.js` (dashboard **and** every script) at one platform |
| `cloud-up.sh` / `cloud-down.sh` | ECS service 0 → 1 (+ IP allowlist, readiness wait, seed) / 1 → 0 |
| `local-up.sh` / `local-down.sh` | compose stack incl. contract-platform on :8081 (+ seed) / stop, volumes kept |
| `backend.sh start\|stop\|restart\|status` | orders-backend on :8082 from the checked-out branch |
| `preflight.sh cloud\|local` | read-only readiness check; exit 0 = ready |
| `slide3-break.sh [--skip-backend]` | Level 1: Java change → spec → TS client → **build fails** with file:line |
| `slide3-radar.sh` | Level 2: target platform's `/diff` + Impact Radar on that change |
| `slide3-migrate.sh` | applies `slide3/order-detail.migrated.ts`, build passes, backend restarted |
| `refusal.sh` | forced proposal (BigDecimal) → guardrail G2 refuses, model never called |
| `reset.sh [--yes]` | back to main, contract regenerated, agent PRs closed, agent branches deleted |

`slide3/order-detail.migrated.ts` is the human-reviewed Slide 3 migration. It is copied over
`order-detail.ts` on the prop branch only, and `reset.sh` restores the original.

## Stage sequence

```bash
# T2, at the venue, on main
source infra/aws/deploy/00-env.sh
bash demo/cloud-up.sh            # allowlists THIS network's IP, scales to 1, seeds
bash demo/target.sh cloud
bash demo/backend.sh restart
bash demo/preflight.sh cloud     # must print READY
# T1: cd orders-frontend && npm start ; browser on http://localhost:4200

# Slide 3
git switch stage/compile-break
bash demo/slide3-break.sh        # or --skip-backend if the spec was generated backstage
bash demo/slide3-radar.sh
#   browser: the stage PR's contract comment
bash demo/slide3-migrate.sh      # refresh browser

# Slide 4 — dashboard: Draft change → Approve → PR; typed refusal; optional: bash demo/refusal.sh

# After
bash demo/reset.sh
bash demo/cloud-down.sh
bash demo/target.sh restore
```

## Rules

- **Never merge** the stage PR (`stage/compile-break`) or any `agent/*` PR. `main`'s `OrderResponse`
  must keep its four fields, or Slide 3 has nothing to break.
- **Run `reset.sh` after every run.** Agent request ids restart at 1 whenever the cloud task
  restarts; a leftover `agent/...-1` branch makes the next Approve fail.
- **Resume the cloud with `cloud-up.sh`, not `30-deploy.sh`.** `30-deploy.sh` builds its image tag
  from `git HEAD`, which is not an image in ECR once `main` has moved on.
- **`target.sh restore` before any commit** — the committed `config.js` is the localhost default.
- Call volumes are **seeded demo telemetry**. Say so; the dashboard and the PR comment already do.
