// Firestore rules tests for groups: creation, joining (public / invite code),
// visibility of private groups, posting, replies, leaving and removal.
const test = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  collectionGroup,
  query,
  where,
  getDocs,
  writeBatch,
} = require("firebase/firestore");

let env;

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-chorevo-groups",
    firestore: { rules: fs.readFileSync(path.join(__dirname, "..", "firestore.rules"), "utf8") },
  });
});

test.after(async () => {
  await env?.cleanup();
});

const as = (uid) => env.authenticatedContext(uid).firestore();
const now = 1_700_000_000_000;

function groupData(id, visibility, ownerUid, extra = {}) {
  return {
    id,
    name: `Group ${id}`,
    nameLower: `group ${id}`,
    description: "We dance",
    visibility,
    ownerUid,
    ownerName: ownerUid,
    createdAt: now,
    ...(visibility === "private" ? { inviteCode: "ABCD2345" } : {}),
    ...extra,
  };
}

const member = (uid, role = "member", extra = {}) => ({
  uid,
  displayName: uid,
  photoUrl: null,
  role,
  joinedAt: now,
  ...extra,
});

test.beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "groups/pub"), groupData("pub", "public", "owner"));
    await setDoc(doc(db, "groups/pub/members/owner"), member("owner", "owner"));
    await setDoc(doc(db, "groups/priv"), groupData("priv", "private", "owner"));
    await setDoc(doc(db, "groups/priv/members/owner"), member("owner", "owner"));
    await setDoc(doc(db, "groupInvites/ABCD2345"), { groupId: "priv", createdAt: now });
    await setDoc(doc(db, "groups/priv/posts/p1"), {
      id: "p1", uid: "owner", displayName: "owner", photoUrl: null, body: "secret", kind: "text", createdAt: now,
    });
  });
});

test("creating a group needs the owner's membership in the same batch", async () => {
  const db = as("alice");
  const batch = writeBatch(db);
  batch.set(doc(db, "groups/g1"), groupData("g1", "public", "alice"));
  batch.set(doc(db, "groups/g1/members/alice"), member("alice", "owner"));
  await assertSucceeds(batch.commit());

  await assertFails(setDoc(doc(db, "groups/g2"), groupData("g2", "public", "alice")));
});

test("a private group is created with its invite document", async () => {
  const db = as("alice");
  const batch = writeBatch(db);
  batch.set(doc(db, "groups/g3"), groupData("g3", "private", "alice", { inviteCode: "QWER5678" }));
  batch.set(doc(db, "groups/g3/members/alice"), member("alice", "owner"));
  batch.set(doc(db, "groupInvites/QWER5678"), { groupId: "g3", createdAt: now });
  await assertSucceeds(batch.commit());
});

test("nobody can claim someone else's group or make themselves owner later", async () => {
  const db = as("mallory");
  const batch = writeBatch(db);
  batch.set(doc(db, "groups/g4"), groupData("g4", "public", "alice"));
  batch.set(doc(db, "groups/g4/members/mallory"), member("mallory", "owner"));
  await assertFails(batch.commit());
  await assertFails(setDoc(doc(db, "groups/pub/members/mallory"), member("mallory", "owner")));
});

test("anyone can see and join a public group", async () => {
  const db = as("bob");
  await assertSucceeds(getDoc(doc(db, "groups/pub")));
  await assertSucceeds(getDocs(query(collection(db, "groups"), where("visibility", "==", "public"))));
  await assertSucceeds(setDoc(doc(db, "groups/pub/members/bob"), member("bob")));
  await assertFails(setDoc(doc(db, "groups/pub/members/carol"), member("carol")));
});

test("a private group is hidden until joined with the right invite code", async () => {
  const db = as("bob");
  await assertFails(getDoc(doc(db, "groups/priv")));
  await assertFails(getDocs(collection(db, "groups/priv/posts")));
  await assertFails(getDocs(collection(db, "groups")));

  await assertSucceeds(getDoc(doc(db, "groupInvites/ABCD2345")));
  await assertFails(getDocs(collection(db, "groupInvites")));

  await assertFails(setDoc(doc(db, "groups/priv/members/bob"), member("bob")));
  await assertFails(setDoc(doc(db, "groups/priv/members/bob"), member("bob", "member", { inviteCode: "WRONG000" })));
  await assertSucceeds(setDoc(doc(db, "groups/priv/members/bob"), member("bob", "member", { inviteCode: "ABCD2345" })));

  await assertSucceeds(getDoc(doc(db, "groups/priv")));
  await assertSucceeds(getDocs(collection(db, "groups/priv/posts")));
});

test("only members post and reply, as themselves", async () => {
  const bob = as("bob");
  const post = { id: "p2", uid: "bob", displayName: "bob", photoUrl: null, body: "hi", kind: "text", createdAt: now };
  await assertFails(setDoc(doc(bob, "groups/pub/posts/p2"), post));

  await setDoc(doc(bob, "groups/pub/members/bob"), member("bob"));
  await assertSucceeds(setDoc(doc(bob, "groups/pub/posts/p2"), post));
  await assertFails(setDoc(doc(bob, "groups/pub/posts/p3"), { ...post, id: "p3", uid: "owner" }));
  await assertFails(setDoc(doc(bob, "groups/pub/posts/p4"), { ...post, id: "p4", body: "" }));
  await assertSucceeds(setDoc(doc(bob, "groups/pub/posts/p5"), {
    ...post, id: "p5", body: "", kind: "dance", danceId: "d1", danceTitle: "Salsa",
  }));

  const reply = { id: "r1", postId: "p2", uid: "bob", displayName: "bob", photoUrl: null, body: "nice", createdAt: now };
  await assertSucceeds(setDoc(doc(bob, "groups/pub/posts/p2/replies/r1"), reply));
  await assertFails(setDoc(doc(as("carol"), "groups/pub/posts/p2/replies/r2"), { ...reply, id: "r2", uid: "carol" }));
});

test("members leave; the owner can't leave but can remove members and delete posts", async () => {
  await setDoc(doc(as("bob"), "groups/pub/members/bob"), member("bob"));
  await setDoc(doc(as("carol"), "groups/pub/members/carol"), member("carol"));
  await setDoc(doc(as("bob"), "groups/pub/posts/pb"), {
    id: "pb", uid: "bob", displayName: "bob", photoUrl: null, body: "x", kind: "text", createdAt: now,
  });

  await assertSucceeds(deleteDoc(doc(as("carol"), "groups/pub/members/carol")));
  await assertFails(deleteDoc(doc(as("owner"), "groups/pub/members/owner")));
  await assertFails(deleteDoc(doc(as("carol"), "groups/pub/posts/pb")));
  await assertSucceeds(deleteDoc(doc(as("owner"), "groups/pub/posts/pb")));
  await assertSucceeds(deleteDoc(doc(as("owner"), "groups/pub/members/bob")));
});

test("the owner edits name and description only, and can delete the group", async () => {
  const owner = as("owner");
  await assertSucceeds(updateDoc(doc(owner, "groups/pub"), { name: "Salsa Crew", nameLower: "salsa crew", description: "New" }));
  await assertFails(updateDoc(doc(owner, "groups/pub"), { visibility: "private" }));
  await assertFails(updateDoc(doc(owner, "groups/pub"), { memberCount: 999 }));
  await assertFails(updateDoc(doc(as("bob"), "groups/pub"), { description: "hacked" }));
  await assertFails(deleteDoc(doc(as("bob"), "groups/pub")));
  await assertSucceeds(deleteDoc(doc(owner, "groups/pub")));
});

test("my groups: a user can list only their own memberships", async () => {
  await setDoc(doc(as("bob"), "groups/pub/members/bob"), member("bob"));
  await assertSucceeds(getDocs(query(collectionGroup(as("bob"), "members"), where("uid", "==", "bob"))));
  await assertFails(getDocs(query(collectionGroup(as("bob"), "members"), where("uid", "==", "owner"))));
});
