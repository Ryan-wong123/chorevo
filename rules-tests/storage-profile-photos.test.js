// Storage rules not covered elsewhere: profilePhotos, size caps of thumbnails,
// danceTemplates writes (typed, scoped, legacy read-only) and the default deny.
const test = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const { ref, uploadBytes, getBytes, listAll, deleteObject } = require("firebase/storage");

let env;
const root = path.join(__dirname, "..");

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-chorevo-storage-gaps",
    firestore: { rules: fs.readFileSync(path.join(root, "firestore.rules"), "utf8") },
    storage: { rules: fs.readFileSync(path.join(root, "storage.rules"), "utf8") },
  });
});

test.after(async () => {
  await env?.cleanup();
});

test.beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
});

const storageAs = (uid) => env.authenticatedContext(uid).storage();
const anon = () => env.unauthenticatedContext().storage();
const bytes = (n) => new Uint8Array(n);
const MB = 1024 * 1024;
const jpeg = { contentType: "image/jpeg" };

// ── profilePhotos ───────────────────────────────────────────────────────────

test("profile photos: any signed-in user can read one by path, anonymous users can't", async () => {
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/avatar.jpg"), bytes(4), jpeg));
  await assertSucceeds(getBytes(ref(storageAs("bob"), "profilePhotos/alice/avatar.jpg")));
  await assertSucceeds(getBytes(ref(storageAs("alice"), "profilePhotos/alice/avatar.jpg")));
  await assertFails(getBytes(ref(anon(), "profilePhotos/alice/avatar.jpg")));
});

test("profile photos: nobody can list a profilePhotos folder", async () => {
  await uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/avatar.jpg"), bytes(4), jpeg);
  await assertFails(listAll(ref(storageAs("alice"), "profilePhotos/alice")));
  await assertFails(listAll(ref(storageAs("bob"), "profilePhotos/alice")));
  await assertFails(listAll(ref(anon(), "profilePhotos/alice")));
});

test("profile photos: only the owner can write, overwrite or delete in their folder", async () => {
  await assertFails(uploadBytes(ref(storageAs("bob"), "profilePhotos/alice/avatar.jpg"), bytes(4), jpeg));
  await assertFails(uploadBytes(ref(anon(), "profilePhotos/alice/avatar.jpg"), bytes(4), jpeg));
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/avatar.jpg"), bytes(4), jpeg));
  // Overwrite (update) by owner works, by someone else doesn't.
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/avatar.jpg"), bytes(8), jpeg));
  await assertFails(uploadBytes(ref(storageAs("bob"), "profilePhotos/alice/avatar.jpg"), bytes(8), jpeg));
  await assertFails(deleteObject(ref(storageAs("bob"), "profilePhotos/alice/avatar.jpg")));
  await assertFails(deleteObject(ref(anon(), "profilePhotos/alice/avatar.jpg")));
  await assertSucceeds(deleteObject(ref(storageAs("alice"), "profilePhotos/alice/avatar.jpg")));
});

test("profile photos: must be an image under 10 MB", async () => {
  const photo = ref(storageAs("alice"), "profilePhotos/alice/p.png");
  await assertSucceeds(uploadBytes(photo, bytes(1024), { contentType: "image/png" }));
  await assertFails(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/p.html"), bytes(16), { contentType: "text/html" }));
  await assertFails(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/v.mp4"), bytes(16), { contentType: "video/mp4" }));
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/big-ok.jpg"), bytes(10 * MB - 1), jpeg));
  await assertFails(uploadBytes(ref(storageAs("alice"), "profilePhotos/alice/too-big.jpg"), bytes(10 * MB), jpeg));
});

// ── Thumbnails: type, name and size ─────────────────────────────────────────

test("cover frames: .jpg names, image type, under 10 MB (dance and group thumbnails)", async () => {
  for (const folder of ["danceThumbnails", "groupThumbnails"]) {
    const s = storageAs("alice");
    await assertSucceeds(uploadBytes(ref(s, `${folder}/alice/c1.jpg`), bytes(1024), jpeg));
    await assertFails(uploadBytes(ref(s, `${folder}/alice/c1.png`), bytes(16), { contentType: "image/png" }));
    await assertFails(uploadBytes(ref(s, `${folder}/alice/c2.jpg`), bytes(16), { contentType: "video/mp4" }));
    await assertFails(uploadBytes(ref(s, `${folder}/alice/c3.jpg`), bytes(10 * MB), jpeg));
    await assertFails(uploadBytes(ref(storageAs("bob"), `${folder}/alice/c4.jpg`), bytes(16), jpeg));
    await assertFails(listAll(ref(storageAs("alice"), `${folder}/alice`)));
    await assertFails(getBytes(ref(storageAs("bob"), `${folder}/alice/c1.jpg`)));
  }
});

test("group videos: owner-only .mp4 video uploads, no listing, no reads by others", async () => {
  const v = { contentType: "video/mp4" };
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "groupVideos/alice/p1.mp4"), bytes(16), v));
  await assertFails(uploadBytes(ref(storageAs("alice"), "groupVideos/alice/p1.mov"), bytes(16), v));
  await assertFails(uploadBytes(ref(storageAs("alice"), "groupVideos/alice/p2.mp4"), bytes(16), { contentType: "image/jpeg" }));
  await assertFails(uploadBytes(ref(storageAs("bob"), "groupVideos/alice/p3.mp4"), bytes(16), v));
  await assertFails(getBytes(ref(storageAs("bob"), "groupVideos/alice/p1.mp4")));
  await assertSucceeds(getBytes(ref(storageAs("alice"), "groupVideos/alice/p1.mp4")));
  await assertFails(listAll(ref(storageAs("alice"), "groupVideos/alice")));
});

// ── Pose templates ──────────────────────────────────────────────────────────

test("pose templates: owner uploads typed JSON in their own folder only", async () => {
  const json = { contentType: "application/json" };
  const body = new TextEncoder().encode("{}");
  await assertSucceeds(uploadBytes(ref(storageAs("alice"), "danceTemplates/alice/d1.json"), body, json));
  await assertFails(uploadBytes(ref(storageAs("bob"), "danceTemplates/alice/d2.json"), body, json));
  await assertFails(uploadBytes(ref(storageAs("alice"), "danceTemplates/alice/d3.json"), body, { contentType: "text/plain" }));
  await assertFails(uploadBytes(ref(storageAs("alice"), "danceTemplates/alice/d4.txt"), body, json));
  await assertFails(uploadBytes(ref(storageAs("alice"), "danceTemplates/alice/big.json"), bytes(20 * MB), json));
  await assertFails(listAll(ref(storageAs("alice"), "danceTemplates/alice")));
  await assertFails(deleteObject(ref(storageAs("bob"), "danceTemplates/alice/d1.json")));
  await assertSucceeds(deleteObject(ref(storageAs("alice"), "danceTemplates/alice/d1.json")));
});

test("legacy unscoped pose templates can no longer be written, even by a signed-in user", async () => {
  const json = { contentType: "application/json" };
  const body = new TextEncoder().encode("{}");
  await assertFails(uploadBytes(ref(storageAs("alice"), "danceTemplates/legacy.json"), body, json));
  await assertFails(listAll(ref(storageAs("alice"), "danceTemplates")));
});

// ── Default deny ────────────────────────────────────────────────────────────

test("any path without a rule is closed to reads and writes", async () => {
  await assertFails(uploadBytes(ref(storageAs("alice"), "misc/alice/file.jpg"), bytes(4), jpeg));
  await assertFails(uploadBytes(ref(storageAs("alice"), "file.jpg"), bytes(4), jpeg));
  await assertFails(getBytes(ref(storageAs("alice"), "misc/alice/file.jpg")));
  await assertFails(listAll(ref(storageAs("alice"), "misc")));
});
