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

test("falls back to generic text for missing or disabled dances", () => {
  for (const dance of [null, { title: "Hidden", enabled: false }]) {
    const html = renderDancePage(dance, "https://chorevo-dea05.web.app/dance/x");
    assert.match(html, /og:title" content="Check out this dance on Chorevo"/);
    assert.doesNotMatch(html, /og:video/);
  }
});
