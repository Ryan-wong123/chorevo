// Rules tests for the security hardening: profile privacy, server-only
// leaderboard, validated dances/comments/sessions/streaks, group post media,
// bans, the retired forum, and Storage folder privacy.
const test = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const {
  doc, getDoc, setDoc, updateDoc, deleteDoc, deleteField, collection, getDocs, writeBatch,
} = require("firebase/firestore");
const { ref, uploadBytes, getBytes, listAll, deleteObject } = require("firebase/storage");

let env;
const root = path.join(__dirname, "..");

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-chorevo-security",
    firestore: { rules: fs.readFileSync(path.join(root, "firestore.rules"), "utf8") },
    storage: { rules: fs.readFileSync(path.join(root, "storage.rules"), "utf8") },
  });
});

test.after(async () => {
  await env?.cleanup();
});

const as = (uid) => env.authenticatedContext(uid).firestore();
const storageAs = (uid) => env.authenticatedContext(uid).storage();
const now = () => Date.now();
const BUCKET_URL = "https://firebasestorage.googleapis.com/v0/b/demo-bucket/o";
const media = (folder, uid, file) => `${BUCKET_URL}/${folder}%2F${uid}%2F${file}?alt=media&token=t`;

test.beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "users/alice"), { uid: "alice", displayName: "Alice", email: "alice@example.com", role: "USER" });
    await setDoc(doc(db, "users/bob"), { uid: "bob", displayName: "Bob" });
    await setDoc(doc(db, "danceLibrary/d1"), {
      danceId: "d1", educatorUid: "alice", visibility: "public", title: "Salsa", createdAt: now(),
      videoUrl: media("danceVideos", "alice", "d1.mp4"), enabled: true,
    });
    await setDoc(doc(db, "groups/g"), {
      id: "g", name: "Crew", nameLower: "crew", description: "", visibility: "public",
      ownerUid: "alice", ownerName: "Alice", createdAt: now(),
    });
    await setDoc(doc(db, "groups/g/members/alice"), { uid: "alice", displayName: "Alice", photoUrl: null, role: "owner", joinedAt: now() });
    await setDoc(doc(db, "groups/g/members/bob"), { uid: "bob", displayName: "Bob", photoUrl: null, role: "member", joinedAt: now() });
  });
});

// ── Profiles ────────────────────────────────────────────────────────────────

test("profiles can be read one at a time but never listed", async () => {
  await assertSucceeds(getDoc(doc(as("bob"), "users/alice")));
  await assertFails(getDocs(collection(as("bob"), "users")));
});

test("profiles no longer take an email; existing emails can only be removed", async () => {
  await assertFails(setDoc(doc(as("carol"), "users/carol"), { uid: "carol", email: "c@example.com", lastSeen: now() }));
  await assertSucceeds(setDoc(doc(as("carol"), "users/carol"), { uid: "carol", displayName: "Carol", lastSeen: now() }));
  await assertFails(updateDoc(doc(as("alice"), "users/alice"), { email: "new@example.com" }));
  await assertSucceeds(updateDoc(doc(as("alice"), "users/alice"), { email: deleteField(), lastSeen: now() }));
});

test("profiles can't grant roles or carry huge names", async () => {
  await assertFails(updateDoc(doc(as("bob"), "users/bob"), { role: "ADMIN" }));
  await assertFails(updateDoc(doc(as("bob"), "users/bob"), { displayName: "x".repeat(500) }));
  await assertFails(updateDoc(doc(as("bob"), "users/bob"), { photoUrl: "javascript:alert(1)" }));
  await assertSucceeds(updateDoc(doc(as("bob"), "users/bob"), { displayName: "Bobby", photoUrl: "https://lh3.googleusercontent.com/x" }));
});

test("changing username must release the old one", async () => {
  const bob = as("bob");
  const claim = (handle) => {
    const b = writeBatch(bob);
    b.set(doc(bob, `usernames/${handle}`), { uid: "bob", createdAt: now() });
    b.set(doc(bob, "users/bob"), { username: handle }, { merge: true });
    return b;
  };
  await assertSucceeds(claim("bob_one").commit());
  await assertFails(claim("bob_two").commit()); // keeps bob_one too
  const ok = claim("bob_three");
  ok.delete(doc(bob, "usernames/bob_one"));
  await assertSucceeds(ok.commit());
});

// ── Leaderboard ─────────────────────────────────────────────────────────────

test("nobody can write leaderboard stats from the app", async () => {
  await assertFails(setDoc(doc(as("bob"), "leaderboardStats/bob"), { uid: "bob", totalPracticeSessions: 1e9 }));
  await assertSucceeds(getDoc(doc(as("bob"), "leaderboardStats/alice")));
});

test("points, badges, streaks, challenges and session rewards are read-only for the app", async () => {
  const bob = as("bob");
  for (const path of ["points/current", "achievements/current", "streaks/current",
    "weeklyChallenges/2026-10-12", "sessionRewards/s1"]) {
    await assertFails(setDoc(doc(bob, `users/bob/${path}`), { totalPoints: 999999 }), path);
    await assertSucceeds(getDoc(doc(bob, `users/bob/${path}`)));
    await assertFails(getDoc(doc(as("mallory"), `users/bob/${path}`)));
  }
});

test("practice sessions need a real 0..1 score; analysis is optional but must be genuine", async () => {
  const base = { sessionId: "s1", tsStart: 1, tsEnd: 2, activityName: "Salsa", activityType: "dance", durationMs: 1,
    smoothnessScore: 0.5, balanceScore: 0.5, timingConsistencyScore: 0.5, alignmentScore: 0.7, qualityFlag: "ok" };
  const bob = as("bob");
  await assertSucceeds(setDoc(doc(bob, "users/bob/danceLearningSessions/s1"), base));
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s2"), { ...base, sessionId: "s2", alignmentScore: 50 }));
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s3"), { ...base, sessionId: "s3", hacked: true }));
  await assertSucceeds(setDoc(doc(bob, "users/bob/danceLearningSessions/s4"),
    { ...base, sessionId: "s4", analysis: { danceId: "d1", educatorUid: "alice" } }));
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s5"),
    { ...base, sessionId: "s5", analysis: { danceId: "d1", educatorUid: "bob" } }));
  // Every score is 0..1, the time-zone offset is a real one, and sessions are final.
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s6"), { ...base, sessionId: "s6", timingConsistencyScore: 9 }));
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s7"), { ...base, sessionId: "s7", smoothnessScore: -1 }));
  await assertSucceeds(setDoc(doc(bob, "users/bob/danceLearningSessions/s8"), { ...base, sessionId: "s8", tzOffsetMinutes: 480 }));
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s9"), { ...base, sessionId: "s9", tzOffsetMinutes: 5000 }));
  await assertFails(setDoc(doc(bob, "users/bob/danceLearningSessions/s1"), { ...base, alignmentScore: 1 }));
});

// ── Dances and comments ─────────────────────────────────────────────────────

test("new dances must use the uploader's own media and a current upload time", async () => {
  const alice = as("alice");
  const dance = (id, extra = {}) => ({
    danceId: id, educatorUid: "alice", title: "Bachata", visibility: "public", createdAt: now(),
    videoUrl: media("danceVideos", "alice", `${id}.mp4`), thumbnailUrl: "", enabled: true, ...extra,
  });
  await assertSucceeds(setDoc(doc(alice, "danceLibrary/n1"), dance("n1")));
  await assertFails(setDoc(doc(alice, "danceLibrary/n2"), dance("n2", { videoUrl: "https://evil.example/v.mp4" })));
  await assertFails(setDoc(doc(alice, "danceLibrary/n3"), dance("n3", { videoUrl: media("danceVideos", "bob", "x.mp4") })));
  await assertFails(setDoc(doc(alice, "danceLibrary/n4"), dance("n4", { createdAt: 4102444800000 })));
  await assertFails(setDoc(doc(alice, "danceLibrary/n5"), dance("n5", { title: "x".repeat(500) })));
  await assertFails(setDoc(doc(alice, "danceLibrary/n6"), dance("n6", { visibility: "unlisted" })));
  await assertFails(setDoc(doc(alice, "danceLibrary/n7"), dance("n7", { templateStoragePath: "danceTemplates/bob/x.json" })));
});

test("upload time, media and moderation flag can't be changed later", async () => {
  const alice = as("alice");
  await assertSucceeds(updateDoc(doc(alice, "danceLibrary/d1"), { title: "Salsa 2", visibility: "private" }));
  await assertFails(updateDoc(doc(alice, "danceLibrary/d1"), { createdAt: 4102444800000 }));
  await assertFails(updateDoc(doc(alice, "danceLibrary/d1"), { videoUrl: media("danceVideos", "alice", "other.mp4") }));
  await assertFails(updateDoc(doc(alice, "danceLibrary/d1"), { enabled: false }));
  await assertSucceeds(updateDoc(doc(alice, "danceLibrary/d1"), { templateGenerationStatus: "processing" }));
});

test("comments carry a bounded name and the current time", async () => {
  const c = (id, extra = {}) => ({ commentId: id, uid: "bob", username: "Bob", text: "nice", createdAt: now(), ...extra });
  const bob = as("bob");
  await assertSucceeds(setDoc(doc(bob, "danceLibrary/d1/comments/c1"), c("c1")));
  await assertFails(setDoc(doc(bob, "danceLibrary/d1/comments/c2"), c("c2", { createdAt: 4102444800000 })));
  await assertFails(setDoc(doc(bob, "danceLibrary/d1/comments/c3"), c("c3", { username: "x".repeat(500) })));
});

// ── Groups ──────────────────────────────────────────────────────────────────

test("group posts can't reference someone else's files or outside URLs", async () => {
  const bob = as("bob");
  const post = (id, extra) => ({ id, uid: "bob", displayName: "Bob", photoUrl: null, body: "", createdAt: now(), ...extra });
  // Current app: paths only, no permanent link.
  await assertSucceeds(setDoc(doc(bob, "groups/g/posts/v1"), post("v1", {
    kind: "video", storagePath: "groupVideos/bob/v1.mp4", thumbnailPath: "groupThumbnails/bob/v1.jpg",
  })));
  await assertFails(setDoc(doc(bob, "groups/g/posts/v2"), post("v2", { kind: "video", storagePath: "groupVideos/alice/v2.mp4" })));
  await assertFails(setDoc(doc(bob, "groups/g/posts/v3"), post("v3", { kind: "video" })));
  await assertSucceeds(setDoc(doc(bob, "groups/g/posts/p1"), post("p1", {
    kind: "video", videoUrl: media("groupVideos", "bob", "p1.mp4"), thumbnailUrl: null,
    storagePath: "groupVideos/bob/p1.mp4", thumbnailPath: null,
  })));
  await assertFails(setDoc(doc(bob, "groups/g/posts/p2"), post("p2", {
    kind: "video", videoUrl: media("groupVideos", "bob", "p2.mp4"), storagePath: "groupVideos/alice/p9.mp4",
  })));
  await assertFails(setDoc(doc(bob, "groups/g/posts/p3"), post("p3", {
    kind: "video", videoUrl: "https://evil.example/v.mp4", storagePath: "groupVideos/bob/p3.mp4",
  })));
  await assertFails(setDoc(doc(bob, "groups/g/posts/p4"), post("p4", {
    kind: "text", body: "hi", storagePath: "groupVideos/alice/p9.mp4",
  })));
  // Sharing a dance needs the poster's own dance.
  await assertFails(setDoc(doc(bob, "groups/g/posts/p5"), post("p5", { kind: "dance", danceId: "d1" })));
});

test("removed members are banned and can't rejoin", async () => {
  const alice = as("alice");
  const b = writeBatch(alice);
  b.delete(doc(alice, "groups/g/members/bob"));
  b.set(doc(alice, "groups/g/bans/bob"), { uid: "bob", bannedAt: now() });
  await assertSucceeds(b.commit());
  await assertFails(setDoc(doc(as("bob"), "groups/g/members/bob"),
    { uid: "bob", displayName: "Bob", photoUrl: null, role: "member", joinedAt: now() }));
  await assertFails(setDoc(doc(as("bob"), "groups/g/bans/bob"), { uid: "bob", bannedAt: now() }));
});

test("only the owner can see and lift bans, after which the person can rejoin", async () => {
  const alice = as("alice");
  const b = writeBatch(alice);
  b.delete(doc(alice, "groups/g/members/bob"));
  b.set(doc(alice, "groups/g/bans/bob"), { uid: "bob", bannedAt: now() });
  await assertSucceeds(b.commit());
  const member = { uid: "bob", displayName: "Bob", photoUrl: null, role: "member", joinedAt: now() };

  await assertSucceeds(getDocs(collection(alice, "groups/g/bans")));
  await assertFails(getDocs(collection(as("carol"), "groups/g/bans")));
  await assertFails(deleteDoc(doc(as("bob"), "groups/g/bans/bob")));
  await assertFails(deleteDoc(doc(as("carol"), "groups/g/bans/bob")));

  await assertSucceeds(deleteDoc(doc(alice, "groups/g/bans/bob")));
  await assertSucceeds(setDoc(doc(as("bob"), "groups/g/members/bob"), member));
});

test("private groups must be created with their own invite code document", async () => {
  const bob = as("bob");
  const group = { id: "pg", name: "Secret", nameLower: "secret", description: "", visibility: "private",
    ownerUid: "bob", ownerName: "Bob", inviteCode: "QWER5678", createdAt: now() };
  const member = { uid: "bob", displayName: "Bob", photoUrl: null, role: "owner", joinedAt: now() };
  const noInvite = writeBatch(bob);
  noInvite.set(doc(bob, "groups/pg"), group);
  noInvite.set(doc(bob, "groups/pg/members/bob"), member);
  await assertFails(noInvite.commit());
  const ok = writeBatch(bob);
  ok.set(doc(bob, "groups/pg"), group);
  ok.set(doc(bob, "groups/pg/members/bob"), member);
  ok.set(doc(bob, "groupInvites/QWER5678"), { groupId: "pg", createdAt: now() });
  await assertSucceeds(ok.commit());
});

test("the retired forum takes no new posts", async () => {
  await assertFails(setDoc(doc(as("bob"), "communityPosts/x"), {
    id: "x", uid: "bob", displayName: "Bob", photoUrl: null, title: "t", body: "b", createdAt: now(),
  }));
});

// ── Storage ─────────────────────────────────────────────────────────────────

const video = new Uint8Array([0, 0, 0, 1]);

test("nobody can list someone's videos; only the owner reads by path", async () => {
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "danceVideos/alice/d1.mp4"), video, { contentType: "video/mp4" }));
  await assertFails(listAll(ref(storageAs("bob"), "danceVideos/alice")));
  await assertFails(listAll(ref(env.unauthenticatedContext().storage(), "danceVideos/alice")));
  await assertFails(getBytes(ref(storageAs("bob"), "danceVideos/alice/d1.mp4")));
  await assertSucceeds(getBytes(ref(storageAs("alice"), "danceVideos/alice/d1.mp4")));
});

test("owners can delete their own files, nobody else's", async () => {
  await uploadBytes(ref(storageAs("alice"), "danceThumbnails/alice/d1.jpg"), video, { contentType: "image/jpeg" });
  await assertFails(deleteObject(ref(storageAs("bob"), "danceThumbnails/alice/d1.jpg")));
  await assertSucceeds(deleteObject(ref(storageAs("alice"), "danceThumbnails/alice/d1.jpg")));
});

test("uploads must be the right type, in the uploader's folder, with expected names", async () => {
  await assertFails(uploadBytes(ref(storageAs("bob"), "danceVideos/alice/x.mp4"), video, { contentType: "video/mp4" }));
  await assertFails(uploadBytes(ref(storageAs("bob"), "danceVideos/bob/x.mp4"), video, { contentType: "text/html" }));
  await assertFails(uploadBytes(ref(storageAs("bob"), "danceVideos/bob/page.html"), video, { contentType: "video/mp4" }));
  await assertSucceeds(uploadBytes(ref(storageAs("bob"), "groupVideos/bob/p1.mp4"), video, { contentType: "video/mp4" }));
});
