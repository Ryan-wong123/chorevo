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
