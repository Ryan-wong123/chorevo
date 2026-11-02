// danceShare: the /dance/<id> link-preview page.
const test = require("node:test");
const assert = require("node:assert/strict");
const h = require("./support/harness");

const { danceShare } = h.loadIndex();
const call = async (path) => {
  const res = h.fakeRes();
  await danceShare({ path }, res);
  return res;
};
const PUBLIC_DANCE = { title: "Salsa Basics", educatorName: "Maria", visibility: "public", enabled: true, description: "Step one" };

test("danceShare renders a public dance's title and byline into the page with 200 and security headers", async () => {
  h.reset({ docs: { "danceLibrary/abc123": PUBLIC_DANCE } });
  const res = await call("/dance/abc123");
  assert.equal(res.statusCode, 200);
  assert.match(res.body, /Salsa Basics/);
  assert.match(res.body, /by Maria/);
  assert.match(res.body, /https:\/\/chorevo-dea05\.web\.app\/dance\/abc123/);
  assert.equal(res.headers["Cache-Control"], "public, max-age=120, s-maxage=300");
  assert.equal(res.headers["X-Content-Type-Options"], "nosniff");
  assert.equal(res.headers["X-Frame-Options"], "DENY");
  assert.equal(res.headers["Referrer-Policy"], "strict-origin-when-cross-origin");
});

test("danceShare falls back to the generic page for a missing dance", async () => {
  h.reset();
  const res = await call("/dance/nope");
  assert.equal(res.statusCode, 200);
  assert.match(res.body, /Check out this dance on Chorevo/);
});

test("danceShare never reveals a private or disabled dance", async () => {
  h.reset({ docs: {
    "danceLibrary/priv": { ...PUBLIC_DANCE, title: "Secret Choreo", visibility: "private" },
    "danceLibrary/off": { ...PUBLIC_DANCE, title: "Hidden Choreo", enabled: false },
  } });
  for (const id of ["priv", "off"]) {
    const res = await call(`/dance/${id}`);
    assert.equal(res.statusCode, 200);
    assert.doesNotMatch(res.body, /Secret Choreo|Hidden Choreo/);
    assert.match(res.body, /Check out this dance on Chorevo/);
  }
});

test("danceShare does not touch Firestore for a malformed path", async () => {
  const c = h.reset({ docs: { "danceLibrary/abc": PUBLIC_DANCE } });
  for (const p of ["/", "/dance/", "/dance/a/b", "/dance/bad%2Fid", "/other/abc"]) {
    const res = await call(p);
    assert.equal(res.statusCode, 200);
    assert.match(res.body, /Check out this dance on Chorevo/);
  }
  assert.deepEqual(c.db.reads, []);
});

test("danceShare escapes HTML in user-written fields", async () => {
  h.reset({ docs: { "danceLibrary/x": { ...PUBLIC_DANCE, title: '<script>alert("x")</script>' } } });
  const res = await call("/dance/x");
  assert.doesNotMatch(res.body, /<script>alert/);
  assert.match(res.body, /&lt;script&gt;/);
});

test("danceShare logs and still serves the fallback page when Firestore fails", async () => {
  const c = h.reset({ docs: { "danceLibrary/abc": PUBLIC_DANCE } });
  c.db.onRead = () => { throw Object.assign(new Error("firestore down"), { code: 14 }); };
  const res = await call("/dance/abc");
  assert.equal(res.statusCode, 200);
  assert.match(res.body, /Check out this dance on Chorevo/);
  assert.equal(c.logger.entries.error.length, 1);
  assert.equal(c.logger.entries.error[0][1].danceId, "abc");
  assert.equal(c.logger.entries.error[0][1].errorMessage, "firestore down");
});
