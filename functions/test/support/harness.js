// Test harness for functions/index.js: stubs the Firebase SDK modules so every
// exported handler is the plain function it wraps, and provides in-memory fakes
// of Firestore, Storage and Messaging. (Loaded by the *.test.js files; it holds
// no tests itself.)
const Module = require("node:module");
const path = require("node:path");

// ── Sentinels (FieldValue / AggregateField) ──────────────────────────────────
const DELETE = { __op: "delete" };
const SERVER_TIMESTAMP = { __op: "serverTimestamp" };
const increment = (n) => ({ __op: "increment", n });
const isSentinel = (v) => v && typeof v === "object" && typeof v.__op === "string";
const clone = (v) => (v === undefined || isSentinel(v) ? v : structuredClone(v));

class HttpsError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/** Applies a write to the previous document data. `deep` merges nested maps (set with merge). */
function resolveWrite(prev, data, deep) {
  const out = { ...(prev || {}) };
  for (const [k, v] of Object.entries(data)) {
    if (v === DELETE) delete out[k];
    else if (v && v.__op === "increment") out[k] = (Number(out[k]) || 0) + v.n;
    else if (deep && v && typeof v === "object" && !Array.isArray(v) && !isSentinel(v)) out[k] = resolveWrite(out[k], v, true);
    else out[k] = clone(v);
  }
  return out;
}

// ── Fake Firestore ───────────────────────────────────────────────────────────
class FakeFirestore {
  constructor(seed = {}) {
    this.docs = new Map(); // path -> data
    this.createTimes = new Map();
    this.log = []; // every write: { op, path, data }
    this.reads = []; // paths read through doc().get()
    this.onWrite = null; // (op, path) => may throw to simulate a failure
    this.onRead = null; // (path) => may throw
    this.transactions = 0;
    this.batchSizes = []; // operations per committed batch
    for (const [p, d] of Object.entries(seed)) this.docs.set(p, structuredClone(d));
  }

  doc(p) { return new DocRef(this, p); }
  collection(p) { return new CollectionRef(this, p); }
  collectionGroup(name) { return new Query(this, (p) => { const s = p.split("/"); return s.length % 2 === 0 && s[s.length - 2] === name; }); }
  async getAll(...refs) { return Promise.all(refs.map((r) => r.get())); }
  data(p) { return this.docs.get(p); }
  has(p) { return this.docs.has(p); }
  paths(prefix = "") { return [...this.docs.keys()].filter((p) => p.startsWith(prefix)).sort(); }
  writesTo(p) { return this.log.filter((w) => w.path === p); }

  _write(op, p, data, merge) {
    if (this.onWrite) this.onWrite(op, p);
    this.log.push({ op, path: p, data: data && structuredClone(data) });
    if (op === "delete") { this.docs.delete(p); return; }
    const exists = this.docs.has(p);
    if (op === "update") {
      if (!exists) throw Object.assign(new Error(`NOT_FOUND: ${p}`), { code: 5 });
      this.docs.set(p, resolveWrite(this.docs.get(p), data, false));
      return;
    }
    if (!exists) this.createTimes.set(p, 1_700_000_000_000);
    this.docs.set(p, merge ? resolveWrite(this.docs.get(p), data, true) : resolveWrite({}, data, false));
  }

  async runTransaction(fn) {
    this.transactions += 1;
    const pending = [];
    const tx = {
      get: async (ref) => ref.get(),
      set: (ref, data, opts) => { pending.push(["set", ref.path, data, !!(opts && opts.merge)]); return tx; },
      update: (ref, data) => { pending.push(["update", ref.path, data]); return tx; },
      delete: (ref) => { pending.push(["delete", ref.path]); return tx; },
    };
    const result = await fn(tx);
    for (const [op, p, data, merge] of pending) this._write(op, p, data, merge);
    return result;
  }

  batch() {
    const ops = [];
    const b = {
      update: (ref, data) => ops.push(["update", ref.path, data]),
      set: (ref, data, opts) => ops.push(["set", ref.path, data, !!(opts && opts.merge)]),
      delete: (ref) => ops.push(["delete", ref.path]),
      commit: async () => { this.batchSizes.push(ops.length); for (const [op, p, data, merge] of ops) this._write(op, p, data, merge); },
    };
    return b;
  }

  bulkWriter() {
    const refs = [];
    return {
      delete: (ref) => refs.push(ref),
      close: async () => { for (const r of refs) this._write("delete", r.path); },
    };
  }

  async recursiveDelete(ref) {
    if (this.onWrite) this.onWrite("recursiveDelete", ref.path);
    this.log.push({ op: "recursiveDelete", path: ref.path });
    for (const p of [...this.docs.keys()]) {
      if (p === ref.path || p.startsWith(`${ref.path}/`)) this.docs.delete(p);
    }
  }
}

class Snapshot {
  constructor(db, p, data) {
    this.ref = new DocRef(db, p);
    this.id = this.ref.id;
    this.exists = data !== undefined;
    this._data = data;
    this.createTime = { toMillis: () => db.createTimes.get(p) ?? 1_700_000_000_000 };
  }
  data() { return this.exists ? clone(this._data) : undefined; }
  get(field) { return this.exists ? clone(this._data[field]) : undefined; }
}

class DocRef {
  constructor(db, p) {
    this.db = db;
    this.path = p;
    this.id = p.split("/").pop();
  }
  get parent() { return new CollectionRef(this.db, this.path.split("/").slice(0, -1).join("/")); }
  collection(name) { return new CollectionRef(this.db, `${this.path}/${name}`); }
  async get() {
    this.db.reads.push(this.path);
    if (this.db.onRead) this.db.onRead(this.path);
    return new Snapshot(this.db, this.path, this.db.docs.get(this.path));
  }
  async set(data, opts) { this.db._write("set", this.path, data, !!(opts && opts.merge)); }
  async update(data) { this.db._write("update", this.path, data); }
  async delete() { this.db._write("delete", this.path); }
}

class Query {
  constructor(db, match, opts = {}) {
    this.db = db;
    this.match = match;
    this.filters = opts.filters || [];
    this.order = opts.order || null;
    this.max = opts.max ?? Infinity;
  }
  _with(extra) { return new Query(this.db, this.match, { filters: this.filters, order: this.order, max: this.max, ...extra }); }
  where(field, op, value) { return this._with({ filters: [...this.filters, [field, op, value]] }); }
  orderBy(field, dir = "asc") { return this._with({ order: [field, dir] }); }
  limit(n) { return this._with({ max: n }); }
  _rows() {
    let rows = [...this.db.docs.entries()].filter(([p]) => this.match(p));
    for (const [field, op, value] of this.filters) {
      rows = rows.filter(([, d]) => {
        if (op === "==") return d[field] === value;
        if (op === "!=") return value === null ? d[field] !== undefined && d[field] !== null : d[field] !== undefined && d[field] !== value;
        throw new Error(`unsupported op ${op}`);
      });
    }
    if (this.order) {
      const [field, dir] = this.order;
      rows = rows.filter(([, d]) => d[field] !== undefined);
      rows.sort((a, b) => (a[1][field] < b[1][field] ? -1 : a[1][field] > b[1][field] ? 1 : 0) * (dir === "desc" ? -1 : 1));
    }
    return rows.slice(0, this.max);
  }
  async get() {
    const docs = this._rows().map(([p, d]) => new Snapshot(this.db, p, d));
    return { docs, size: docs.length, empty: docs.length === 0 };
  }
  count() { return { get: async () => ({ data: () => ({ count: this._rows().length }) }) }; }
  aggregate(spec) {
    return {
      get: async () => {
        const rows = this._rows().map(([, d]) => d);
        const out = {};
        for (const [k, a] of Object.entries(spec)) {
          out[k] = a.type === "count" ? rows.length : rows.reduce((s, d) => s + (Number(d[a.field]) || 0), 0);
        }
        return { data: () => out };
      },
    };
  }
}

class CollectionRef extends Query {
  constructor(db, p) {
    super(db, (docPath) => docPath.startsWith(`${p}/`) && !docPath.slice(p.length + 1).includes("/"));
    this.path = p;
    this.id = p.split("/").pop();
  }
  get parent() {
    const segs = this.path.split("/");
    return segs.length > 1 ? new DocRef(this.db, segs.slice(0, -1).join("/")) : null;
  }
  doc(id) { return new DocRef(this.db, `${this.path}/${id}`); }
}

// ── Fake Storage ─────────────────────────────────────────────────────────────
class FakeBucket {
  constructor(files = {}, name = "chorevo-dea05.firebasestorage.app") {
    this.name = name;
    this.files = new Map(Object.entries(files)); // path -> { metadata: {...} }
    this.deleted = [];
    this.metadataWrites = [];
    this.signed = [];
    this.deleteFilesCalls = [];
    this.failDelete = new Set();
    this.failSetMetadata = new Set();
    this.failDeleteFilesPrefix = new Set();
    this.failSign = new Set();
  }
  file(p) {
    const bucket = this;
    return {
      name: p,
      async getMetadata() {
        if (!bucket.files.has(p)) throw Object.assign(new Error("No such object"), { code: 404 });
        return [structuredClone(bucket.files.get(p))];
      },
      async setMetadata(meta) {
        if (bucket.failSetMetadata.has(p)) throw new Error(`setMetadata failed for ${p}`);
        if (!bucket.files.has(p)) throw Object.assign(new Error("No such object"), { code: 404 });
        bucket.metadataWrites.push({ path: p, meta });
        const cur = bucket.files.get(p);
        bucket.files.set(p, { ...cur, metadata: { ...cur.metadata, ...meta.metadata } });
      },
      async delete(opts) {
        if (bucket.failDelete.has(p)) throw new Error(`delete failed for ${p}`);
        bucket.deleted.push({ path: p, opts });
        bucket.files.delete(p);
      },
      async getSignedUrl(opts) {
        if (bucket.failSign.has(p)) throw new Error(`sign failed for ${p}`);
        bucket.signed.push({ path: p, opts });
        return [`https://signed.example/${p}?exp=${opts.expires}`];
      },
    };
  }
  async deleteFiles({ prefix }) {
    this.deleteFilesCalls.push(prefix);
    if (this.failDeleteFilesPrefix.has(prefix)) throw new Error(`deleteFiles failed for ${prefix}`);
    for (const p of [...this.files.keys()]) if (p.startsWith(prefix)) this.files.delete(p);
  }
}

// ── Fake Messaging ───────────────────────────────────────────────────────────
class FakeMessaging {
  constructor() {
    this.sent = [];
    this.respond = (msg) => ({ responses: msg.tokens.map(() => ({ success: true })), successCount: msg.tokens.length, failureCount: 0 });
  }
  async sendEachForMulticast(msg) {
    this.sent.push(msg);
    return this.respond(msg);
  }
}

// ── Logger ───────────────────────────────────────────────────────────────────
function makeLogger() {
  const entries = { info: [], warn: [], error: [] };
  return {
    entries,
    info: (...a) => entries.info.push(a),
    warn: (...a) => entries.warn.push(a),
    error: (...a) => entries.error.push(a),
  };
}

// Mutable context the stubbed SDK getters read from; tests replace its fields.
const ctx = { db: null, bucket: null, messaging: null, logger: makeLogger() };

const identity = (...args) => args[args.length - 1];
const trigger = (opts, handler) => handler;

const stubs = {
  "firebase-functions/v2": { setGlobalOptions: () => {} },
  "firebase-functions/v2/https": { onRequest: trigger, onCall: trigger, HttpsError },
  "firebase-functions/v2/firestore": {
    onDocumentWritten: trigger, onDocumentUpdated: trigger, onDocumentDeleted: trigger, onDocumentCreated: trigger,
  },
  "firebase-functions/v2/scheduler": { onSchedule: trigger },
  "firebase-functions/logger": ctx.logger,
  "firebase-functions/v1": {
    region: () => ({ runWith: () => ({ auth: { user: () => ({ onDelete: identity }) } }) }),
  },
  "firebase-admin/app": { initializeApp: () => {} },
  "firebase-admin/firestore": {
    getFirestore: () => ctx.db,
    FieldValue: { delete: () => DELETE, increment, serverTimestamp: () => SERVER_TIMESTAMP },
    AggregateField: { count: () => ({ type: "count" }), sum: (field) => ({ type: "sum", field }) },
  },
  "firebase-admin/storage": { getStorage: () => ({ bucket: () => ctx.bucket }) },
  "firebase-admin/messaging": { getMessaging: () => ctx.messaging },
};

let installed = false;
function install() {
  if (installed) return;
  installed = true;
  const realLoad = Module._load;
  Module._load = function (request, parent, isMain) {
    if (Object.prototype.hasOwnProperty.call(stubs, request)) return stubs[request];
    return realLoad.call(this, request, parent, isMain);
  };
}

/** Loads functions/index.js against the stubs; every export is its raw handler. */
function loadIndex() {
  install();
  return require(path.join(__dirname, "..", "..", "index.js"));
}

/** Fresh fakes for a test; returns them (also stored in ctx). */
function reset({ docs = {}, files = {} } = {}) {
  ctx.db = new FakeFirestore(docs);
  ctx.bucket = new FakeBucket(files);
  ctx.messaging = new FakeMessaging();
  for (const k of Object.keys(ctx.logger.entries)) ctx.logger.entries[k].length = 0;
  return ctx;
}

/** Freezes Date.now() at `ms` until the test ends. */
function freezeTime(t, ms) {
  const real = Date.now;
  Date.now = () => ms;
  t.after(() => { Date.now = real; });
}

/** Change event for onDocumentWritten / onDocumentUpdated: before/after data (undefined = absent). */
function change(db, docPath, before, after, params = {}) {
  const snap = (d) => ({ exists: d !== undefined, data: () => (d === undefined ? undefined : structuredClone(d)), ref: db.doc(docPath), get: (f) => (d === undefined ? undefined : d[f]) });
  return { params, data: { before: snap(before), after: snap(after) } };
}

/** Event for onDocumentCreated / onDocumentDeleted: data is the document snapshot. */
function docEvent(db, docPath, data, params = {}) {
  return {
    params,
    data: data === undefined ? undefined : { ref: db.doc(docPath), id: docPath.split("/").pop(), exists: true, data: () => structuredClone(data), get: (f) => data[f] },
  };
}

/** Minimal Express-like response. */
function fakeRes() {
  const res = { headers: {}, statusCode: null, body: null };
  res.set = (k, v) => { res.headers[k] = v; return res; };
  res.status = (n) => { res.statusCode = n; return res; };
  res.send = (b) => { res.body = b; return res; };
  return res;
}

module.exports = {
  ctx, install, loadIndex, reset, freezeTime, change, docEvent, fakeRes,
  FakeFirestore, FakeBucket, FakeMessaging, HttpsError, DELETE, SERVER_TIMESTAMP, increment,
};
