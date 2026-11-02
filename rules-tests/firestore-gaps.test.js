// Firestore rules not covered by the other files: weekly challenge documents,
// friends, the legacy streak path, like/comment edge cases, the retired
// forum's deletes, and the default deny for unknown collections.
const test = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs,
} = require("firebase/firestore");

let env;
const root = path.join(__dirname, "..");

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-chorevo-firestore-gaps",
    firestore: { rules: fs.readFileSync(path.join(root, "firestore.rules"), "utf8") },
  });
});

test.after(async () => {
  await env?.cleanup();
});

const as = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();
const now = () => Date.now();

test.beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "users/alice"), { uid: "alice", displayName: "Alice" });
    await setDoc(doc(db, "users/alice/weeklyChallenges/2026-W44"), { weekId: "2026-W44", progress: 2, target: 5 });
    await setDoc(doc(db, "users/alice/streak/current"), { count: 3 });
    await setDoc(doc(db, "users/alice/friends/bob"), { uid: "bob", displayName: "Bob" });
    await setDoc(doc(db, "danceLibrary/d1"), {
      danceId: "d1", educatorUid: "alice", visibility: "public", title: "Salsa", createdAt: now(), enabled: true,
    });
    await setDoc(doc(db, "communityPosts/p1"), { uid: "alice", body: "old forum post" });
    await setDoc(doc(db, "communityPosts/p1/replies/r1"), { uid: "bob", body: "old reply" });
  });
});

// ── users/{uid}/weeklyChallenges ────────────────────────────────────────────

test("weekly challenges: only the owner reads them, and a single doc or the whole list works", async () => {
  await assertSucceeds(getDoc(doc(as("alice"), "users/alice/weeklyChallenges/2026-W44")));
  await assertSucceeds(getDocs(collection(as("alice"), "users/alice/weeklyChallenges")));
  await assertFails(getDoc(doc(as("bob"), "users/alice/weeklyChallenges/2026-W44")));
  await assertFails(getDocs(collection(as("bob"), "users/alice/weeklyChallenges")));
  await assertFails(getDoc(doc(anon(), "users/alice/weeklyChallenges/2026-W44")));
});

test("weekly challenges: the app can't create, edit or delete them, not even the owner", async () => {
  const alice = as("alice");
  await assertFails(setDoc(doc(alice, "users/alice/weeklyChallenges/2026-W45"), { progress: 99 }));
  await assertFails(updateDoc(doc(alice, "users/alice/weeklyChallenges/2026-W44"), { progress: 5 }));
  await assertFails(deleteDoc(doc(alice, "users/alice/weeklyChallenges/2026-W44")));
  await assertFails(setDoc(doc(as("bob"), "users/alice/weeklyChallenges/2026-W45"), { progress: 99 }));
  await assertFails(setDoc(doc(anon(), "users/alice/weeklyChallenges/2026-W45"), { progress: 99 }));
});

// ── Legacy streak path ──────────────────────────────────────────────────────

test("legacy streak document is readable by its owner only and never writable", async () => {
  await assertSucceeds(getDoc(doc(as("alice"), "users/alice/streak/current")));
  await assertFails(getDoc(doc(as("bob"), "users/alice/streak/current")));
  await assertFails(setDoc(doc(as("alice"), "users/alice/streak/current"), { count: 100 }));
  await assertFails(deleteDoc(doc(as("alice"), "users/alice/streak/current")));
});

// ── Friends ─────────────────────────────────────────────────────────────────

test("friends: the owner manages their own list, nobody else can read or write it", async () => {
  await assertSucceeds(getDoc(doc(as("alice"), "users/alice/friends/bob")));
  await assertSucceeds(getDocs(collection(as("alice"), "users/alice/friends")));
  await assertSucceeds(setDoc(doc(as("alice"), "users/alice/friends/carol"), { uid: "carol" }));
  await assertSucceeds(deleteDoc(doc(as("alice"), "users/alice/friends/carol")));
  await assertFails(getDoc(doc(as("bob"), "users/alice/friends/bob")));
  await assertFails(getDocs(collection(as("bob"), "users/alice/friends")));
  await assertFails(setDoc(doc(as("bob"), "users/alice/friends/bob2"), { uid: "bob2" }));
  await assertFails(deleteDoc(doc(as("bob"), "users/alice/friends/bob")));
  await assertFails(getDoc(doc(anon(), "users/alice/friends/bob")));
});

// ── Likes and comments ──────────────────────────────────────────────────────

test("likes: only `liked: true` with the allowed fields, keyed by the caller's own uid", async () => {
  const bob = as("bob");
  await assertSucceeds(setDoc(doc(bob, "danceLibrary/d1/likes/bob"), { liked: true }));
  await assertSucceeds(setDoc(doc(bob, "danceLibrary/d1/likes/bob"), { liked: true, uid: "bob", createdAt: now() }));
  await assertFails(setDoc(doc(bob, "danceLibrary/d1/likes/bob"), { liked: false }));
  await assertFails(setDoc(doc(bob, "danceLibrary/d1/likes/bob"), { liked: true, extra: 1 }));
  await assertFails(setDoc(doc(bob, "danceLibrary/d1/likes/bob"), { liked: true, createdAt: "yesterday" }));
  await assertFails(setDoc(doc(bob, "danceLibrary/d1/likes/alice"), { liked: true }));
  await assertFails(setDoc(doc(anon(), "danceLibrary/d1/likes/bob"), { liked: true }));
});

test("likes: removable by the liker or the dance's owner, not by a bystander", async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), "danceLibrary/d1/likes/bob"), { liked: true });
  });
  await assertFails(deleteDoc(doc(as("carol"), "danceLibrary/d1/likes/bob")));
  await assertSucceeds(deleteDoc(doc(as("alice"), "danceLibrary/d1/likes/bob")));
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), "danceLibrary/d1/likes/bob"), { liked: true });
  });
  await assertSucceeds(deleteDoc(doc(as("bob"), "danceLibrary/d1/likes/bob")));
});

test("comments: can't be edited; deletable by the author or the dance owner only", async () => {
  const comment = { commentId: "c1", uid: "bob", username: "Bob", text: "nice", createdAt: now() };
  await assertSucceeds(setDoc(doc(as("bob"), "danceLibrary/d1/comments/c1"), comment));
  await assertFails(updateDoc(doc(as("bob"), "danceLibrary/d1/comments/c1"), { text: "edited" }));
  await assertFails(deleteDoc(doc(as("carol"), "danceLibrary/d1/comments/c1")));
  await assertSucceeds(deleteDoc(doc(as("alice"), "danceLibrary/d1/comments/c1")));
  await assertSucceeds(setDoc(doc(as("bob"), "danceLibrary/d1/comments/c1"), comment));
  await assertSucceeds(deleteDoc(doc(as("bob"), "danceLibrary/d1/comments/c1")));
  // Impersonation, empty and oversized text are refused.
  await assertFails(setDoc(doc(as("bob"), "danceLibrary/d1/comments/c2"), { ...comment, commentId: "c2", uid: "alice" }));
  await assertFails(setDoc(doc(as("bob"), "danceLibrary/d1/comments/c3"), { ...comment, commentId: "c3", text: "" }));
  await assertFails(setDoc(doc(as("bob"), "danceLibrary/d1/comments/c4"), { ...comment, commentId: "c4", text: "x".repeat(1001) }));
});

// ── Retired forum ───────────────────────────────────────────────────────────

test("retired forum: readable when signed in, only authors can delete their posts and replies", async () => {
  await assertSucceeds(getDoc(doc(as("bob"), "communityPosts/p1")));
  await assertSucceeds(getDoc(doc(as("bob"), "communityPosts/p1/replies/r1")));
  await assertFails(getDoc(doc(anon(), "communityPosts/p1")));
  await assertFails(updateDoc(doc(as("alice"), "communityPosts/p1"), { body: "edit" }));
  await assertFails(deleteDoc(doc(as("bob"), "communityPosts/p1")));
  await assertFails(deleteDoc(doc(as("alice"), "communityPosts/p1/replies/r1")));
  await assertFails(setDoc(doc(as("bob"), "communityPosts/p1/replies/r2"), { uid: "bob", body: "new" }));
  await assertSucceeds(deleteDoc(doc(as("bob"), "communityPosts/p1/replies/r1")));
  await assertSucceeds(deleteDoc(doc(as("alice"), "communityPosts/p1")));
});

// ── Default deny ────────────────────────────────────────────────────────────

test("collections without a rule are closed to everyone", async () => {
  await assertFails(setDoc(doc(as("alice"), "secrets/s1"), { a: 1 }));
  await assertFails(getDoc(doc(as("alice"), "secrets/s1")));
  await assertFails(getDocs(collection(as("alice"), "secrets")));
  await assertFails(setDoc(doc(as("alice"), "users/alice/unknownSub/x"), { a: 1 }));
  await assertFails(getDoc(doc(as("alice"), "users/alice/unknownSub/x")));
});
