// Day 11 — runtime configuration, read by src/app/api-config.ts before the app boots.
//
// This file is an ASSET: the Angular builder copies it into the build output untouched and
// never hashes it, so it can be edited in place on a deployed dashboard and picked up on a
// browser refresh. No rebuild, no redeploy.
//
// Committed with the local values, which is the state every developer and every test wants.
// For the cloud demo, point platformBase at the ALB (see the Day 11 guide, §9.1) — and put
// it back before committing anything.
//
// ordersBase stays local on purpose: only contract-platform is deployed. orders-backend is
// the SAMPLE API and runs on your laptop, which is exactly where the compile-break demo
// needs it to be.
window.syncTankConfig = {
  platformBase: 'http://localhost:8081',
  ordersBase: 'http://localhost:8080',
};