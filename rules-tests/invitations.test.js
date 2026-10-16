// Firestore rules tests for usernames and group invitations by username.
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
  deleteDoc,
  collection,
  query,
  where,
  orderBy,
  limit,
  getDocs,
  writeBatch,
  documentId,
} = require("firebase/firestore");

let env;

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-chorevo-invitations",
    firestore: { rules: fs.readFileSync(path.join(__dirname, "..", "firestore.rules"), "utf8") },
  });
});

test.after(async () => {
  await env?.cleanup();
});

const as = (uid) => env.authenticatedContext(uid).firestore();
const now = Date.now();

function claim(db, uid, handle, old) {
  const batch = writeBatch(db);
  batch.set(doc(db, `usernames/${handle}`), { uid, createdAt: now });
  batch.set(doc(db, `users/${uid}`), { uid, username: handle }, { merge: true });
  if (old) batch.delete(doc(db, `usernames/${old}`));
  return batch.commit();
}

const invitation = (inviteeUid, extra = {}) => ({
  groupId: "priv",
  groupName: "Group priv",
  inviteeUid,
  inviteeUsername: inviteeUid,
  inviterUid: "owner",
  inviterName: "owner",
  createdAt: now,
  ...extra,
});

test.beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const uid of ["owner", "bob", "carol", "mallory"]) {
      await setDoc(doc(db, `users/${uid}`), { uid, displayName: uid });
    }
    await setDoc(doc(db, "usernames/carol_d"), { uid: "carol", createdAt: now });
    await setDoc(doc(db, "users/carol"), { uid: "carol", displayName: "carol", username: "carol_d" });
    await setDoc(doc(db, "groups/priv"), {
      id: "priv", name: "Group priv", nameLower: "group priv", description: "", visibility: "private",
      ownerUid: "owner", ownerName: "owner", inviteCode: "ABCD2345", createdAt: now,
    });
    await setDoc(doc(db, "groups/priv/members/owner"), {
      uid: "owner", displayName: "owner", photoUrl: null, role: "owner", joinedAt: now,
    });
  });
});

test("a user claims a free username together with their profile", async () => {
  const bob = as("bob");
  await assertSucceeds(claim(bob, "bob", "bob_dance"));
  await assertSucceeds(getDoc(doc(bob, "usernames/bob_dance")));
});

test("usernames are unique and well-formed", async () => {
  const bob = as("bob");
  await assertFails(claim(bob, "bob", "carol_d")); // taken
  await assertFails(claim(bob, "bob", "Bob")); // upper case
  await assertFails(claim(bob, "bob", "ab")); // too short
  // A claim without the profile field, or for someone else, fails.
  await assertFails(setDoc(doc(bob, "usernames/bob_x"), { uid: "bob", createdAt: now }));
  await assertFails(setDoc(doc(bob, "usernames/bob_y"), { uid: "mallory", createdAt: now }));
});

test("a profile can't show a username it doesn't own", async () => {
  await assertFails(setDoc(doc(as("bob"), "users/bob"), { username: "carol_d" }, { merge: true }));
  // Other profile writes still work.
  await assertSucceeds(setDoc(doc(as("carol"), "users/carol"), { lastSeen: now }, { merge: true }));
});

test("changing username releases the old one; nobody else can delete it", async () => {
  await assertFails(deleteDoc(doc(as("mallory"), "usernames/carol_d")));
  await assertFails(deleteDoc(doc(as("carol"), "usernames/carol_d"))); // still on the profile
  await assertSucceeds(claim(as("carol"), "carol", "carol_new", "carol_d"));
  await assertSucceeds(claim(as("bob"), "bob", "carol_d"));
});

test("usernames can be searched by prefix, a page at a time", async () => {
  const q = (n) => query(collection(as("bob"), "usernames"), orderBy(documentId()), where(documentId(), ">=", "car"), limit(n));
  await assertSucceeds(getDocs(q(10)));
  await assertFails(getDocs(q(100)));
});

test("members invite other users to a group", async () => {
  const owner = as("owner");
  await assertSucceeds(setDoc(doc(owner, "groupInvitations/priv_bob"), invitation("bob")));
  // Wrong id, wrong group name, someone already in it, yourself, or a missing user.
  await assertFails(setDoc(doc(owner, "groupInvitations/x"), invitation("carol")));
  await assertFails(setDoc(doc(owner, "groupInvitations/priv_carol"), invitation("carol", { groupName: "Fake" })));
  await assertFails(setDoc(doc(owner, "groupInvitations/priv_owner"), invitation("owner")));
  await assertFails(setDoc(doc(owner, "groupInvitations/priv_ghost"), invitation("ghost")));
  // Non-members can't invite, or pretend to be someone else.
  await assertFails(setDoc(doc(as("mallory"), "groupInvitations/priv_carol"), invitation("carol", { inviterUid: "mallory" })));
  await assertFails(setDoc(doc(as("mallory"), "groupInvitations/priv_carol"), invitation("carol")));
});

test("only the invitee (and inviter) read an invitation; the invitee joins with it", async () => {
  await setDoc(doc(as("owner"), "groupInvitations/priv_bob"), invitation("bob"));

  await assertSucceeds(getDocs(query(collection(as("bob"), "groupInvitations"), where("inviteeUid", "==", "bob"))));
  await assertFails(getDoc(doc(as("mallory"), "groupInvitations/priv_bob")));
  await assertFails(getDocs(query(collection(as("mallory"), "groupInvitations"), where("inviteeUid", "==", "bob"))));

  // Without an invitation (or code) carol can't join the private group.
  await assertFails(setDoc(doc(as("carol"), "groups/priv/members/carol"), {
    uid: "carol", displayName: "carol", photoUrl: null, role: "member", joinedAt: now,
  }));

  const bob = as("bob");
  const batch = writeBatch(bob);
  batch.set(doc(bob, "groups/priv/members/bob"), { uid: "bob", displayName: "bob", photoUrl: null, role: "member", joinedAt: now });
  batch.delete(doc(bob, "groupInvitations/priv_bob"));
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDoc(doc(bob, "groups/priv")));
});

test("the invitee can decline; others can't delete the invitation", async () => {
  await setDoc(doc(as("owner"), "groupInvitations/priv_bob"), invitation("bob"));
  await assertFails(deleteDoc(doc(as("mallory"), "groupInvitations/priv_bob")));
  await assertSucceeds(deleteDoc(doc(as("bob"), "groupInvitations/priv_bob")));
});

test("users keep their own push tokens; nobody else can read or write them", async () => {
  const bob = as("bob");
  await assertSucceeds(setDoc(doc(bob, "users/bob/fcmTokens/tok1"), { token: "tok1", platform: "android", updatedAt: now }));
  await assertFails(setDoc(doc(bob, "users/bob/fcmTokens/tok2"), { token: "other", platform: "android", updatedAt: now }));
  await assertFails(setDoc(doc(bob, "users/bob/fcmTokens/tok3"), { token: "tok3", extra: true }));
  await assertFails(getDoc(doc(as("mallory"), "users/bob/fcmTokens/tok1")));
  await assertFails(setDoc(doc(as("mallory"), "users/bob/fcmTokens/tokm"), { token: "tokm", platform: "android", updatedAt: now }));
  await assertSucceeds(deleteDoc(doc(bob, "users/bob/fcmTokens/tok1")));
});
