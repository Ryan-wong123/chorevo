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

module.exports = { becamePrivate, storagePathFromDownloadUrl, downloadUrl, revocableMedia };
