// functions/account.js: storage folders removed when a user deletes their account.
// (userStoragePrefixes is the module's only export; the deleteUserData handler
// that uses it is covered in index.deleteUserData.test.js.)
const test = require("node:test");
const assert = require("node:assert/strict");
const { userStoragePrefixes } = require("../account");

test("userStoragePrefixes lists every per-user Storage folder, each ending in a slash", () => {
  assert.deepEqual(userStoragePrefixes("abc"), [
    "danceVideos/abc/", "danceThumbnails/abc/", "danceTemplates/abc/",
    "groupVideos/abc/", "groupThumbnails/abc/", "profilePhotos/abc/",
  ]);
});

test("userStoragePrefixes' trailing slash keeps one user's prefixes from matching another user's files", () => {
  const prefixes = userStoragePrefixes("u1");
  for (const other of ["danceVideos/u10/a.mp4", "profilePhotos/u1x/me.jpg", "groupVideos/xu1/a.mp4"]) {
    assert.ok(!prefixes.some((p) => other.startsWith(p)), other);
  }
});

test("userStoragePrefixes gives distinct, non-overlapping prefixes for different users", () => {
  const a = userStoragePrefixes("alice");
  const b = userStoragePrefixes("bob");
  assert.equal(new Set(a).size, a.length);
  assert.ok(a.every((p) => !b.includes(p)));
});
