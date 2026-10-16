# Chorevo security

This is how Chorevo protects its users and data. It also lists what still has
to be done in the Firebase, Google Cloud, GitHub and Play consoles before the
Play Store release.

## Threat model

Anyone can install the app, create an account and call Firebase directly with
the public config in `google-services.json`. All protection therefore lives in
three places: the Firestore and Storage rules, the Cloud Functions, and App
Check. Nothing relies on the app behaving well.

## Data access (firestore.rules, storage.rules)

| Data | Who can read | Who can write |
|---|---|---|
| `users/{uid}` profile | signed-in users, one document at a time (no listing) | the owner. Fields are validated: no email, no role, name ≤ 60, https photo URL. A username must be claimed in `usernames/`, and the old one released. |
| `users/{uid}/*` (points, streaks, sessions, tokens…) | the owner; a session can also be read by the creator of its dance | the owner. Streak totals rise by at most 1 per write. Session scores must be 0–1. A session's dance must exist. |
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

- `leaderboardFrom*` recompute leaderboard stats. Scores are clamped and
  active days can't exceed the session count.
- `notifyGroupInvitation` sends at most one push per invitation and at most
  20 per inviter per hour. Group and inviter names come from the server's own
  data, and the inviter must be a member.
- `groupCleanup` deletes only files in the post author's own folders, and only
  the group's own invite code.
- `deleteUserData` (Firebase Auth `onDelete`) removes the user's profile,
  username, dances, likes, comments, groups they own, memberships, posts,
  replies, invitations, leaderboard entry and all of their Storage folders.
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

## Release signing

Release builds are signed with your **upload key** when it is configured;
otherwise the release APK is unsigned and can't be installed. The key is never
committed (`*.jks`, `*.keystore` and `keystore.properties` are gitignored).

1. Create the upload key once and keep it, and its passwords, somewhere safe
   (a password manager). If you lose it, you have to ask Google to reset the
   upload key.
   ```
   keytool -genkeypair -v -keystore chorevo-upload-key.jks -alias upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Local builds:** copy `keystore.properties.example` to
   `keystore.properties` and fill it in. `./gradlew assembleRelease` then
   produces a signed, installable APK, and `./gradlew bundleRelease` produces
   the App Bundle for Play.
3. **CI:** add these repository secrets (GitHub → Settings → Secrets and
   variables → Actions):
   - `RELEASE_KEYSTORE_BASE64`: the output of `base64 -w0 chorevo-upload-key.jks`
     (on macOS, `base64 -i chorevo-upload-key.jks`)
   - `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`upload`) and
     `RELEASE_KEY_PASSWORD`

   With these set, the Android CI build job signs the release APK and also
   uploads a signed `chorevo-release-aab` for Play. Secrets aren't available
   to pull requests from forks, so those builds stay unsigned.
4. In Play Console, turn on **Play App Signing**. Google then re-signs the app
   for users with the app signing key, and your key stays the upload key. Add
   the SHA-1/SHA-256 of **both** keys to Firebase and to `assetlinks.json`
   (step 2 below). Get your upload key's fingerprints with
   `keytool -list -v -keystore chorevo-upload-key.jks -alias upload`.

## Before releasing on Google Play (console actions)

1. **Application ID:** `com.example.chorevo` is rejected by Play.
   - Choose the final ID in `app/build.gradle.kts` (`applicationId`).
   - Register it as a new Android app in Firebase and replace
     `app/google-services.json`.
   - Update `public/.well-known/assetlinks.json` and `ANDROID_PACKAGE` in
     `public/js/*.js`.
2. **SHA fingerprints:** add the SHA-1 and SHA-256 of the upload key and the
   Play App Signing key to the Firebase Android app (Google sign-in and App
   Check need them) and to `assetlinks.json` (App Links).
3. **App Check:**
   - Link Play Console to the Firebase project and register the app for
     Play Integrity.
   - Register your debug token for development builds.
   - Watch the App Check metrics, then **enforce** App Check on Firestore,
     Storage and Authentication.
4. **API key:** in Google Cloud Console → Credentials, restrict the Android key
   to your package and SHA-1. Allow only the Firebase APIs the app uses.
5. **Firebase Authentication:**
   - Set the password policy to at least 8 characters.
   - Keep email enumeration protection on.
6. **Budget alerts:** add Cloud Billing budget alerts, and alerts on function
   errors and invocations.
7. **GitHub:**
   - Add required reviewers to the `production` environment.
   - Protect `main`.
   - Turn on secret scanning.
   - Prefer Workload Identity Federation over a JSON key for
     `FIREBASE_SERVICE_ACCOUNT`.
8. **Play Console:**
   - Publish a privacy policy URL.
   - Fill in the Data safety form. The app collects email, name, photos,
     videos, user-generated content, practice activity and device push
     tokens.
   - Give the account deletion URL:
     https://chorevo-dea05.web.app/delete-account.html.
   - Fill in that page's contact line first.
   - Declare the exact-alarm use (practice reminders).
9. **Test the release build:** install `assembleRelease` on a device and check
   that sign-in, the feed, practice, uploads, groups, invites and push all
   still work. Unit tests don't run R8.
10. **16 KB pages:** check that the native libraries (TensorFlow Lite,
    MediaPipe, Filament) support 16 KB page sizes, which Play requires for
    Android 15+ targets. Use the APK Analyzer or `zipalign -c -P 16`.

## Known limitations

- Download URLs of dance and group videos work for anyone who has the URL.
  Making a dance private hides it everywhere in the app, but doesn't revoke a
  URL someone already copied.
- Practice scores are computed on the phone. The server clamps them and
  rate-limits streaks, but a modified app could still submit good scores.
- Points, badges and weekly challenges are stored per user and visible only to
  that user, so changing them only affects the user themselves.
- A removed group member stays banned. There is no "unban" button yet.
