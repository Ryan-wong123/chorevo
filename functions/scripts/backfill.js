#!/usr/bin/env node
// One-off backfill for the discovery and privacy changes. Run from functions/:
//
//   GOOGLE_APPLICATION_CREDENTIALS=<credentials.json> node scripts/backfill.js
//
// It fills the server-managed fields on every dance (visibility, feedReady,
// danceTypeKey, searchTokens, shuffleKey, exact likeCount, trendingScore),
// rebuilds meta/danceCategories, and adds uid/createdAt to old like documents.
// Safe to run more than once.
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { refreshAllDances, backfillLikeUids } = require("../maintenance");

async function main() {
  initializeApp({ projectId: process.env.GCLOUD_PROJECT || "chorevo-dea05" });
  const db = getFirestore();
  console.log(await backfillLikeUids(db));
  console.log(await refreshAllDances(db, { recountLikes: true }));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
