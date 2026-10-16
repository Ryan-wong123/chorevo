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
