// Pure helpers for the group functions in index.js.

/** Storage paths of group-only videos and thumbnails uploaded by these posts. */
function storagePathsOfPosts(posts) {
  const paths = new Set();
  for (const post of posts) {
    for (const p of [post.storagePath, post.thumbnailPath]) {
      // Only files in the group folders; never touch the main danceVideos/.
      if (typeof p === "string" && /^group(Videos|Thumbnails)\//.test(p)) paths.add(p);
    }
  }
  return [...paths];
}

module.exports = { storagePathsOfPosts };
