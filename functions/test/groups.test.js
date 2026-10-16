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
