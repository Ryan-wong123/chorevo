const test = require("node:test");
const assert = require("node:assert");
const { becamePrivate, storagePathFromDownloadUrl, downloadUrl, revocableMedia } = require("../privacy");

const BUCKET = "chorevo-dea05.appspot.com";
const url = (path, token = "t1") => downloadUrl(BUCKET, path, token);

test("only a public-to-private change counts", () => {
  assert.strictEqual(becamePrivate({ visibility: "public" }, { visibility: "private" }), true);
  assert.strictEqual(becamePrivate({}, { visibility: "private" }), true); // older dances had no field
  assert.strictEqual(becamePrivate({ visibility: "private" }, { visibility: "private" }), false);
  assert.strictEqual(becamePrivate({ visibility: "private" }, { visibility: "public" }), false);
  assert.strictEqual(becamePrivate(null, { visibility: "private" }), false);
  assert.strictEqual(becamePrivate({ visibility: "public" }, null), false);
});

test("download URLs round-trip to their storage path", () => {
  const u = url("danceVideos/alice/d1.mp4", "abc");
  assert.strictEqual(u, "https://firebasestorage.googleapis.com/v0/b/chorevo-dea05.appspot.com/o/danceVideos%2Falice%2Fd1.mp4?alt=media&token=abc");
  assert.strictEqual(storagePathFromDownloadUrl(u, BUCKET), "danceVideos/alice/d1.mp4");
});

test("anything that isn't this bucket's download URL is ignored", () => {
  assert.strictEqual(storagePathFromDownloadUrl("", BUCKET), null);
  assert.strictEqual(storagePathFromDownloadUrl(null, BUCKET), null);
  assert.strictEqual(storagePathFromDownloadUrl("not a url", BUCKET), null);
  assert.strictEqual(storagePathFromDownloadUrl("http://firebasestorage.googleapis.com/v0/b/chorevo-dea05.appspot.com/o/a", BUCKET), null);
  assert.strictEqual(storagePathFromDownloadUrl("https://evil.example/v0/b/chorevo-dea05.appspot.com/o/a", BUCKET), null);
  assert.strictEqual(storagePathFromDownloadUrl(downloadUrl("other-bucket", "danceVideos/alice/d1.mp4", "t"), BUCKET), null);
});

test("only the uploader's own dance video and cover are revoked", () => {
  const media = revocableMedia({
    educatorUid: "alice",
    videoUrl: url("danceVideos/alice/d1.mp4"),
    thumbnailUrl: url("danceThumbnails/alice/d1.jpg"),
  }, BUCKET);
  assert.deepStrictEqual(media, [
    { field: "videoUrl", path: "danceVideos/alice/d1.mp4" },
    { field: "thumbnailUrl", path: "danceThumbnails/alice/d1.jpg" },
  ]);

  assert.deepStrictEqual(revocableMedia({
    educatorUid: "alice",
    videoUrl: url("danceVideos/bob/d1.mp4"),            // someone else's file
    thumbnailUrl: url("groupThumbnails/alice/p1.jpg"),   // not a dance file
  }, BUCKET), []);
  assert.deepStrictEqual(revocableMedia({ educatorUid: "alice", videoUrl: url("danceVideos/alice/x/../d.mp4") }, BUCKET), []);
  assert.deepStrictEqual(revocableMedia({ videoUrl: url("danceVideos/alice/d1.mp4") }, BUCKET), []);
});

// ── Rotating links ───────────────────────────────────────────────────────────

const { rotateDanceLinks, revokeOldPrivateLinks } = require("../privacy");

function fakeBucket(name = BUCKET, failing = new Set()) {
  const metadata = {};
  return {
    name,
    metadata,
    file: (path) => ({
      setMetadata: async (m) => {
        if (failing.has(path)) throw new Error("boom");
        metadata[path] = m.metadata.firebaseStorageDownloadTokens;
      },
    }),
  };
}

const tokens = () => {
  let n = 0;
  return () => `new${++n}`;
};
const quiet = { warn: () => {} };

test("rotating gives each file a new token and returns the new URLs", async () => {
  const bucket = fakeBucket();
  const updates = await rotateDanceLinks(bucket, {
    danceId: "d1",
    educatorUid: "alice",
    videoUrl: url("danceVideos/alice/d1.mp4", "old"),
    thumbnailUrl: url("danceThumbnails/alice/d1.jpg", "old"),
  }, { newToken: tokens(), nowMs: 42, logger: quiet });

  assert.deepStrictEqual(bucket.metadata, {
    "danceVideos/alice/d1.mp4": "new1",
    "danceThumbnails/alice/d1.jpg": "new2",
  });
  assert.deepStrictEqual(updates, {
    videoUrl: url("danceVideos/alice/d1.mp4", "new1"),
    thumbnailUrl: url("danceThumbnails/alice/d1.jpg", "new2"),
    linksRevokedAt: 42,
  });
});

test("a file that can't be updated keeps its URL", async () => {
  const bucket = fakeBucket(BUCKET, new Set(["danceVideos/alice/d1.mp4"]));
  const updates = await rotateDanceLinks(bucket, {
    educatorUid: "alice",
    videoUrl: url("danceVideos/alice/d1.mp4", "old"),
    thumbnailUrl: url("danceThumbnails/alice/d1.jpg", "old"),
  }, { newToken: tokens(), nowMs: 1, logger: quiet });
  assert.strictEqual(updates.videoUrl, undefined);
  assert.strictEqual(updates.thumbnailUrl, url("danceThumbnails/alice/d1.jpg", "new2"));
});

test("the catch-up pass revokes only private dances not done yet", async () => {
  const docs = [
    { id: "a", data: { danceId: "a", educatorUid: "alice", visibility: "private", videoUrl: url("danceVideos/alice/a.mp4", "old") } },
    { id: "b", data: { danceId: "b", educatorUid: "alice", visibility: "private", linksRevokedAt: 5, videoUrl: url("danceVideos/alice/b.mp4", "kept") } },
  ];
  const written = {};
  const db = {
    collection: (name) => {
      assert.strictEqual(name, "danceLibrary");
      return {
        where: (field, op, value) => {
          assert.deepStrictEqual([field, op, value], ["visibility", "==", "private"]);
          return {
            get: async () => ({
              size: docs.length,
              docs: docs.map((d) => ({ data: () => d.data, ref: { update: async (u) => { written[d.id] = u; } } })),
            }),
          };
        },
      };
    },
  };
  const result = await revokeOldPrivateLinks(db, fakeBucket(), { newToken: tokens(), nowMs: 9, logger: quiet });
  assert.deepStrictEqual(result, { privateDances: 2, revoked: 1 });
  assert.deepStrictEqual(written, { a: { videoUrl: url("danceVideos/alice/a.mp4", "new1"), linksRevokedAt: 9 } });
});
