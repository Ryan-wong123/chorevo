// Fields the server keeps on each danceLibrary document so the app can page,
// filter, search and rank the feed with plain Firestore queries. Pure
// functions only; Firestore access lives in index.js and maintenance.js.

/** Fields only Cloud Functions may write (Firestore rules block clients). */
const SERVER_FIELDS = [
  "likeCount",
  "trendingScore",
  "feedReady",
  "danceTypeKey",
  "searchTokens",
  "searchTrigrams",
  "shuffleKey",
];

/** Longest prefix stored per word; longer search words match on this prefix. */
const MAX_TOKEN_LENGTH = 15;
/** Upper bound on searchTokens per dance (Firestore arrays are cheap but not free). */
const MAX_TOKENS = 300;

const DAY_MS = 86_400_000;

/** "Hip Hop" / "hip-hop" / "HIPHOP" → "hiphop"; blank → null. Mirrors the app's danceTypeKey. */
function danceTypeKey(raw) {
  // Bounded, so a long or odd dance type can't bloat meta/danceCategories.
  const key = String(raw || "").toLowerCase().replace(/[^\p{L}\p{N}]/gu, "").slice(0, 30);
  return key || null;
}

/** Lower-cased words (letters and digits) in a piece of text. */
function words(text) {
  return String(text || "")
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
}

/**
 * Every prefix (1–15 characters) of every word in the title, dance type and
 * creator name, plus the dance-type key. The app searches with
 * array-contains on the first word typed, so "sal", "salsa" and "maria" all
 * find "Salsa Basics" by Maria.
 */
function searchTokens(dance) {
  const tokens = new Set();
  for (const word of [
    ...words(dance.title),
    ...words(dance.danceType),
    ...words(dance.educatorName),
  ]) {
    const max = Math.min(word.length, MAX_TOKEN_LENGTH);
    for (let i = 1; i <= max; i++) tokens.add(word.slice(0, i));
  }
  const key = danceTypeKey(dance.danceType);
  if (key) tokens.add(key.slice(0, MAX_TOKEN_LENGTH));
  return [...tokens].slice(0, MAX_TOKENS);
}

/**
 * Three-letter pieces of every word in the title, dance type and creator
 * name (words shorter than 3 letters are kept whole). The app finds
 * misspelled searches with array-contains-any on the trigrams of the first
 * word typed ("hiphp" shares "hip" with "hiphop"), then ranks by edit
 * distance on the device.
 */
function searchTrigrams(dance) {
  const grams = new Set();
  for (const word of [
    ...words(dance.title),
    ...words(dance.danceType),
    ...words(dance.educatorName),
    ...(danceTypeKey(dance.danceType) ? [danceTypeKey(dance.danceType)] : []),
  ]) {
    if (word.length < 3) grams.add(word);
    else for (let i = 0; i + 3 <= word.length; i++) grams.add(word.slice(i, i + 3));
  }
  return [...grams].slice(0, MAX_TOKENS);
}

/** Missing visibility means public (uploads from before privacy existed). */
function isPublic(dance) {
  return String(dance.visibility || "public").toLowerCase() !== "private";
}

/** Matches DanceLibraryModel.isTemplateReady (a missing status reads as "ready"). */
function isTemplateReady(dance) {
  return String(dance.templateGenerationStatus || "ready").toLowerCase() === "ready";
}

/** Whether the dance belongs in everyone's feed. */
function isFeedReady(dance) {
  return dance.enabled === true && isPublic(dance) && isTemplateReady(dance);
}

/**
 * Likes decayed by age so new dances can trend: (likes + 1) / (ageDays + 2)^1.5.
 * Same formula the app used before ranking moved to the server.
 */
function trendingScore(likeCount, createdAtMs, nowMs) {
  const created = Number(createdAtMs) || nowMs;
  const ageDays = Math.max(0, nowMs - created) / DAY_MS;
  return ((Number(likeCount) || 0) + 1) / Math.pow(ageDays + 2, 1.5);
}

/**
 * Derived fields that differ from what the document holds, ready for
 * update(). Only content-derived fields are compared, so writing them back
 * does not trigger another change (no update loop). trendingScore and
 * likeCount are filled in only when missing; likes and the schedule keep them
 * current. shuffleKey is set once.
 */
function derivedFieldUpdates(dance, nowMs, random = Math.random) {
  const updates = {};
  const want = {
    feedReady: isFeedReady(dance),
    danceTypeKey: danceTypeKey(dance.danceType),
    searchTokens: searchTokens(dance),
    searchTrigrams: searchTrigrams(dance),
  };
  for (const [field, value] of Object.entries(want)) {
    if (JSON.stringify(dance[field] ?? null) !== JSON.stringify(value)) updates[field] = value;
  }
  if (dance.visibility === undefined || dance.visibility === null || dance.visibility === "") {
    updates.visibility = "public";
  }
  if (typeof dance.shuffleKey !== "number") updates.shuffleKey = random();
  if (typeof dance.likeCount !== "number") updates.likeCount = 0;
  if (typeof dance.trendingScore !== "number") {
    updates.trendingScore = trendingScore(dance.likeCount, dance.createdAt, nowMs);
  }
  return updates;
}

/**
 * The chip a dance counts towards in the categories document, or null when
 * it is not in the feed.
 */
function categoryContribution(dance) {
  if (!dance || !isFeedReady(dance)) return null;
  const key = danceTypeKey(dance.danceType);
  return key ? { key, label: String(dance.danceType).trim().slice(0, 40) } : null;
}

/**
 * Categories document content from every feed dance: counts per key and the
 * most common spelling as the label.
 */
function buildCategories(dances) {
  const counts = {};
  const spellings = {};
  for (const dance of dances) {
    const c = categoryContribution(dance);
    if (!c) continue;
    counts[c.key] = (counts[c.key] || 0) + 1;
    spellings[c.key] = spellings[c.key] || {};
    spellings[c.key][c.label] = (spellings[c.key][c.label] || 0) + 1;
  }
  const labels = {};
  for (const [key, bySpelling] of Object.entries(spellings)) {
    labels[key] = Object.entries(bySpelling).sort((a, b) => b[1] - a[1])[0][0];
  }
  return { counts, labels };
}

module.exports = {
  SERVER_FIELDS,
  MAX_TOKEN_LENGTH,
  danceTypeKey,
  searchTokens,
  searchTrigrams,
  isPublic,
  isTemplateReady,
  isFeedReady,
  trendingScore,
  derivedFieldUpdates,
  categoryContribution,
  buildCategories,
};
