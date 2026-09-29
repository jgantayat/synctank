# SyncTank Contract Check

A GitHub Action that turns API contract drift into a failed check with an explanation attached.

On every pull request it:

1. diffs the OpenAPI spec your backend produces against the baseline committed on your default branch;
2. classifies every change as **ADDITIVE**, **DANGEROUS** or **BREAKING** with deterministic rules — never an AI;
3. optionally weighs each change against a consumer that really compiles against your generated client (the **Impact Radar**);
4. posts **one** plain-English report comment per API and keeps it up to date as you push;
5. fails the check on an unapproved BREAKING change.

Everything runs on the job's own runner: a throwaway contract-platform, an S3 store (LocalStack) and Postgres start for the job and are gone when it ends. No SyncTank server, no account, no data leaves the runner except the optional call to Anthropic for the narration.

## Minimum adoption

```yaml
permissions:
  contents: read
  pull-requests: write          # for the report comment

jobs:
  contract:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      # ... your existing step that writes the candidate spec, e.g. `mvn verify` ...
      - uses: jgantayat/synctank/.github/actions/contract-check@v1
        with:
          candidate-spec: my-service/target/openapi.json
          baseline-spec: my-service/contract/openapi.baseline.json
          spec-key: my-service
```

Commit a baseline once (`cp my-service/target/openapi.json my-service/contract/openapi.baseline.json`) or have your default-branch pipeline commit it after each merge — see SyncTank's own `.github/workflows/contract.yml`, step *Commit baseline file to repo*. Until a baseline exists the check reports "no baseline" and passes.

## Inputs

| Input | Default | Meaning |
|---|---|---|
| `candidate-spec` | — (required) | OpenAPI JSON this commit produces |
| `baseline-spec` | — (required) | Committed baseline. Missing file = "no baseline", passes |
| `spec-key` | — (required) | Stable API name: letters, digits, `.` `_` `-` |
| `frontend-src` | `''` | A consumer's source directory. Turns on the Impact Radar |
| `consumer-app-name` | `''` | Required with `frontend-src` |
| `consumer-team` | `''` | Shown in the blast radius |
| `consumer-repo` | the current repository | Recorded with the consumer |
| `demo-telemetry` | `''` | JSON `{"GET /path": callsPerDay}`. **Seeded, not measured**; the comment says so |
| `anthropic-api-key` | `''` | Enables the AI narration. Without it the report is deterministic |
| `github-token` | `github.token` | Writes the comment |
| `comment-on-pr` | `true` | One comment per API per PR, edited in place |
| `fail-on-breaking` | `pull-request` | `pull-request`, `always` or `never` |
| `breaking-approval-label` | `contract:breaking-approved` | PR label that turns a BREAKING failure into a recorded warning. `''` disables |
| `platform-port` | `18081` | Local port for the throwaway platform |
| `upload-artifacts` | `true` | Uploads `contract-check-<spec-key>-<sha>` |

## Outputs

`status` (`no-baseline` / `diffed`), `changed`, `change-count`, `severity`, `effective-severity`, `registry-seeded`, `gate-severity`, `diff-report`, `report`.

## How the gate decides

| Situation | The gate acts on |
|---|---|
| No classified changes | nothing — `NONE`, no comment |
| `frontend-src` given **and** the consumer's usage was found | the **effective** severity after the Impact Radar (a BREAKING change nobody compiles against becomes `SAFE_WITH_NOTE`; a DANGEROUS change on a busy endpoint becomes BREAKING) |
| No `frontend-src`, or the consumer's usage came back empty | the **classifier's** severity. An empty registry is not evidence that nothing breaks |

`fail-on-breaking: pull-request` fails pull requests only. A push to the default branch is past the merge; failing it only stops the baseline from advancing, which would make every later pull request fail against a stale contract.

## Intentional breaking changes

Add the `contract:breaking-approved` label to the pull request. The run is repeated automatically if your workflow listens for `labeled`/`unlabeled` (SyncTank's does), the check passes with a warning, and the comment records the approval. Anyone who can label pull requests can approve, so pair this with a CODEOWNERS review on the API directory.

## Requirements

`ubuntu-*` runner with Docker (GitHub-hosted runners have it). The action installs Java 21 and ripgrep itself and adds about two minutes to a job, mostly building the platform.

## Limits (MVP)

- The platform is rebuilt from source on every run; there is no published image yet.
- Spec history is not persisted between runs. The durable record is the workflow artifact and your committed baseline.
- Impact Radar consumer mapping is registry-based (who compiles against what); call volumes are seeded demo telemetry.