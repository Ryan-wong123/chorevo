const test = require("node:test");
const assert = require("node:assert");
const { parseDanceId, renderDancePage } = require("../render");

test("parseDanceId extracts the id", () => {
  assert.strictEqual(parseDanceId("/dance/abc123"), "abc123");
  assert.strictEqual(parseDanceId("/dance/abc123/"), "abc123");
  assert.strictEqual(parseDanceId("/dance/"), null);
  assert.strictEqual(parseDanceId("/other/abc"), null);
  assert.strictEqual(parseDanceId("/dance/a/b"), null);
});

test("renders the dance's own title in the preview tags", () => {
  const html = renderDancePage(
    { title: "Hip Hop Basics", educatorName: "Mia", enabled: true, videoUrl: "https://firebasestorage.googleapis.com/v0/b/chorevo-dea05.firebasestorage.app/o/danceVideos%2Fu1%2Fv.mp4?alt=media&token=t" },
    "https://chorevo-dea05.web.app/dance/abc"
  );
  assert.match(html, /<meta property="og:title" content="Hip Hop Basics">/);
  assert.match(html, /<meta name="twitter:title" content="Hip Hop Basics">/);
  assert.match(html, /og:description" content="by Mia · /);
  assert.match(html, /<title>Hip Hop Basics \| Chorevo<\/title>/);
  assert.match(html, /og:video" content="https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/chorevo-dea05\.firebasestorage\.app\/o\/danceVideos%2Fu1%2Fv\.mp4\?alt=media&amp;token=t"/);
  assert.match(html, /<video class="video" src="https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/chorevo-dea05\.firebasestorage\.app\/o\/danceVideos%2Fu1%2Fv\.mp4\?alt=media&amp;token=t"/);
  assert.doesNotMatch(html, /\{\{\w+\}\}/);
});

test("escapes user-provided text", () => {
  const html = renderDancePage(
    { title: '"><script>alert(1)</script>', educatorName: "A&B", enabled: true },
    "https://chorevo-dea05.web.app/dance/x"
  );
  assert.doesNotMatch(html, /<script>alert/);
  assert.match(html, /&quot;&gt;&lt;script&gt;/);
  assert.match(html, /by A&amp;B/);
});

test("falls back to generic text for missing, disabled or private dances", () => {
  const privateDance = { title: "Secret", enabled: true, visibility: "private", videoUrl: "https://x/s.mp4" };
  for (const dance of [null, { title: "Hidden", enabled: false }, privateDance]) {
    const html = renderDancePage(dance, "https://chorevo-dea05.web.app/dance/x");
    assert.match(html, /og:title" content="Check out this dance on Chorevo"/);
    assert.doesNotMatch(html, /og:video/);
    assert.doesNotMatch(html, /<video/);
    assert.doesNotMatch(html, /Secret|s\.mp4/);
  }
});

test("uses the dance's cover thumbnail as the preview image", () => {
  const html = renderDancePage(
    { title: "Salsa", enabled: true, videoUrl: "https://firebasestorage.googleapis.com/v0/b/chorevo-dea05.firebasestorage.app/o/danceVideos%2Fu1%2Fv.mp4?alt=media&token=t", thumbnailUrl: "https://firebasestorage.googleapis.com/v0/b/chorevo-dea05.firebasestorage.app/o/danceThumbnails%2Fu1%2Ft.jpg?alt=media" },
    "https://chorevo-dea05.web.app/dance/abc"
  );
  assert.match(html, /<meta property="og:image" content="https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/chorevo-dea05\.firebasestorage\.app\/o\/danceThumbnails%2Fu1%2Ft\.jpg\?alt=media">/);
  assert.match(html, /<meta name="twitter:image" content="https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/chorevo-dea05\.firebasestorage\.app\/o\/danceThumbnails%2Fu1%2Ft\.jpg\?alt=media">/);
  assert.match(html, /<meta name="twitter:card" content="summary_large_image">/);
  assert.match(html, /poster="https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/chorevo-dea05\.firebasestorage\.app\/o\/danceThumbnails%2Fu1%2Ft\.jpg\?alt=media"/);
});

test("falls back to the branded preview image without a thumbnail or for private dances", () => {
  const { DEFAULT_IMAGE } = require("../render");
  const plain = renderDancePage({ title: "Salsa", enabled: true }, "https://chorevo-dea05.web.app/dance/a");
  assert.ok(plain.includes(`<meta property="og:image" content="${DEFAULT_IMAGE}">`));
  const priv = renderDancePage(
    { title: "Secret", enabled: true, visibility: "private", thumbnailUrl: "https://x/secret.jpg" },
    "https://chorevo-dea05.web.app/dance/b"
  );
  assert.doesNotMatch(priv, /secret\.jpg/);
  assert.ok(priv.includes(DEFAULT_IMAGE));
});

test("parseDanceId rejects ids that could address other documents", () => {
  assert.strictEqual(parseDanceId("/dance/abc%2Fcomments%2Fxyz"), null);
  assert.strictEqual(parseDanceId("/dance/..%2F.."), null);
  assert.strictEqual(parseDanceId("/dance/" + "a".repeat(200)), null);
  assert.strictEqual(parseDanceId("/dance/Ab_c-9"), "Ab_c-9");
});

test("only this project's dance media is rendered", () => {
  const html = renderDancePage(
    { title: "Salsa", enabled: true, videoUrl: "https://evil.example/v.mp4", thumbnailUrl: "https://evil.example/t.jpg" },
    "https://chorevo-dea05.web.app/dance/a"
  );
  assert.doesNotMatch(html, /evil\.example/);
  assert.doesNotMatch(html, /<video/);
});

test("dances with an unknown visibility are not shown", () => {
  const html = renderDancePage(
    { title: "Unlisted", enabled: true, visibility: "Private" },
    "https://chorevo-dea05.web.app/dance/a"
  );
  assert.doesNotMatch(html, /Unlisted/);
});
