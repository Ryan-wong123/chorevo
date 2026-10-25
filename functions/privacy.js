/**
 * Revoking a dance's download links when it is made private.
 *
 * Firebase Storage download URLs carry an access token, so anyone who copied
 * one keeps access. Giving the file a new token is the only way to cut those
 * links off; the dance then gets the new URLs (its owner still plays it).
 */

const STORAGE_HOST = "firebasestorage.googleapis.com";

/** True when this update turned a visible dance private. */
function becamePrivate(before, after) {
  if (!before || !after) return false;
  return before.visibility !== "private" && after.visibility === "private";
}

/**
 * The object path inside [bucketName] that a Firebase download URL points to,
 * or null when it isn't a download URL for that bucket.
 */
function storagePathFromDownloadUrl(url, bucketName) {
  if (typeof url !== "string" || !url) return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:" || parsed.hostname !== STORAGE_HOST) return null;
  const match = parsed.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
  if (!match || match[1] !== bucketName) return null;
  try {
    return decodeURIComponent(match[2]);
  } catch {
    return null;
  }
}

/** A Firebase download URL for [path] with [token]. */
function downloadUrl(bucketName, path, token) {
  return `https://${STORAGE_HOST}/v0/b/${bucketName}/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
}

/**
 * The dance's video and cover files whose links should be revoked: only
 * files in the uploader's own dance folders (never anyone else's files).
 * Returns [{ field, path }].
 */
function revocableMedia(dance, bucketName) {
  const owner = dance && dance.educatorUid;
  if (typeof owner !== "string" || !owner) return [];
  const folders = { videoUrl: `danceVideos/${owner}/`, thumbnailUrl: `danceThumbnails/${owner}/` };
  const media = [];
  for (const [field, folder] of Object.entries(folders)) {
    const path = storagePathFromDownloadUrl(dance[field], bucketName);
    if (path && path.startsWith(folder) && !path.slice(folder.length).includes("/")) {
      media.push({ field, path });
    }
  }
  return media;
}

/**
 * Gives the dance's video and cover new download tokens. Returns the fields to
 * write to the dance: the new URLs plus `linksRevokedAt`. A file that can't be
 * updated keeps its URL (and is logged).
 */
async function rotateDanceLinks(bucket, dance, { newToken, nowMs = Date.now(), logger = console } = {}) {
  const updates = {};
  for (const { field, path } of revocableMedia(dance, bucket.name)) {
    const token = newToken();
    try {
      await bucket.file(path).setMetadata({ metadata: { firebaseStorageDownloadTokens: token } });
      updates[field] = downloadUrl(bucket.name, path, token);
    } catch (err) {
      logger.warn?.("Couldn't revoke a download link", { danceId: dance.danceId, path, error: String(err && err.message) });
    }
  }
  updates.linksRevokedAt = nowMs;
  return updates;
}

/**
 * One-off catch-up for dances made private before revocation existed: rotates
 * the links of every private dance without `linksRevokedAt`. Safe to run
 * repeatedly (later runs find nothing to do).
 */
async function revokeOldPrivateLinks(db, bucket, { newToken, nowMs = Date.now(), logger = console } = {}) {
  const snap = await db.collection("danceLibrary").where("visibility", "==", "private").get();
  let revoked = 0;
  for (const doc of snap.docs) {
    const dance = doc.data();
    if (dance.linksRevokedAt) continue;
    await doc.ref.update(await rotateDanceLinks(bucket, dance, { newToken, nowMs, logger }));
    revoked += 1;
  }
  return { privateDances: snap.size, revoked };
}

module.exports = {
  becamePrivate, storagePathFromDownloadUrl, downloadUrl, revocableMedia, rotateDanceLinks, revokeOldPrivateLinks,
};
