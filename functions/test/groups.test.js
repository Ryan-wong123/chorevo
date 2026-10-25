const test = require("node:test");
const assert = require("node:assert");
const { storagePathsOfPosts } = require("../groups");

test("collects group video and thumbnail paths, ignoring everything else", () => {
  const paths = storagePathsOfPosts([
    { kind: "video", storagePath: "groupVideos/u1/p1.mp4", thumbnailPath: "groupThumbnails/u1/p1.jpg" },
    { kind: "dance", danceId: "d1" },
    { kind: "video", storagePath: "danceVideos/u1/d1.mp4" }, // never delete feed videos
    { kind: "text", storagePath: null },
    { kind: "video", storagePath: "groupVideos/u1/p1.mp4" },  // duplicate
  ]);
  assert.deepStrictEqual(paths, []); // posts without an author uid are ignored
  const owned = storagePathsOfPosts([
    { uid: "u1", kind: "video", storagePath: "groupVideos/u1/p1.mp4", thumbnailPath: "groupThumbnails/u1/p1.jpg" },
    { uid: "u1", kind: "dance", danceId: "d1" },
    { uid: "u1", kind: "video", storagePath: "danceVideos/u1/d1.mp4" }, // never delete feed videos
    { uid: "u1", kind: "text", storagePath: null },
    { uid: "u1", kind: "video", storagePath: "groupVideos/u1/p1.mp4" }, // duplicate
  ]);
  assert.deepStrictEqual(owned.sort(), ["groupThumbnails/u1/p1.jpg", "groupVideos/u1/p1.mp4"]);
});

test("a post can't point the cleanup at someone else's files", () => {
  const paths = storagePathsOfPosts([
    { uid: "attacker", kind: "video", storagePath: "groupVideos/victim/p1.mp4", thumbnailPath: "groupThumbnails/victim/p1.jpg" },
    { uid: "attacker", kind: "video", storagePath: "groupVideos/attacker/../victim/p1.mp4" },
  ]);
  assert.deepStrictEqual(paths, []);
});

test("isInviteCode accepts only well-formed codes", () => {
  const { isInviteCode } = require("../groups");
  assert.ok(isInviteCode("ABCD2345"));
  assert.ok(!isInviteCode("abcd2345"));
  assert.ok(!isInviteCode("ABC/2345"));
  assert.ok(!isInviteCode("ABCD0O1I"));
  assert.ok(!isInviteCode(null));
});

test("invitePushAllowance limits pushes per inviter per hour", () => {
  const { invitePushAllowance, INVITE_PUSHES_PER_HOUR } = require("../groups");
  const t = 10 * 3_600_000;
  let counter = null;
  for (let i = 0; i < INVITE_PUSHES_PER_HOUR; i++) {
    const r = invitePushAllowance(counter, t + i);
    assert.ok(r.allowed);
    counter = r.counter;
  }
  assert.ok(!invitePushAllowance(counter, t + 1000).allowed);
  assert.ok(invitePushAllowance(counter, t + 3_600_000).allowed); // next hour
});

const { invitationMessage, deadTokens } = require("../groups");

test("invitationMessage names the inviter and group and routes to the invites channel", () => {
  const msg = invitationMessage({ groupId: "g1", groupName: "Salsa Crew", inviterName: "Ana" });
  assert.equal(msg.notification.body, "Ana invited you to join Salsa Crew");
  assert.deepEqual(msg.data, { type: "group_invitation", groupId: "g1" });
  assert.equal(msg.android.notification.channelId, "group_invites");
  // Data values must be strings for FCM.
  for (const v of Object.values(msg.data)) assert.equal(typeof v, "string");
});

test("invitationMessage falls back when names are missing and trims long ones", () => {
  const msg = invitationMessage({ groupId: "g1" });
  assert.equal(msg.notification.body, "Someone invited you to join a group");
  const long = invitationMessage({ groupId: "g1", inviterName: "x".repeat(200), groupName: "y" });
  assert.ok(long.notification.body.length < 100);
});

test("deadTokens keeps tokens that failed for transient reasons", () => {
  const tokens = ["a", "b", "c", "d"];
  const responses = [
    { success: true },
    { success: false, error: { code: "messaging/registration-token-not-registered" } },
    { success: false, error: { code: "messaging/internal-error" } },
    { success: false, error: { code: "messaging/invalid-registration-token" } },
  ];
  assert.deepEqual(deadTokens(tokens, responses), ["b", "d"]);
  // A payload error is not the token's fault.
  assert.deepEqual(deadTokens(["e"], [{ success: false, error: { code: "messaging/invalid-argument" } }]), []);
});

// ── Group video links ────────────────────────────────────────────────────────

const { parseGroupMediaRequest, canSeeGroup, groupPostMediaPaths, isGroupMediaPath } = require("../groups");

test("media requests need a group id and 1-50 post ids", () => {
  assert.deepStrictEqual(parseGroupMediaRequest({ groupId: "g1", postIds: ["p1", "p2", "p1"] }), { groupId: "g1", postIds: ["p1", "p2"] });
  assert.strictEqual(parseGroupMediaRequest(null), null);
  assert.strictEqual(parseGroupMediaRequest({ groupId: "g1", postIds: [] }), null);
  assert.strictEqual(parseGroupMediaRequest({ groupId: "g/1", postIds: ["p1"] }), null);
  assert.strictEqual(parseGroupMediaRequest({ groupId: "g1", postIds: ["../x"] }), null);
  assert.strictEqual(parseGroupMediaRequest({ groupId: "g1", postIds: Array.from({ length: 51 }, (_, i) => `p${i}`) }), null);
});

test("public groups are visible to everyone signed in, private ones to members", () => {
  assert.strictEqual(canSeeGroup({ visibility: "public" }, false), true);
  assert.strictEqual(canSeeGroup({ visibility: "private" }, false), false);
  assert.strictEqual(canSeeGroup({ visibility: "private" }, true), true);
  assert.strictEqual(canSeeGroup(null, true), false);
});

test("only a video post's own files are signed", () => {
  assert.deepStrictEqual(groupPostMediaPaths("p1", {
    kind: "video", uid: "alice", storagePath: "groupVideos/alice/p1.mp4", thumbnailPath: "groupThumbnails/alice/p1.jpg",
  }), { video: "groupVideos/alice/p1.mp4", thumbnail: "groupThumbnails/alice/p1.jpg" });
  assert.deepStrictEqual(groupPostMediaPaths("p1", { kind: "video", uid: "alice", storagePath: "danceVideos/alice/d1.mp4" }), {});
  assert.deepStrictEqual(groupPostMediaPaths("p1", { kind: "video", uid: "alice", storagePath: "groupVideos/bob/p1.mp4" }), {});
  assert.deepStrictEqual(groupPostMediaPaths("p1", { kind: "video", uid: "alice", storagePath: "groupVideos/alice/p2.mp4" }), {});
  assert.deepStrictEqual(groupPostMediaPaths("p1", { kind: "text", uid: "alice", storagePath: "groupVideos/alice/p1.mp4" }), {});
});

test("group media paths are recognised", () => {
  assert.strictEqual(isGroupMediaPath("groupVideos/alice/p1.mp4"), true);
  assert.strictEqual(isGroupMediaPath("groupThumbnails/alice/p1.jpg"), true);
  assert.strictEqual(isGroupMediaPath("danceVideos/alice/d1.mp4"), false);
  assert.strictEqual(isGroupMediaPath("groupVideos/alice/x/p1.mp4"), false);
});

const { removeDownloadTokens, lockOldGroupMedia } = require("../groups");

function fakeFile(meta, { missing = false } = {}) {
  const calls = [];
  return {
    calls,
    getMetadata: async () => {
      if (missing) { const e = new Error("nope"); e.code = 404; throw e; }
      return [meta];
    },
    setMetadata: async (m) => { calls.push(m); },
  };
}

test("removing download tokens only touches files that have one", async () => {
  const withToken = fakeFile({ metadata: { firebaseStorageDownloadTokens: "t" } });
  assert.strictEqual(await removeDownloadTokens(withToken), true);
  assert.deepStrictEqual(withToken.calls, [{ metadata: { firebaseStorageDownloadTokens: null } }]);

  const without = fakeFile({ metadata: {} });
  assert.strictEqual(await removeDownloadTokens(without), false);
  assert.deepStrictEqual(without.calls, []);

  assert.strictEqual(await removeDownloadTokens(fakeFile(null, { missing: true })), false);
});

test("the catch-up locks old video posts' files and drops their stored links", async () => {
  const files = {
    "groupVideos/alice/p1.mp4": fakeFile({ metadata: { firebaseStorageDownloadTokens: "t" } }),
    "groupThumbnails/alice/p1.jpg": fakeFile({ metadata: {} }),
  };
  const updates = {};
  const posts = [
    { id: "p1", data: { kind: "video", uid: "alice", storagePath: "groupVideos/alice/p1.mp4", thumbnailPath: "groupThumbnails/alice/p1.jpg", videoUrl: "https://x", thumbnailUrl: "https://y" } },
    { id: "p2", data: { kind: "text", uid: "bob", body: "hi" } },
  ];
  const db = {
    collectionGroup: (name) => {
      assert.strictEqual(name, "posts");
      return { get: async () => ({ size: posts.length, docs: posts.map((p) => ({ id: p.id, data: () => p.data, ref: { update: async (u) => { updates[p.id] = u; } } })) }) };
    },
  };
  const bucket = { file: (path) => files[path] };
  const result = await lockOldGroupMedia(db, bucket, { deleteField: () => "DELETE", logger: { warn: () => {} } });
  assert.deepStrictEqual(result, { posts: 2, filesLocked: 1 });
  assert.deepStrictEqual(updates, { p1: { videoUrl: "DELETE", thumbnailUrl: "DELETE" } });
});
