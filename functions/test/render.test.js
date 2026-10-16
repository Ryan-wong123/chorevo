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
    { title: "Hip Hop Basics", educatorName: "Mia", enabled: true, videoUrl: "https://x/v.mp4" },
    "https://chorevo-dea05.web.app/dance/abc"
  );
  assert.match(html, /<meta property="og:title" content="Hip Hop Basics">/);
  assert.match(html, /<meta name="twitter:title" content="Hip Hop Basics">/);
  assert.match(html, /og:description" content="by Mia · /);
  assert.match(html, /<title>Hip Hop Basics \| Chorevo<\/title>/);
  assert.match(html, /og:video" content="https:\/\/x\/v.mp4"/);
  assert.match(html, /<video class="video" src="https:\/\/x\/v.mp4"/);
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
    { title: "Salsa", enabled: true, videoUrl: "https://x/v.mp4", thumbnailUrl: "https://x/t.jpg" },
    "https://chorevo-dea05.web.app/dance/abc"
  );
  assert.match(html, /<meta property="og:image" content="https:\/\/x\/t.jpg">/);
  assert.match(html, /<meta name="twitter:image" content="https:\/\/x\/t.jpg">/);
  assert.match(html, /<meta name="twitter:card" content="summary_large_image">/);
  assert.match(html, /poster="https:\/\/x\/t.jpg"/);
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
