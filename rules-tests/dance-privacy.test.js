// Firestore rules tests for dance privacy and server-managed fields.
// Run with `npm test` in rules-tests/ (starts the Firestore emulator).
const test = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  collectionGroup,
  query,
  where,
  getDocs,
} = require("firebase/firestore");

let env;

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-chorevo",
    firestore: { rules: fs.readFileSync(path.join(__dirname, "..", "firestore.rules"), "utf8") },
  });
});

test.after(async () => {
  await env?.cleanup();
});

test.beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "danceLibrary/pub"), {
      danceId: "pub", educatorUid: "owner", visibility: "public", enabled: true, feedReady: true,
    });
    await setDoc(doc(db, "danceLibrary/legacy"), { danceId: "legacy", educatorUid: "owner", enabled: true });
    await setDoc(doc(db, "danceLibrary/priv"), {
      danceId: "priv", educatorUid: "owner", visibility: "private", enabled: true, feedReady: false,
    });
    await setDoc(doc(db, "danceLibrary/priv/likes/owner"), { liked: true, uid: "owner" });
    await setDoc(doc(db, "danceLibrary/pub/likes/viewer"), { liked: true, uid: "viewer" });
    await setDoc(doc(db, "danceLibrary/priv/comments/c1"), { commentId: "c1", uid: "owner", text: "hi" });
  });
});

const as = (uid) => env.authenticatedContext(uid).firestore();

test("public dances are readable by anyone signed in", async () => {
  await assertSucceeds(getDoc(doc(as("viewer"), "danceLibrary/pub")));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "danceLibrary/pub")));
});

test("a dance without a visibility field is owner-only until the backfill sets it", async () => {
  await assertFails(getDoc(doc(as("viewer"), "danceLibrary/legacy")));
  await assertSucceeds(getDoc(doc(as("owner"), "danceLibrary/legacy")));
});

test("a private dance is readable only by its uploader", async () => {
  await assertFails(getDoc(doc(as("viewer"), "danceLibrary/priv")));
  await assertSucceeds(getDoc(doc(as("owner"), "danceLibrary/priv")));
});

test("feed queries must filter to public dances", async () => {
  const lib = collection(as("viewer"), "danceLibrary");
  await assertFails(getDocs(query(lib, where("enabled", "==", true))));
  await assertFails(getDocs(lib));
  await assertSucceeds(getDocs(query(lib, where("visibility", "==", "public"), where("feedReady", "==", true))));
  await assertSucceeds(getDocs(query(collection(as("owner"), "danceLibrary"), where("educatorUid", "==", "owner"))));
});

test("likes and comments of a private dance are hidden from others", async () => {
  await assertFails(getDocs(collection(as("viewer"), "danceLibrary/priv/likes")));
  await assertFails(getDocs(collection(as("viewer"), "danceLibrary/priv/comments")));
  await assertSucceeds(getDocs(collection(as("owner"), "danceLibrary/priv/likes")));
  await assertSucceeds(getDocs(collection(as("viewer"), "danceLibrary/pub/likes")));
  await assertFails(setDoc(doc(as("viewer"), "danceLibrary/priv/likes/viewer"), { liked: true, uid: "viewer" }));
});

test("likes may carry the liker's uid and time, but not someone else's uid", async () => {
  const db = as("viewer");
  await assertSucceeds(setDoc(doc(db, "danceLibrary/pub/likes/viewer"), { liked: true, uid: "viewer", createdAt: 1 }));
  await assertSucceeds(setDoc(doc(db, "danceLibrary/pub/likes/viewer"), { liked: true }));
  await assertFails(setDoc(doc(db, "danceLibrary/pub/likes/viewer"), { liked: true, uid: "someone" }));
  await assertFails(setDoc(doc(db, "danceLibrary/pub/likes/viewer"), { liked: true, extra: 1 }));
});

test("the liked-videos query only returns the caller's own likes", async () => {
  const likes = collectionGroup(as("viewer"), "likes");
  await assertSucceeds(getDocs(query(likes, where("uid", "==", "viewer"))));
  await assertFails(getDocs(query(likes, where("uid", "==", "owner"))));
  await assertFails(getDocs(likes));
});

test("clients cannot set or change server-managed fields", async () => {
  const db = as("owner");
  await assertSucceeds(setDoc(doc(db, "danceLibrary/new1"), { danceId: "new1", educatorUid: "owner", title: "x", createdAt: Date.now() }));
  await assertFails(setDoc(doc(db, "danceLibrary/new2"), { danceId: "new2", educatorUid: "owner", likeCount: 99, createdAt: Date.now() }));
  await assertSucceeds(updateDoc(doc(db, "danceLibrary/pub"), { title: "Renamed", visibility: "private" }));
  await assertFails(updateDoc(doc(db, "danceLibrary/pub"), { likeCount: 1000 }));
  await assertFails(updateDoc(doc(db, "danceLibrary/pub"), { feedReady: false }));
});

test("meta documents are read-only for clients", async () => {
  await assertSucceeds(getDoc(doc(as("viewer"), "meta/danceCategories")));
  await assertFails(setDoc(doc(as("viewer"), "meta/danceCategories"), { counts: {} }));
});

test("the uploader can still delete their dance and its likes", async () => {
  const db = as("owner");
  await assertSucceeds(deleteDoc(doc(db, "danceLibrary/pub/likes/viewer")));
  await assertSucceeds(deleteDoc(doc(db, "danceLibrary/priv")));
});
