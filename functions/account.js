// Account deletion: what to remove when a user deletes their account.

/** Storage folders that belong to one user. */
function userStoragePrefixes(uid) {
  return [
    `danceVideos/${uid}/`,
    `danceThumbnails/${uid}/`,
    `danceTemplates/${uid}/`,
    `groupVideos/${uid}/`,
    `groupThumbnails/${uid}/`,
    `profilePhotos/${uid}/`,
  ];
}

module.exports = { userStoragePrefixes };
