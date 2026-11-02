// deleteUserData: Auth onDelete cleanup.
const test = require("node:test");
const assert = require("node:assert/strict");
const h = require("./support/harness");
const { userStoragePrefixes } = require("../account");

const idx = h.loadIndex();
const UID = "u1";

const seed = () => ({
  // profile and everything under it
  "users/u1": { displayName: "Ann" },
  "users/u1/points/current": { totalPoints: 5 },
  "users/u1/fcmTokens/tok": { at: 1 },
  "users/u10": { displayName: "Someone with a similar id" },
  "leaderboardStats/u1": { uid: "u1" },
  "leaderboardStats/u2": { uid: "u2" },
  "pushRate/u1": { hour: 1, sent: 2 },
  "pushRate/u2": { hour: 1, sent: 2 },
  "usernames/ann": { uid: "u1" },
  "usernames/bob": { uid: "u2" },
  // their dances, with likes and comments
  "danceLibrary/mine": { educatorUid: "u1", title: "Mine" },
  "danceLibrary/mine/likes/u2": { uid: "u2" },
  "danceLibrary/mine/comments/c1": { uid: "u2" },
  "danceLibrary/theirs": { educatorUid: "u2", title: "Theirs" },
  "danceLibrary/theirs/likes/u1": { uid: "u1" },
  "danceLibrary/theirs/likes/u2": { uid: "u2" },
  "danceLibrary/theirs/comments/c1": { uid: "u1" },
  "danceLibrary/theirs/comments/c2": { uid: "u2" },
  // groups: owns gOwn, member of gMem
  "groups/gOwn": { name: "Mine" },
  "groups/gOwn/members/u1": { uid: "u1", role: "owner" },
  "groups/gOwn/members/u2": { uid: "u2", role: "member" },
  "groups/gMem": { name: "Theirs" },
  "groups/gMem/members/u1": { uid: "u1", role: "member" },
  "groups/gMem/members/u2": { uid: "u2", role: "owner" },
  "groups/gMem/posts/p1": { uid: "u1" },
  "groups/gMem/posts/p1/replies/r1": { uid: "u2" },
  "groups/gMem/posts/p2": { uid: "u2" },
  "groups/gMem/posts/p2/replies/r2": { uid: "u1" },
  "groups/gMem/posts/p2/replies/r3": { uid: "u2" },
  // invitations
  "groupInvitations/in1": { inviteeUid: "u1", inviterUid: "u2" },
  "groupInvitations/out1": { inviteeUid: "u2", inviterUid: "u1" },
  "groupInvitations/other": { inviteeUid: "u2", inviterUid: "u3" },
});
const files = () => ({
  "danceVideos/u1/a.mp4": {}, "danceThumbnails/u1/a.jpg": {}, "danceTemplates/u1/a.json": {},
  "groupVideos/u1/a.mp4": {}, "groupThumbnails/u1/a.jpg": {}, "profilePhotos/u1/me.jpg": {},
  "danceVideos/u10/a.mp4": {}, "profilePhotos/u2/you.jpg": {},
});

test("deleteUserData removes the user's data everywhere and leaves other users' data alone", async () => {
  const c = h.reset({ docs: seed(), files: files() });
  await idx.deleteUserData({ uid: UID });

  assert.deepEqual(c.db.paths("users/u1"), ["users/u10"]); // profile + subcollections, not u10
  assert.equal(c.db.has("leaderboardStats/u1"), false);
  assert.equal(c.db.has("pushRate/u1"), false);
  assert.equal(c.db.has("usernames/ann"), false);
  assert.deepEqual(c.db.paths("danceLibrary/mine"), []);
  assert.deepEqual(c.db.paths("danceLibrary/theirs"), ["danceLibrary/theirs", "danceLibrary/theirs/comments/c2", "danceLibrary/theirs/likes/u2"]);
  assert.equal(c.db.has("groups/gOwn"), false); // owned group deleted (groupCleanup handles the rest)
  assert.equal(c.db.has("groups/gMem/members/u1"), false);
  assert.ok(c.db.has("groups/gMem/members/u2"));
  assert.ok(c.db.has("groups/gMem")); // not owned: group stays
  assert.deepEqual(c.db.paths("groups/gMem/posts/"), ["groups/gMem/posts/p2", "groups/gMem/posts/p2/replies/r3"]);
  assert.deepEqual(c.db.paths("groupInvitations/"), ["groupInvitations/other"]);

  for (const other of ["leaderboardStats/u2", "pushRate/u2", "usernames/bob", "groups/gOwn/members/u2"]) assert.ok(c.db.has(other), other);
});

test("deleteUserData deletes all of the user's Storage folders but not look-alike ids", async () => {
  const c = h.reset({ docs: seed(), files: files() });
  await idx.deleteUserData({ uid: UID });
  assert.deepEqual(c.bucket.deleteFilesCalls, userStoragePrefixes(UID));
  assert.deepEqual([...c.bucket.files.keys()].sort(), ["danceVideos/u10/a.mp4", "profilePhotos/u2/you.jpg"]);
});

test("deleteUserData deletes an owned group document but only the membership of groups owned by others", async () => {
  const c = h.reset({ docs: seed() });
  await idx.deleteUserData({ uid: UID });
  assert.ok(c.db.writesTo("groups/gOwn").some((w) => w.op === "delete"));
  assert.ok(c.db.writesTo("groups/gMem/members/u1").some((w) => w.op === "delete"));
  assert.equal(c.db.writesTo("groups/gMem").length, 0);
});

test("deleteUserData logs how much it removed", async () => {
  const c = h.reset({ docs: seed(), files: files() });
  await idx.deleteUserData({ uid: UID });
  const [msg, info] = c.logger.entries.info.at(-1);
  assert.equal(msg, "Deleted user data");
  assert.deepEqual(info, {
    uid: UID, dances: 1, likes: 1, comments: 1, posts: 1, replies: 1, invitationsReceived: 1, invitationsSent: 1, usernames: 1,
  });
});

test("deleteUserData works for a user with no data and can be safely repeated", async () => {
  const c = h.reset();
  await idx.deleteUserData({ uid: "nobody" });
  const c2 = h.reset({ docs: seed(), files: files() });
  await idx.deleteUserData({ uid: UID });
  const after = new Map(c2.db.docs);
  await idx.deleteUserData({ uid: UID });
  assert.deepEqual(c2.db.docs, after);
  assert.equal(c.logger.entries.warn.length, 0);
});

test("deleteUserData logs a Storage folder that can't be cleared and still clears the others", async () => {
  const c = h.reset({ docs: seed(), files: files() });
  c.bucket.failDeleteFilesPrefix.add("groupVideos/u1/");
  await idx.deleteUserData({ uid: UID });
  assert.equal(c.logger.entries.warn.length, 1);
  assert.equal(c.logger.entries.warn[0][1].prefix, "groupVideos/u1/");
  assert.equal(c.bucket.files.has("danceVideos/u1/a.mp4"), false);
  assert.equal(c.bucket.files.has("profilePhotos/u1/me.jpg"), false);
  assert.ok(c.bucket.files.has("groupVideos/u1/a.mp4")); // the failed folder remains
  assert.equal(c.logger.entries.info.at(-1)[0], "Deleted user data");
});

test("deleteUserData rejects (so the failure is visible) when a Firestore delete fails", async () => {
  const c = h.reset({ docs: seed(), files: files() });
  c.db.onWrite = (op, p) => { if (op === "recursiveDelete" && p === "users/u1") throw new Error("recursive delete failed"); };
  await assert.rejects(idx.deleteUserData({ uid: UID }), /recursive delete failed/);
  assert.deepEqual(c.bucket.deleteFilesCalls, []); // storage not reached
});
