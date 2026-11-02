// groupMediaUrls, lockGroupPostMedia, groupMemberCount, notifyGroupInvitation, groupCleanup.
const test = require("node:test");
const assert = require("node:assert/strict");
const h = require("./support/harness");
const { MEDIA_LINK_TTL_MS, INVITE_PUSHES_PER_HOUR } = require("../groups");

const idx = h.loadIndex();
const NOW = Date.UTC(2026, 9, 14, 12);
const rejectsWith = (p, code) => assert.rejects(p, (e) => e instanceof h.HttpsError && e.code === code);

// ── groupMediaUrls ───────────────────────────────────────────────────────────

const videoPost = (id, uid = "u1", extra = {}) => ({
  kind: "video", uid, storagePath: `groupVideos/${uid}/${id}.mp4`, thumbnailPath: `groupThumbnails/${uid}/${id}.jpg`, ...extra,
});
const mediaSeed = (extraDocs = {}) => ({
  "groups/pub": { visibility: "public", name: "Public" },
  "groups/priv": { visibility: "private", name: "Private" },
  "groups/priv/members/member1": { uid: "member1" },
  "groups/pub/posts/p1": videoPost("p1"),
  "groups/priv/posts/p1": videoPost("p1"),
  ...extraDocs,
});
const mediaReq = (uid, data) => ({ auth: uid ? { uid } : undefined, data });

test("groupMediaUrls rejects callers who are not signed in", async () => {
  h.reset({ docs: mediaSeed() });
  await rejectsWith(idx.groupMediaUrls(mediaReq(null, { groupId: "pub", postIds: ["p1"] })), "unauthenticated");
  await rejectsWith(idx.groupMediaUrls({ data: { groupId: "pub", postIds: ["p1"] }, auth: { } }), "unauthenticated");
});

test("groupMediaUrls rejects malformed requests with invalid-argument", async () => {
  h.reset({ docs: mediaSeed() });
  const bad = [
    undefined, null, {}, { groupId: "pub" }, { groupId: "pub", postIds: [] },
    { groupId: "pub", postIds: "p1" }, { groupId: "pub", postIds: [1] },
    { groupId: "pub", postIds: Array.from({ length: 51 }, (_, i) => `p${i}`) },
    { groupId: "a/b", postIds: ["p1"] }, { groupId: "pub", postIds: ["../x"] },
  ];
  for (const data of bad) await rejectsWith(idx.groupMediaUrls(mediaReq("member1", data)), "invalid-argument");
});

test("groupMediaUrls denies a missing group and a private group to non-members", async () => {
  const c = h.reset({ docs: mediaSeed() });
  await rejectsWith(idx.groupMediaUrls(mediaReq("member1", { groupId: "nope", postIds: ["p1"] })), "permission-denied");
  await rejectsWith(idx.groupMediaUrls(mediaReq("stranger", { groupId: "priv", postIds: ["p1"] })), "permission-denied");
  assert.deepEqual(c.bucket.signed, []);
});

test("groupMediaUrls signs hour-long links for video and cover, for members of private groups and anyone for public ones", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: mediaSeed() });
  const asMember = await idx.groupMediaUrls(mediaReq("member1", { groupId: "priv", postIds: ["p1"] }));
  const asStranger = await idx.groupMediaUrls(mediaReq("stranger", { groupId: "pub", postIds: ["p1"] }));
  for (const r of [asMember, asStranger]) {
    assert.deepEqual(Object.keys(r.urls), ["p1"]);
    assert.equal(r.urls.p1.expiresAt, NOW + MEDIA_LINK_TTL_MS);
    assert.match(r.urls.p1.videoUrl, /groupVideos\/u1\/p1\.mp4/);
    assert.match(r.urls.p1.thumbnailUrl, /groupThumbnails\/u1\/p1\.jpg/);
  }
  assert.ok(c.bucket.signed.every((s) => s.opts.version === "v4" && s.opts.action === "read" && s.opts.expires === NOW + MEDIA_LINK_TTL_MS));
});

test("groupMediaUrls skips missing posts, non-video posts and posts whose paths aren't the author's own, and handles a missing cover", async () => {
  const c = h.reset({ docs: mediaSeed({
    "groups/pub/posts/nocover": { kind: "video", uid: "u1", storagePath: "groupVideos/u1/nocover.mp4" },
    "groups/pub/posts/text": { kind: "text", uid: "u1" },
    "groups/pub/posts/evil": { kind: "video", uid: "u1", storagePath: "groupVideos/victim/evil.mp4", thumbnailPath: "danceThumbnails/victim/x.jpg" },
  }) });
  const { urls } = await idx.groupMediaUrls(mediaReq("u1", { groupId: "pub", postIds: ["p1", "nocover", "text", "evil", "missing"] }));
  assert.deepEqual(Object.keys(urls).sort(), ["nocover", "p1"]);
  assert.equal(urls.nocover.thumbnailUrl, null);
  assert.ok(!c.bucket.signed.some((s) => s.path.includes("victim")));
});

test("groupMediaUrls signs a post once even when its id is repeated", async () => {
  const c = h.reset({ docs: mediaSeed() });
  await idx.groupMediaUrls(mediaReq("u1", { groupId: "pub", postIds: ["p1", "p1", "p1"] }));
  assert.equal(c.bucket.signed.length, 2); // video + thumbnail
});

test("groupMediaUrls rejects when signing fails", async () => {
  const c = h.reset({ docs: mediaSeed() });
  c.bucket.failSign.add("groupVideos/u1/p1.mp4");
  await assert.rejects(idx.groupMediaUrls(mediaReq("u1", { groupId: "pub", postIds: ["p1"] })), /sign failed/);
});

// ── lockGroupPostMedia ───────────────────────────────────────────────────────

const lockEvent = (c, post, id = "p1") => h.docEvent(c.db, `groups/g1/posts/${id}`, post, { groupId: "g1", postId: id });
const tokenFiles = () => ({
  "groupVideos/u1/p1.mp4": { metadata: { firebaseStorageDownloadTokens: "tok1" } },
  "groupThumbnails/u1/p1.jpg": { metadata: { firebaseStorageDownloadTokens: "tok2" } },
});

test("lockGroupPostMedia removes download tokens, drops stored permanent URLs and marks the post locked", async () => {
  const post = videoPost("p1", "u1", { videoUrl: "https://old/v", thumbnailUrl: "https://old/t" });
  const c = h.reset({ docs: { "groups/g1/posts/p1": post }, files: tokenFiles() });
  await idx.lockGroupPostMedia(lockEvent(c, post));
  assert.equal(c.bucket.files.get("groupVideos/u1/p1.mp4").metadata.firebaseStorageDownloadTokens, null);
  assert.equal(c.bucket.files.get("groupThumbnails/u1/p1.jpg").metadata.firebaseStorageDownloadTokens, null);
  const stored = c.db.data("groups/g1/posts/p1");
  assert.equal(stored.mediaLocked, true);
  assert.equal("videoUrl" in stored, false);
  assert.equal("thumbnailUrl" in stored, false);
});

test("lockGroupPostMedia ignores non-video posts and events without data", async () => {
  const c = h.reset({ docs: { "groups/g1/posts/p1": { kind: "text", uid: "u1" } }, files: tokenFiles() });
  await idx.lockGroupPostMedia(lockEvent(c, { kind: "text", uid: "u1" }));
  await idx.lockGroupPostMedia({ params: { groupId: "g1", postId: "p1" }, data: undefined });
  assert.deepEqual(c.db.log, []);
  assert.deepEqual(c.bucket.metadataWrites, []);
});

test("lockGroupPostMedia is idempotent (a retried event finds nothing left to unlock)", async () => {
  const post = videoPost("p1");
  const c = h.reset({ docs: { "groups/g1/posts/p1": post }, files: tokenFiles() });
  await idx.lockGroupPostMedia(lockEvent(c, post));
  const writes = c.bucket.metadataWrites.length;
  await idx.lockGroupPostMedia(lockEvent(c, post));
  assert.equal(c.bucket.metadataWrites.length, writes); // no second metadata write
  assert.equal(c.db.data("groups/g1/posts/p1").mediaLocked, true);
});

test("lockGroupPostMedia tolerates a file that doesn't exist yet and still locks the post", async () => {
  const post = videoPost("p1");
  const c = h.reset({ docs: { "groups/g1/posts/p1": post } });
  await idx.lockGroupPostMedia(lockEvent(c, post));
  assert.equal(c.db.data("groups/g1/posts/p1").mediaLocked, true);
});

test("lockGroupPostMedia rejects (and doesn't mark the post) when a file can't be updated, so it is retried", async () => {
  const post = videoPost("p1");
  const c = h.reset({ docs: { "groups/g1/posts/p1": post }, files: tokenFiles() });
  c.bucket.failSetMetadata.add("groupVideos/u1/p1.mp4");
  await assert.rejects(idx.lockGroupPostMedia(lockEvent(c, post)), /setMetadata failed/);
  assert.equal(c.db.data("groups/g1/posts/p1").mediaLocked, undefined);
});

// ── groupMemberCount ─────────────────────────────────────────────────────────

const memberEvent = (c, before, after, uid = "m1") =>
  h.change(c.db, `groups/g1/members/${uid}`, before, after, { groupId: "g1", memberUid: uid });

test("groupMemberCount stores the exact member count when someone joins or leaves", async () => {
  const c = h.reset({ docs: {
    "groups/g1": { name: "G", memberCount: 7 },
    "groups/g1/members/m1": { uid: "m1" }, "groups/g1/members/m2": { uid: "m2" }, "groups/g1/members/m3": { uid: "m3" },
    "groups/other/members/x": { uid: "x" },
  } });
  await idx.groupMemberCount(memberEvent(c, undefined, { uid: "m3" }, "m3"));
  assert.equal(c.db.data("groups/g1").memberCount, 3); // exact, other groups not counted
  c.db.docs.delete("groups/g1/members/m3");
  await idx.groupMemberCount(memberEvent(c, { uid: "m3" }, undefined, "m3"));
  assert.equal(c.db.data("groups/g1").memberCount, 2);
});

test("groupMemberCount is idempotent for replayed events", async () => {
  const c = h.reset({ docs: { "groups/g1": { name: "G" }, "groups/g1/members/m1": { uid: "m1" } } });
  await idx.groupMemberCount(memberEvent(c, undefined, { uid: "m1" }));
  await idx.groupMemberCount(memberEvent(c, undefined, { uid: "m1" }));
  assert.equal(c.db.data("groups/g1").memberCount, 1);
});

test("groupMemberCount does nothing (and doesn't recreate the group) when the group was deleted", async () => {
  const c = h.reset({ docs: { "groups/g1/members/m1": { uid: "m1" } } });
  await idx.groupMemberCount(memberEvent(c, { uid: "m1" }, undefined));
  assert.deepEqual(c.db.log, []);
  assert.equal(c.db.has("groups/g1"), false);
});

// ── notifyGroupInvitation ────────────────────────────────────────────────────

const inviteSeed = (extra = {}) => ({
  "groups/g1": { name: "Salsa Club" },
  "groups/g1/members/inviter": { uid: "inviter", role: "owner" },
  "users/inviter": { displayName: "Ann" },
  "users/invitee/fcmTokens/tokA": { at: 1 },
  "users/invitee/fcmTokens/tokB": { at: 2 },
  ...extra,
});
const INVITE = { inviteeUid: "invitee", inviterUid: "inviter", groupId: "g1" };
const inviteEvent = (c, data = INVITE, id = "i1") => h.docEvent(c.db, `groupInvitations/${id}`, data, { invitationId: id });

test("notifyGroupInvitation pushes to all the invitee's devices using server-side names and records the send", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: inviteSeed() });
  await idx.notifyGroupInvitation(inviteEvent(c, { ...INVITE, groupName: "FAKE NAME", inviterName: "FAKE" }));
  assert.equal(c.messaging.sent.length, 1);
  const msg = c.messaging.sent[0];
  assert.deepEqual(msg.tokens.sort(), ["tokA", "tokB"]);
  assert.equal(msg.notification.body, "Ann invited you to join Salsa Club");
  assert.equal(msg.data.type, "group_invitation");
  assert.equal(msg.data.groupId, "g1");
  assert.ok(c.db.has("pushLog/invite_i1"));
  assert.deepEqual(c.db.data("pushRate/inviter"), { hour: Math.floor(NOW / 3_600_000), sent: 1 });
});

test("notifyGroupInvitation ignores invitations missing invitee, inviter or group", async () => {
  const c = h.reset({ docs: inviteSeed() });
  for (const data of [{ ...INVITE, inviteeUid: "" }, { ...INVITE, inviterUid: undefined }, { ...INVITE, groupId: null }, {}]) {
    await idx.notifyGroupInvitation(inviteEvent(c, data));
  }
  await idx.notifyGroupInvitation({ params: { invitationId: "i1" }, data: undefined });
  assert.deepEqual(c.messaging.sent, []);
  assert.deepEqual(c.db.log, []);
});

test("notifyGroupInvitation sends one push per invitation id, however often the event repeats", async () => {
  const c = h.reset({ docs: inviteSeed() });
  await idx.notifyGroupInvitation(inviteEvent(c));
  await idx.notifyGroupInvitation(inviteEvent(c));
  assert.equal(c.messaging.sent.length, 1);
  assert.equal(c.db.data("pushRate/inviter").sent, 1); // duplicate didn't burn rate allowance
  assert.equal(c.logger.entries.info.length >= 1, true);
});

test("notifyGroupInvitation stops after the per-inviter hourly limit", async (t) => {
  h.freezeTime(t, NOW);
  const hour = Math.floor(NOW / 3_600_000);
  const c = h.reset({ docs: inviteSeed({ "pushRate/inviter": { hour, sent: INVITE_PUSHES_PER_HOUR } }) });
  await idx.notifyGroupInvitation(inviteEvent(c));
  assert.deepEqual(c.messaging.sent, []);
  assert.equal(c.db.has("pushLog/invite_i1"), false);
  assert.equal(c.db.data("pushRate/inviter").sent, INVITE_PUSHES_PER_HOUR);
  assert.match(c.logger.entries.info[0][0], /skipped/);
});

test("notifyGroupInvitation allows pushes again in a new hour", async (t) => {
  h.freezeTime(t, NOW);
  const hour = Math.floor(NOW / 3_600_000);
  const c = h.reset({ docs: inviteSeed({ "pushRate/inviter": { hour: hour - 1, sent: INVITE_PUSHES_PER_HOUR } }) });
  await idx.notifyGroupInvitation(inviteEvent(c));
  assert.equal(c.messaging.sent.length, 1);
  assert.deepEqual(c.db.data("pushRate/inviter"), { hour, sent: 1 });
});

test("notifyGroupInvitation sends nothing when the group is gone or the inviter isn't a member", async () => {
  const noGroup = h.reset({ docs: { ...inviteSeed(), "groups/g1": undefined } });
  noGroup.db.docs.delete("groups/g1");
  await idx.notifyGroupInvitation(inviteEvent(noGroup));
  assert.deepEqual(noGroup.messaging.sent, []);

  const notMember = h.reset({ docs: inviteSeed() });
  notMember.db.docs.delete("groups/g1/members/inviter");
  await idx.notifyGroupInvitation(inviteEvent(notMember, INVITE, "i2"));
  assert.deepEqual(notMember.messaging.sent, []);
});

test("notifyGroupInvitation sends nothing when the invitee has no registered devices", async () => {
  const c = h.reset({ docs: inviteSeed() });
  c.db.docs.delete("users/invitee/fcmTokens/tokA");
  c.db.docs.delete("users/invitee/fcmTokens/tokB");
  await idx.notifyGroupInvitation(inviteEvent(c));
  assert.deepEqual(c.messaging.sent, []);
});

test("notifyGroupInvitation names the inviter by display name, then @username, then 'Someone'", async () => {
  const bodies = [];
  for (const user of [{ displayName: "Ann", username: "ann" }, { username: "ann_k" }, {}]) {
    const c = h.reset({ docs: inviteSeed({ "users/inviter": user }) });
    await idx.notifyGroupInvitation(inviteEvent(c));
    bodies.push(c.messaging.sent[0].notification.body);
  }
  assert.deepEqual(bodies, [
    "Ann invited you to join Salsa Club", "@ann_k invited you to join Salsa Club", "Someone invited you to join Salsa Club",
  ]);
});

test("notifyGroupInvitation sends to at most 20 devices", async () => {
  const docs = inviteSeed();
  for (let i = 0; i < 25; i++) docs[`users/invitee/fcmTokens/t${String(i).padStart(2, "0")}`] = { at: i };
  const c = h.reset({ docs });
  await idx.notifyGroupInvitation(inviteEvent(c));
  assert.equal(c.messaging.sent[0].tokens.length, 20);
});

test("notifyGroupInvitation deletes only the tokens FCM reports as dead", async () => {
  const c = h.reset({ docs: inviteSeed({ "users/invitee/fcmTokens/tokC": { at: 3 } }) });
  c.messaging.respond = (msg) => {
    const codes = { tokA: "messaging/registration-token-not-registered", tokB: "messaging/internal-error", tokC: "messaging/invalid-registration-token" };
    const responses = msg.tokens.map((t) => ({ success: false, error: { code: codes[t] } }));
    return { responses, successCount: 0, failureCount: msg.tokens.length };
  };
  await idx.notifyGroupInvitation(inviteEvent(c));
  assert.deepEqual(c.db.paths("users/invitee/fcmTokens/"), ["users/invitee/fcmTokens/tokB"]);
  assert.equal(c.logger.entries.info.at(-1)[1].removed, 2);
});

test("notifyGroupInvitation rejects when FCM itself throws", async () => {
  const c = h.reset({ docs: inviteSeed() });
  c.messaging.sendEachForMulticast = async () => { throw new Error("fcm down"); };
  await assert.rejects(idx.notifyGroupInvitation(inviteEvent(c)), /fcm down/);
});

// ── groupCleanup ─────────────────────────────────────────────────────────────

const CODE = "ABCD2345";
const cleanupSeed = (extra = {}) => ({
  "groups/g1/members/m1": { uid: "m1" },
  "groups/g1/posts/p1": videoPost("p1"),
  "groups/g1/posts/p1/replies/r1": { uid: "u2" },
  "groups/g1/posts/p2": { kind: "dance", uid: "u1", danceId: "d1" },
  [`groupInvites/${CODE}`]: { groupId: "g1" },
  "groupInvitations/inv1": { groupId: "g1", inviteeUid: "x" },
  "groupInvitations/inv2": { groupId: "g1", inviteeUid: "y" },
  "groupInvitations/other": { groupId: "g2", inviteeUid: "z" },
  "groups/g2/members/m9": { uid: "m9" },
  ...extra,
});
const cleanupFiles = () => ({
  "groupVideos/u1/p1.mp4": {}, "groupThumbnails/u1/p1.jpg": {}, "groupVideos/u1/unrelated.mp4": {}, "danceVideos/u1/d1.mp4": {},
});
const delEvent = (c, groupData = { inviteCode: CODE }) => h.docEvent(c.db, "groups/g1", groupData, { groupId: "g1" });

test("groupCleanup removes the group's subcollections, its post media, invite code and pending invitations", async () => {
  const c = h.reset({ docs: cleanupSeed(), files: cleanupFiles() });
  await idx.groupCleanup(delEvent(c));
  assert.deepEqual(c.db.paths("groups/g1"), []);
  assert.equal(c.db.has(`groupInvites/${CODE}`), false);
  assert.deepEqual(c.db.paths("groupInvitations/"), ["groupInvitations/other"]);
  assert.ok(c.db.has("groups/g2/members/m9"));
  assert.deepEqual(c.bucket.deleted.map((d) => d.path).sort(), ["groupThumbnails/u1/p1.jpg", "groupVideos/u1/p1.mp4"]);
  assert.ok(c.bucket.deleted.every((d) => d.opts.ignoreNotFound === true));
  assert.ok(c.bucket.files.has("danceVideos/u1/d1.mp4")); // feed videos are never touched
  assert.ok(c.bucket.files.has("groupVideos/u1/unrelated.mp4"));
});

test("groupCleanup won't delete another group's invite document even if the group names its code", async () => {
  const c = h.reset({ docs: cleanupSeed({ [`groupInvites/${CODE}`]: { groupId: "g2" } }) });
  await idx.groupCleanup(delEvent(c));
  assert.ok(c.db.has(`groupInvites/${CODE}`));
});

test("groupCleanup ignores a malformed invite code and tolerates a missing invite document", async () => {
  const c = h.reset({ docs: cleanupSeed() });
  await idx.groupCleanup(delEvent(c, { inviteCode: "../users/victim" }));
  assert.ok(!c.db.reads.some((p) => p.startsWith("users/") || p.includes("..")));
  assert.ok(c.db.has(`groupInvites/${CODE}`)); // not this group's declared code, left alone

  const c2 = h.reset({ docs: cleanupSeed() });
  c2.db.docs.delete(`groupInvites/${CODE}`);
  await idx.groupCleanup(delEvent(c2));
  assert.deepEqual(c2.db.paths("groups/g1"), []);
});

test("groupCleanup works for a group with no posts, no invite code and no snapshot data", async () => {
  const c = h.reset({ docs: { "groups/g1/members/m1": { uid: "m1" } } });
  await idx.groupCleanup(delEvent(c, {}));
  await idx.groupCleanup({ params: { groupId: "g1" }, data: undefined });
  assert.deepEqual(c.db.paths("groups/g1"), []);
  assert.deepEqual(c.bucket.deleted, []);
});

test("groupCleanup logs a file that can't be deleted and still removes everything else", async () => {
  const c = h.reset({ docs: cleanupSeed(), files: cleanupFiles() });
  c.bucket.failDelete.add("groupVideos/u1/p1.mp4");
  await idx.groupCleanup(delEvent(c));
  assert.equal(c.logger.entries.warn.length, 1);
  assert.equal(c.logger.entries.warn[0][1].path, "groupVideos/u1/p1.mp4");
  assert.equal(c.bucket.files.has("groupThumbnails/u1/p1.jpg"), false);
  assert.deepEqual(c.db.paths("groups/g1"), []);
  assert.equal(c.db.has(`groupInvites/${CODE}`), false);
});

test("groupCleanup only deletes media from the post author's own folders", async () => {
  const c = h.reset({
    docs: { "groups/g1/posts/evil": { kind: "video", uid: "attacker", storagePath: "groupVideos/victim/v.mp4", thumbnailPath: "groupThumbnails/victim/t.jpg" } },
    files: { "groupVideos/victim/v.mp4": {}, "groupThumbnails/victim/t.jpg": {} },
  });
  await idx.groupCleanup(delEvent(c, {}));
  assert.deepEqual(c.bucket.deleted, []);
  assert.equal(c.bucket.files.size, 2);
});

test("groupCleanup is safe to repeat", async () => {
  const c = h.reset({ docs: cleanupSeed(), files: cleanupFiles() });
  await idx.groupCleanup(delEvent(c));
  await idx.groupCleanup(delEvent(c));
  assert.deepEqual(c.db.paths("groups/g1"), []);
  assert.deepEqual(c.db.paths("groupInvitations/"), ["groupInvitations/other"]);
});

test("groupCleanup rejects when the recursive delete fails so the event is retried", async () => {
  const c = h.reset({ docs: cleanupSeed() });
  c.db.onWrite = (op) => { if (op === "recursiveDelete") throw new Error("delete failed"); };
  await assert.rejects(idx.groupCleanup(delEvent(c)), /delete failed/);
});
