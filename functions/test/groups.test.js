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
  assert.deepStrictEqual(paths.sort(), ["groupThumbnails/u1/p1.jpg", "groupVideos/u1/p1.mp4"]);
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
});
