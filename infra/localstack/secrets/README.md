# Local secret for LocalStack (Day 10)

`secrets.local.json` in this directory is read by `infra/localstack/init/ready.d/10-synctank-secret.sh`
every time LocalStack starts, and becomes the `synctank/platform` secret that contract-platform
fetches when `SECRETS_PROVIDER=aws`.

```bash
cp infra/localstack/secrets/secrets.example.json infra/localstack/secrets/secrets.local.json
# edit secrets.local.json — real Anthropic key and GitHub PAT, or leave those two blank
docker compose up -d --force-recreate localstack
```

`secrets.local.json` is **gitignored** (root `.gitignore`, Day 09 rule `secrets.local.json`) and
**excluded from the Docker build context** (`contract-platform/.dockerignore`). Check before every commit:

```bash
git check-ignore -v infra/localstack/secrets/secrets.local.json   # must print the matching rule
git status --porcelain infra/                                     # must NOT list secrets.local.json
```

The five keys and what each one does are defined in
`contract-platform/src/main/java/com/synctank/platform/config/secrets/SecretMapping.java`.
`anthropicApiKey` and `githubToken` are optional (blank = AI fallback report / draft-only agent);
`dbPassword` is required; the two `s3*` keys are required while the spec store is MinIO.

This is local convenience only. On AWS (Day 11) the secret is created once with the AWS CLI and
read through the ECS task role — no file, no hook.