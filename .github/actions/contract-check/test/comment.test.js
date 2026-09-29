// Day 12 — unit tests for the PR comment renderer. Plain Node, no dependencies:
//   node --test .github/actions/contract-check/test/
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const run = require('../scripts/comment.js');
const { buildBody, marker } = run;

const baseEnv = {
  SPEC_KEY: 'orders-backend',
  CHANGE_COUNT: '1',
  SEVERITY: 'BREAKING',
  EFFECTIVE_SEVERITY: 'BREAKING',
  GATE_SEVERITY: 'BREAKING',
  REGISTRY_SEEDED: 'true',
  FRONTEND_LABEL: 'orders-frontend/src',
  AI_CONFIGURED: 'true',
  APPROVAL_LABEL: 'contract:breaking-approved',
  BREAKING_APPROVED: 'false',
  DEMO_TELEMETRY: '{"GET /api/orders/{id}": 12000}',
};

const diff = {
  changed: true,
  highestSeverity: 'BREAKING',
  effectiveSeverity: 'BREAKING',
  changes: [{ severity: 'BREAKING', category: 'FIELD_REMOVED', location: 'OrderResponse.status',
              description: 'Field removed.' }],
  impact: [{ location: 'OrderResponse.status', classifiedSeverity: 'BREAKING', effectiveSeverity: 'BREAKING',
             verdict: '1 registered consumer(s) affected, ~12000 calls/day.',
             consumers: [{ appName: 'customer-portal', team: 'Team Checkout', screens: ['order-detail'],
                           useSites: [], callsPerDay: 12000 }], totalCallsPerDay: 12000 }],
};

const report = {
  summary: 'Removes OrderResponse.status.',
  changes: [{ location: 'OrderResponse.status', severity: 'BREAKING', plainEnglish: 'status is gone.',
              usageHits: ['/w/orders-frontend/src/app/order-detail/order-detail.html:12:  {{ o.status }} // see http://x/y'] }],
  suggestedMigrationPatch: '',
  openQuestions: [],
  impact: diff.impact,
};

test('a real report renders marker, use site, blast radius, gate and telemetry note', () => {
  const body = buildBody(baseEnv, report, diff);
  assert.ok(body.startsWith(marker('orders-backend')));
  assert.match(body, /`order-detail\.html:12`/);          // file:line, not text after the last slash
  assert.match(body, /customer-portal \(Team Checkout\) — screens: order-detail, ~12,000 calls\/day/);
  assert.match(body, /merge blocked\. If it is intentional.*`contract:breaking-approved`/);
  assert.match(body, /seeded demo telemetry/);
});

test('an error body in report.json falls back to the deterministic diff', () => {
  const body = buildBody(baseEnv, { status: 500, error: 'Internal Server Error' }, diff);
  assert.match(body, /change report service was unavailable/);
  assert.match(body, /Field removed\./);
  assert.match(body, /Usage scan unavailable this run/);   // unknown is not the same as "none"
});

test('no frontend-src means no usage line and the gate names its basis', () => {
  const env = { ...baseEnv, FRONTEND_LABEL: '', REGISTRY_SEEDED: 'false', DEMO_TELEMETRY: '' };
  const body = buildBody(env, report, diff);
  assert.doesNotMatch(body, /Used in|No usages found/);
  assert.match(body, /no Impact Radar evidence/);
  assert.doesNotMatch(body, /seeded demo telemetry/);
});

test('an approved breaking change says so', () => {
  const body = buildBody({ ...baseEnv, BREAKING_APPROVED: 'true' }, report, diff);
  assert.match(body, /\*\*approved\*\* by the `contract:breaking-approved` label/);
});

// ---------- run(): create / update / skip / forbidden ----------

function fakeGithub(existingComments, { failWith } = {}) {
  const calls = [];
  const rest = {
    issues: {
      listComments: 'listComments',
      createComment: async args => { if (failWith) throw Object.assign(new Error('x'), { status: failWith }); calls.push(['create', args]); },
      updateComment: async args => { calls.push(['update', args]); },
    },
  };
  return { calls, github: { rest, paginate: async () => existingComments } };
}
const core = { info() {}, warning() {} };
const context = { repo: { owner: 'o', repo: 'r' }, issue: { number: 7 }, payload: { pull_request: { number: 7 } } };

function withFiles() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-'));
  fs.writeFileSync(path.join(dir, 'report.json'), JSON.stringify(report));
  fs.writeFileSync(path.join(dir, 'diff.json'), JSON.stringify(diff));
  return { REPORT_PATH: path.join(dir, 'report.json'), DIFF_REPORT_PATH: path.join(dir, 'diff.json') };
}

test('first push with changes creates one comment', async () => {
  const { github, calls } = fakeGithub([]);
  const result = await run({ github, context, core, env: { ...baseEnv, ...withFiles() } });
  assert.equal(result, 'created');
  assert.equal(calls.length, 1);
});

test('a later push edits the same comment instead of adding another', async () => {
  const { github, calls } = fakeGithub([{ id: 42, body: `${marker('orders-backend')}\nold` },
                                        { id: 43, body: `${marker('other-api')}\nnot ours` }]);
  const result = await run({ github, context, core, env: { ...baseEnv, ...withFiles() } });
  assert.equal(result, 'updated');
  assert.equal(calls[0][1].comment_id, 42);
});

test('no changes and no earlier comment posts nothing (Day 12 F1)', async () => {
  const { github, calls } = fakeGithub([]);
  const result = await run({ github, context, core, env: { ...baseEnv, CHANGE_COUNT: '0' } });
  assert.equal(result, 'skipped');
  assert.equal(calls.length, 0);
});

test('changes that disappear rewrite the earlier comment', async () => {
  const { github, calls } = fakeGithub([{ id: 42, body: `${marker('orders-backend')}\nold` }]);
  const result = await run({ github, context, core, env: { ...baseEnv, CHANGE_COUNT: '0' } });
  assert.equal(result, 'updated');
  assert.match(calls[0][1].body, /No API contract changes in the latest commit/);
});

test('a read-only token (fork PR) is a warning, not a failure', async () => {
  const { github } = fakeGithub([], { failWith: 403 });
  const result = await run({ github, context, core, env: { ...baseEnv, ...withFiles() } });
  assert.equal(result, 'forbidden');
});