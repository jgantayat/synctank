/**
 * Day 11 (F2) — where the two backends live, resolved at RUNTIME.
 *
 * Until today `http://localhost:8081` was a const in dashboard.ts and again in
 * contract-agent.ts, and `http://localhost:8080` was a literal in app.config.ts. That was
 * correct while everything ran on one laptop and became a blocker the moment the platform
 * moved to ECS: repointing the dashboard meant editing three files and rebuilding.
 *
 * WHY A RUNTIME FILE AND NOT Angular's fileReplacements: `public/config.js` is copied
 * verbatim into the build output, so demo day is "edit one line, refresh the browser"
 * rather than "edit, rebuild, redeploy". On a stage that difference is the whole point.
 *
 * The defaults below are exactly the values that were hard-coded before, so every
 * existing spec — which asserts on http://localhost:8081/... — passes unchanged. That is
 * deliberate: an unchanged test suite is the evidence this refactor changed no behaviour.
 */
export interface SyncTankConfig {
  platformBase?: string;
  ordersBase?: string;
}

declare global {
  interface Window {
    syncTankConfig?: SyncTankConfig;
  }
}

function resolve(key: keyof SyncTankConfig, fallback: string): string {
  const configured = typeof window === 'undefined' ? undefined : window.syncTankConfig?.[key];
  const value = configured && configured.trim() ? configured.trim() : fallback;
  // Trailing slashes are the classic config-file typo: every call site here builds URLs
  // as `${BASE}/path`, so 'http://host/' would produce 'http://host//path'.
  return value.replace(/\/+$/, '');
}

/** contract-platform: spec history, registry, Impact Radar, Contract Agent. */
export const PLATFORM_BASE = resolve('platformBase', 'http://localhost:8081');

/** orders-backend: the sample API the generated client calls. Stays local for the demo. */
export const ORDERS_BASE = resolve('ordersBase', 'http://localhost:8080');