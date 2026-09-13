# SyncTank — AWS definitions (Day 10)

Written on Day 10, **applied on Day 11**. Nothing here is executed by the platform or by CI.
IAM policy files are pure JSON (IAM has no comment syntax), so every justification lives here.

## Files

| File | Attached to | Purpose |
|---|---|---|
| `iam/contract-platform-task-role-policy.json` | **task role** `synctank-contract-platform-task` | What the *application* may do: read one secret, read/write spec objects. |
| `iam/ecs-tasks-trust-policy.json` | both roles (trust relationship) | Only ECS tasks in *this* account may assume them. |
| `iam/ecs-task-execution-role-policy.json` | **execution role** `synctank-contract-platform-execution` | What the *ECS agent* may do before the app starts: pull this image, write this log group. |
| `ecs/contract-platform-task-definition.json` | Fargate task definition | Wires the image, both roles, env, health check, logs, read-only root FS. |

Two roles, deliberately. The execution role is used by ECS itself and has **no** access to the
secret — the application fetches it at startup with the task role (Day 09's
`SecretsInitializer`), so the credential never passes through ECS's `secrets:` injection or the
task definition.

## Placeholders

`${AWS_ACCOUNT_ID}` `${AWS_REGION}` `${SPEC_BUCKET}` `${IMAGE_TAG}` `${DB_HOST}` `${DASHBOARD_ORIGIN}`

Render with `envsubst` (macOS: `brew install gettext`), naming the variables explicitly so nothing
else in the file is touched:

```bash
export AWS_ACCOUNT_ID=123456789012 AWS_REGION=us-east-1 SPEC_BUCKET=synctank-specs-123456789012
envsubst '${AWS_ACCOUNT_ID} ${AWS_REGION} ${SPEC_BUCKET}' \
  < iam/contract-platform-task-role-policy.json > /tmp/task-role-policy.json
```

## Every permission, traced to the line of code that needs it

| Code path | AWS API call | IAM action granted | Resource |
|---|---|---|---|
| `AwsSecretBundleLoader.readSecretString` | `GetSecretValue` | `secretsmanager:GetSecretValue` | `secret:synctank/platform-??????` |
| `SpecStore.putSpec`, `setBaseline` | `PutObject` | `s3:PutObject` | `${SPEC_BUCKET}/specs/*` |
| `SpecStore.getBaselineSpec`, `baselineCommit`, `listVersions` | `GetObject` | `s3:GetObject` | `${SPEC_BUCKET}/specs/*` |
| `SpecStore.listVersions` | `ListObjectsV2` | `s3:ListBucket` | `${SPEC_BUCKET}` |
| `BucketInitializer.run` | `HeadBucket` | `s3:ListBucket` (same grant) | `${SPEC_BUCKET}` |

**Not granted, on purpose:**

- `s3:CreateBucket` — the bucket is provisioned infrastructure; `S3_CREATE_BUCKET=false` makes
  `BucketInitializer` fail fast with instructions instead of attempting it (finding F4).
- `s3:DeleteObject` — nothing in the platform deletes a spec version.
- `secretsmanager:DescribeSecret` — Day 09's Appendix A included it so the CLI checks would work
  under the same role. The running task never calls it, so the task role does not get it.
- `bedrock:*` — the platform calls Anthropic's API directly (`spring-ai-starter-model-anthropic`).
  Granting Bedrock access nothing uses would be permission for its own sake. See the Day 10
  guide, Appendix A, for the statement to add if and when the model provider changes.
- `kms:Decrypt` — not needed while the secret uses the default `aws/secretsmanager` key and the
  bucket uses SSE-S3. Add it, scoped to the key ARN, only if either moves to a customer-managed key.

**Two non-obvious details:**

1. `s3:ListBucket` has **no** `s3:prefix` condition. `HeadBucket` sends no prefix, so a
   `StringLike s3:prefix specs/*` condition would deny it and the platform would fail at startup
   with a 403. The bucket is dedicated to the platform, so the unconditioned grant exposes nothing
   the object grant does not already cover.
2. `s3:ListBucket` is also what makes a missing object return **404 NoSuchKey** instead of
   **403 AccessDenied**. `SpecStore.baselineCommit` relies on catching `NoSuchKeyException` on a
   fresh bucket; without `ListBucket` it would get a 403 and the spec timeline would 500.

## Validate before Day 11 (optional today — needs an AWS account)

```bash
aws accessanalyzer validate-policy --policy-type IDENTITY_POLICY \
  --policy-document file:///tmp/task-role-policy.json
```

Expect `"findings": []`. LocalStack Community does not enforce IAM, so enforcement itself is
first observable on Day 11.