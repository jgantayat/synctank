// SyncTank contract-check — step 4: the pull-request comment.
//
// Moved out of contract.yml's inline github-script block (Day 05/06) into a file so it can be
// unit-tested (test/comment.test.js) and reused. The rendering of each change line, blast
// radius and migration patch is unchanged. Three behaviours are new (Day 12 F1, F5):
//
//   1. ONE comment per API per pull request. It is found by a hidden marker and edited in
//      place on every push, instead of a new comment per push.
//   2. No classified changes -> no comment. If an earlier push HAD changes, the existing
//      comment is rewritten to say they are gone, rather than left stale.
//   3. A missing or broken report.json falls back to the deterministic diff report, and a
//      403/404 from the API (fork PRs get a read-only token) is a warning, not a failed job.
'use strict';

const fs = require('fs');

const MARKER_PREFIX = '<!-- synctank-contract-check:';

function marker(specKey) {
  return `${MARKER_PREFIX}${specKey} -->`;
}

function readJson(path) {
  if (!path) return null;
  try {
    return JSON.parse(fs.readFileSync(path, 'utf8'));
  } catch (e) {
    return null;
  }
}

/** report.json when it is a real report, otherwise the same shape built from diff-report.json. */
function resolveReport(report, diff) {
  if (report && Array.isArray(report.changes)) {
    return { ...report, degraded: false };
  }
  return {
    summary: 'The change report service was unavailable this run — showing the deterministic classification and Impact Radar data directly.',
    changes: ((diff && diff.changes) || []).map(c => ({
      location: c.location,
      severity: c.severity,
      plainEnglish: c.description,
      usageHits: null, // unknown, not "none"
    })),
    suggestedMigrationPatch: '',
    impact: (diff && diff.impact) || [],
    degraded: true,
  };
}

function blastRadius(assessment, env) {
  // v1.0.1 — with no consumer evidence the gate IGNORES the radar (Day 12 F3), so the radar's
  // "downgraded to SAFE_WITH_NOTE — the merge is unblocked" must not be printed as if it counted.
  // It contradicted the Gate line directly below it on the adopter demo PR.
  if (env.REGISTRY_SEEDED !== 'true') {
    return '\n  - **Blast radius:** not assessed — no consumer evidence for this run, so the gate used the classifier\'s severity';
  }
  if (!assessment) return '';
  const consumers = assessment.consumers || [];
  if (consumers.length === 0) {
    return `\n  - **Blast radius:** no registered consumer — _${assessment.verdict}_`;
  }
  const rows = consumers.map(c =>
    `${c.appName} (${c.team || 'unknown team'}) — screens: ${(c.screens || []).join(', ') || 'unmapped'}` +
    (c.callsPerDay ? `, ~${Number(c.callsPerDay).toLocaleString('en-US')} calls/day` : '')
  ).join('; ');
  const escalated = assessment.classifiedSeverity !== assessment.effectiveSeverity
    ? ` → **${assessment.effectiveSeverity}** (_${assessment.verdict}_)`
    : '';
  return `\n  - **Blast radius:** ${rows}${escalated}`;
}

function usageLine(change, env) {
  if (!env.FRONTEND_LABEL) return '';                       // no consumer source configured
  if (change.usageHits === null || change.usageHits === undefined) {
    return '\n  - Usage scan unavailable this run';
  }
  if (change.usageHits.length === 0) {
    return `\n  - No usages found in ${env.FRONTEND_LABEL}`;
  }
  return `\n  - Used in: ${change.usageHits.map(u => `\`${useSite(u)}\``).join(', ')}`;
}

// A hit is "path:line:matched text". The old inline script used u.split('/').pop(), which cut
// at the LAST slash — inside the matched text whenever that text held a `//` comment or a URL.
// file:line is what a reviewer needs to jump to the use site.
function useSite(hit) {
  const [path, line] = String(hit).split(':');
  const file = path.split('/').pop();
  return /^\d+$/.test(line || '') ? `${file}:${line}` : file;
}

function gateLine(env) {
  const gate = env.GATE_SEVERITY;
  const basis = env.REGISTRY_SEEDED === 'true'
    ? 'after the Impact Radar'
    : 'classifier severity — no Impact Radar evidence';
  if (gate === 'BREAKING') {
    if (env.BREAKING_APPROVED === 'true') {
      return `\n\n**Gate:** \`BREAKING\` (${basis}) — **approved** by the \`${env.APPROVAL_LABEL}\` label.`;
    }
    const hint = env.APPROVAL_LABEL
      ? ` If it is intentional and every consumer is updated, add the \`${env.APPROVAL_LABEL}\` label.`
      : '';
    return `\n\n**Gate:** \`BREAKING\` (${basis}) — merge blocked.${hint}`;
  }
  return `\n\n**Gate:** \`${gate}\` (${basis}).`;
}

/** The comment body for a pull request with at least one classified change. */
function buildBody(env, rawReport, diff) {
  const report = resolveReport(rawReport, diff);

  const impactByLocation = {};
  for (const assessment of (report.impact || [])) {
    impactByLocation[assessment.location] = assessment;
  }

  const changeLines = report.changes.map(c =>
    `- **${c.severity}** \`${c.location}\` — ${c.plainEnglish}` +
    usageLine(c, env) +
    blastRadius(impactByLocation[c.location], env)
  ).join('\n');

  const patchBlock = report.suggestedMigrationPatch
    ? `\n\n**Suggested migration:**\n\`\`\`diff\n${report.suggestedMigrationPatch}\n\`\`\`\n\n_Advisory only — review before applying._`
    : '';

  // v1.0.1 — only when the radar's verdict is the one the gate used.
  const verdictLine = env.REGISTRY_SEEDED === 'true'
    && env.SEVERITY && env.EFFECTIVE_SEVERITY && env.SEVERITY !== env.EFFECTIVE_SEVERITY
    ? `\n\n> **Impact Radar re-weighted this PR:** classified \`${env.SEVERITY}\` → effective \`${env.EFFECTIVE_SEVERITY}\`.`
    : '';

  // v1.0.1 — the separate "AI narration is not configured" note is gone. The platform now says
  // so itself in the summary (and no longer attempts the call), so the comment said it twice,
  // once as a failure that never happened.

  const telemetryNote = env.DEMO_TELEMETRY
    ? '\n\n<sub>Call volumes are seeded demo telemetry, not live measurements.</sub>'
    : '';

  return `${marker(env.SPEC_KEY)}\n### 🤖 Contract change report — \`${env.SPEC_KEY}\`\n\n` +
    `${report.summary}${verdictLine}\n\n${changeLines}${patchBlock}${gateLine(env)}${telemetryNote}`;
}

function resolvedBody(env) {
  return `${marker(env.SPEC_KEY)}\n### ✅ Contract check — \`${env.SPEC_KEY}\`\n\n` +
    'No API contract changes in the latest commit. An earlier commit on this pull request had some; ' +
    'this comment was updated so it does not describe changes that are no longer here.';
}

async function run({ github, context, core, env = process.env }) {
  const issueNumber = (context.payload.pull_request && context.payload.pull_request.number) || context.issue.number;
  if (!issueNumber) {
    core.info('Not a pull request — no comment.');
    return 'skipped';
  }
  const { owner, repo } = context.repo;
  const count = Number(env.CHANGE_COUNT || '0');
  const tag = marker(env.SPEC_KEY);

  try {
    const comments = await github.paginate(github.rest.issues.listComments, {
      owner, repo, issue_number: issueNumber, per_page: 100,
    });
    const existing = comments.find(c => typeof c.body === 'string' && c.body.includes(tag));

    let body;
    if (count === 0) {
      if (!existing) {
        core.info('No classified contract changes and no earlier report — nothing to post.');
        return 'skipped';
      }
      body = resolvedBody(env);
    } else {
      body = buildBody(env, readJson(env.REPORT_PATH), readJson(env.DIFF_REPORT_PATH));
    }

    if (existing) {
      await github.rest.issues.updateComment({ owner, repo, comment_id: existing.id, body });
      core.info(`Updated contract report comment ${existing.id}`);
      return 'updated';
    }
    await github.rest.issues.createComment({ owner, repo, issue_number: issueNumber, body });
    core.info('Posted contract report comment');
    return 'created';
  } catch (e) {
    if (e.status === 403 || e.status === 404) {
      core.warning(`Could not write the contract report comment (HTTP ${e.status}). A fork pull request gets a read-only token; the gate result and the artifact are unaffected.`);
      return 'forbidden';
    }
    throw e;
  }
}

module.exports = run;
module.exports.buildBody = buildBody;
module.exports.resolvedBody = resolvedBody;
module.exports.marker = marker;