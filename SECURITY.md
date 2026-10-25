# Chorevo security

This is how Chorevo protects its users and data. The steps still to do in the
Firebase, Google Cloud, GitHub and Play consoles before the Play Store release
are in [DEPLOYMENT.md](DEPLOYMENT.md).

## Threat model

Anyone can install the app, create an account and call Firebase directly with
the public config in `google-services.json`. All protection therefore lives in
three places: the Firestore and Storage rules, the Cloud Functions, and App
Check. Nothing relies on the app behaving well.

## Data access (firestore.rules, storage.rules)

| Data | Who can read | Who can write |
|---|---|---|
| `users/{uid}` profile | signed-in users, one document at a time (no listing) | the owner. Fields are validated: no email, no role, name ≤ 60, https photo URL. A username must be claimed in `usernames/`, and the old one released. |
| `users/{uid}/*` (points, badges, streaks, challenges, session rewards) | the owner | **Cloud Functions only** (awarded from synced sessions) |
| `users/{uid}/danceLearningSessions` | the owner, and the creator of the session's dance | the owner, create only (final once uploaded). Every score is 0–1, the time-zone offset is real, and a session's dance must exist. |
| `leaderboardStats` | signed-in users | **Cloud Functions only** (computed from sessions and streaks) |
| `danceLibrary` | public dances: signed-in users; private dances: the uploader only | the uploader. Text is validated. Media must be the uploader's own Storage files. Upload time, media and `enabled` can't be changed later. |
| likes / comments | whoever can read the dance | as yourself, on dances you can read. Comments carry a bounded name and the current time. |
| groups, members, posts, replies | public groups: signed-in users; private groups: members | members only. Post media must be the poster's own files for that post. Removed members are banned (`bans/`) and can't rejoin. |
| `groupInvites/{code}` | single lookup only | the group owner, together with the group |
| `groupInvitations` | invitee and inviter | members of the group, for users who aren't banned or already in it |
| `usernames` | single lookups, at most 20 per query | the claiming user only, together with their profile |
| Storage | **no folder listing anywhere**. Dance and group videos and covers can be read by path only by their owner; everyone else plays them through download URLs. Profile photos and templates: signed-in users. | owner only, with type, size and file name checks |

All timestamps a client sets must be within 1 hour of the server's clock (24
hours for dances).

## Server side (functions/)

- `rewardsFromSession` awards points, badges, the weekly streak, weekly
  challenge progress and leaderboard stats from each synced session, once per
  session, in one transaction. Scores are clamped, days come from the
  server's clock, active days can't exceed the session count, and at most 30
  sessions per user per day earn anything. `weeklyChallenges` (callable)
  creates a user's weekly challenges.
- `notifyGroupInvitation` sends at most one push per invitation and at most
  20 per inviter per hour. Group and inviter names come from the server's own
  data, and the inviter must be a member.
- `groupCleanup` deletes only files in the post author's own folders, and only
  the group's own invite code.
- `deleteUserData` (Firebase Auth `onDelete`) removes the user's profile,
  username, dances, likes, comments, groups they own, memberships, posts,
  replies, invitations, leaderboard entry and all of their Storage folders.
- `revokeLinksWhenPrivate` gives a dance's video and cover new download
  tokens when it is made private (only files in the uploader's own dance
  folders), so links shared earlier stop working.
- `danceShare` accepts only well-formed dance IDs and renders only public
  dances. It embeds media only from this project's Storage and escapes all
  text.
- All functions are capped at 10 instances (`setGlobalOptions`) to bound cost
  under abuse.
- The scheduled `refreshDiscovery` job also removes email addresses that older
  app versions stored on profiles.

## Web (public/, firebase.json)

- Every page sends a strict Content-Security-Policy (scripts only from the
  site itself, no framing), plus `X-Frame-Options: DENY`, `nosniff`, HSTS,
  `Referrer-Policy` and `Permissions-Policy`.
- Page scripts live in `public/js/` rather than inline.
- `delete-account.html` is the public account-deletion page that Google Play
  requires. **Its contact line is a placeholder; fill it in.**

## App (app/)

- **Release build:** R8 shrinking and obfuscation are on (keep rules in
  `app/proguard-rules.pro`), and debug, info and verbose logs are stripped.
- **App Check:** release builds use Play Integrity; the debug provider exists
  only in debug builds.
- **Backups:** they contain only the reminder and theme preferences, never the
  sign-in session, local database, Firestore cache or videos.
- **Sign-up and sign-in:**
  - Passwords need at least 8 characters, and sign-up sends a verification
    email.
  - A password reset link is available.
  - Error messages don't reveal which emails have accounts.
  - Google sign-in uses a fresh nonce for each request.
- **Account deletion:** Settings → Delete account.
- **Sign-out:** clears pending links, invite codes and the user's cached
  recordings.
- **Group media:** videos and covers in group posts are only loaded from this
  project's Storage, never from arbitrary URLs or local `file://` or
  `content://` URIs.
- **Group invites:** a scanned QR code always asks for confirmation before
  joining a group, and copied invite codes are marked sensitive.
- **Permissions:** unused photo permissions were removed. Recording asks for
  camera permission first.

## CI (.github/)

- Third-party actions are pinned to commit SHAs, and Dependabot proposes
  updates weekly.
- The deploy job runs in the `production` environment and installs
  dependencies with `--ignore-scripts` before any credentials reach the
  runner.

## Release signing and Play Store checklist

Creating the upload key, signing, and every console step before releasing
(Firebase, Google Cloud, GitHub, Play Console including the Data safety
answers) are in [DEPLOYMENT.md](DEPLOYMENT.md).

## Known limitations

- Download URLs of dance and group videos work for anyone who has the URL.
  Making a dance private revokes its video and cover links
  (`revokeLinksWhenPrivate`), so copied links and group posts that shared it
  stop playing; dances made private before that function existed keep their
  old links until they are made public and private again. Group post videos
  have no privacy setting and keep working for anyone with the link.
- Practice scores are computed on the phone. Everything derived from them
  (points, badges, streaks, challenges, leaderboard) is computed by the
  server, which clamps scores and limits rewarded sessions per day, but a
  modified app could still submit good scores. Fully preventing that would
  need server-side pose scoring.
- A removed group member stays banned until the group owner unbans them
  (members dialog → Removed → Unban).
