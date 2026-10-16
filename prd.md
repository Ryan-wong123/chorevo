# Chorevo Product Requirements Document

## 1. Product Summary

Chorevo is a native Android dance-learning app that helps learners practise dance routines with AI-assisted body pose and hand/finger feedback. The product combines a vertical dance video feed with likes, comments and sharing, creator uploads with public/private visibility, multi-dancer reference analysis, live camera practice with an instructor avatar, post-session analytics, points, levels, badges, streaks, weekly challenges, community groups, reminders, profiles, and leaderboards.

The current implementation is a single-module Kotlin Android app using Jetpack Compose (Material 3), Hilt, Firebase Authentication (email/password and Google via Credential Manager), Firestore, Firebase Storage, Firebase App Check (debug provider), CameraX, Media3 (ExoPlayer and Transformer), Room, WorkManager, DataStore, Coil, MediaPipe Tasks Vision (Pose and Holistic Landmarker), TensorFlow Lite (YOLO person detector) and SceneView (3D instructor avatar). Shareable dance links are served by Firebase Hosting and a Cloud Function (`functions/`).

## 2. Product Goals

- Let learners discover dance routines in a vertical video feed and start practice quickly.
- Let creators upload reference dance videos, choose whether each is public or private, and manage their own uploads.
- Generate pose (and, where possible, hand) templates from uploaded reference videos, including group videos with several dancers.
- Compare learner pose and hand landmarks against reference templates during practice.
- Save completed practice sessions locally first, then sync them to Firebase.
- Present understandable analytics that help users improve.
- Motivate repeated practice through points, levels, badges, streaks, weekly challenges, reminders, and leaderboards.
- Let users interact through likes, comments, community groups, and sharing to other apps.
- Keep user-owned data protected with Firebase Auth, Firestore rules, and Storage rules.

## 3. Target Users

There is a single user role in the app today: every account is stored with `role = "USER"` and can act as both learner and creator.

### Learners

Learners browse dances, like and comment on them, share them, select a routine, practise with camera guidance, submit results, review analytics, earn points and badges, complete weekly challenges, maintain streaks, join groups and post their dances in them, and compare progress on leaderboards.

### Creators / Educators

Creators upload dance demo videos, analyse and confirm detected dancer tracks, choose public or private visibility, manage their own uploads, and view learner analytics for sessions linked to their uploads.

## 4. Current Feature Scope

### Navigation and Design

- A floating, translucent pill bottom bar with five destinations: Home, Score (leaderboard), a raised gradient "+" Upload button, Groups (community), and Profile.
- The bar is hidden on the sign-in and practice screens and slides away while the user swipes between feed videos.
- Settings and Weekly Challenges are sub-screens of Profile.
- System back (button or gesture) goes to the parent screen: Practice, Upload, Groups, Score and Profile go back to Home; Settings and Weekly Challenges go back to Profile; on Home or sign-in it leaves the app. Back also closes an open group post's replies, then the open group, the liked-videos player, and steps back through the upload steps. The predictive back gesture is supported (Android 13+): while dragging, the screen shrinks with rounded corners to preview leaving.
- Dark mode is the default. Settings has an Appearance choice of Dark, Light, or System (follow the phone); status and navigation bar icons follow the chosen theme.
- App icon: an adaptive icon with a white dancing pose-skeleton figure on the brand gradient (teal → violet → magenta), with a monochrome layer for themed icons on Android 13+.
- The Chorevo design system: a fixed brand palette (teal primary, magenta accent) in dark and light variants that follow the system setting (dynamic colour is off), Sora and Plus Jakarta Sans fonts, and rounded shapes.
- Motion and feedback: screens fade through when switching tabs and practice slides up over the feed; lists and grids show shimmering placeholders while loading instead of "Loading…" text or spinners; empty lists show an animated empty state (floating icon, title, hint); the phone gives a light haptic tick on likes and a stronger confirm on practice combos, the results grade reveal, and a finished publish. Haptics follow the system touch-feedback setting.

### Authentication and Profile

- First launch (signed out) shows a three-page welcome carousel over a dark "stage" background with a looping animated pose-skeleton dancer: "Learn any routine", "Live AI feedback", "Level up every week". Skip or "Get started" goes to sign-in, and the carousel is not shown again on that device.
- The sign-in screen uses the same stage background and dancer, a gradient "Chorevo" wordmark and tagline, and a card with icon text fields, a gradient Login / Create Account button (with a spinner while signing in), a Google button, inline error messages, and a "Sign up here" / "Login here" switch. It scrolls above the keyboard.
- Email/password sign up and sign in through Firebase Auth.
- "Continue with Google" sign-in: a Google ID token from Credential Manager is exchanged for a Firebase credential.
- Every sign-in and session restore merge-writes the profile to `users/{uid}` and mirrors display name and photo into `leaderboardStats/{uid}`.
- The current user is restored on app startup before authenticated routes are shown.
- Profile screen: name, settings and logout in the header; the profile photo inside a gradient streak ring (with a flame badge showing days practised this week); a stats row (Uploads, Liked, Streak days this week, Points); the weekly streak card, achievements level panel, and weekly challenges summary card; and icon tabs for Uploads (public uploads as a thumbnail grid), Private (private uploads, with lock badges), Liked, Activity (own analytics), and Learners (learners practising my uploads). Tapping an upload opens an action sheet: Play, Edit details, Make public/private, Delete.
- The Liked tab is a three-column grid; tapping a video opens a full-screen vertical feed with like/unlike and Share. Private dances are hidden unless they belong to the viewer.
- Settings (gear on Profile): profile header with name, email and photo editing (gallery upload to `profilePhotos/{uid}/profile.jpg`), an Appearance choice (Dark, Light, System; default Dark), a Notifications toggle, a reminder time picker, and Sign Out.

### Dance Library (Home Feed)

- Firestore-backed library from the `danceLibrary` collection.
- Only enabled, public dances with ready pose templates are shown in the feed. Private dances appear only in their uploader's profile.
- Full-screen vertical video feed with the next video preloaded.
- A discovery bar floats over the top of the feed:
  - **Tabs:** For You (a shuffled order that starts at a random point each app session), Trending (ranked by likes with an age decay, (likes + 1) / (age in days + 2)^1.5, so new dances can trend), and New (newest uploads first).
  - **Search:** the search button swaps the tabs for a search field; results load 300 ms after typing pauses, best matches first (up to about 100 candidates, not paged). Each word may start a word ("sal" finds "Salsa"), appear inside one, or be a near miss: one typo for words of 4–6 letters and two for longer words ("slasa", "hiphp grove" still match). Every word must match the title, dance type, creator, or description.
  - **Category chips:** "All" plus one chip per dance type, most common first. Spellings are grouped ("Hip Hop", "hip-hop" and "HIPHOP" are one chip) and labelled with the most common spelling. Tapping the active chip again clears it.
  - Chips and search narrow whichever tab is selected, and changing any of them starts the feed from its first video. If nothing matches, an empty state with "Clear filters" is shown and the bar stays in place.
  - A dance opened from a share link is pinned first only on the unfiltered For You feed.
- The feed loads 12 dances at a time and fetches the next page three videos before the end, so it scales to a large library. Ranking, search tokens, category counts and like counts are kept on the server by Cloud Functions (see Web below).
- Dance metadata includes title, description, dance type, educator UID/name, video URL, template status, dancer count, review status, and visibility.
- Each video has a right-hand rail (creator avatar with a gradient ring; like with count, also by double-tap with a heart animation; comments with count; Share) and, bottom-left, the creator, title, a two-line caption that expands on tap, dance-type and dancer-count chips, and a gradient Practice button. A thin progress bar above the bottom bar shows playback and can be tapped or dragged to seek.
- Comments open in a bottom sheet: newest first, 20 per page with "Load more", and an input to post a comment.
- Start Practice prepares the dance by loading its pose template from Firebase Storage, then opens practice. Opening a dance (including from a share link) shows its video first; practice only starts when the user taps Start Practice.

### Sharing

- Each dance in the home feed and the profile liked-videos feed has a Share button.
- Share opens an in-app share sheet (TikTok/Instagram style) with:
  - social apps: WhatsApp, Instagram, Facebook, Messenger, Telegram, X, Snapchat, LINE. Installed apps show their real icons. WhatsApp, Facebook, Telegram and X fall back to their web share pages when not installed; the others are hidden when not installed.
  - utilities: Copy link, SMS, Email, and More (the Android system share sheet).
- Share links use the format `https://chorevo-dea05.web.app/dance/{danceId}`. The app also accepts `https://chorevo-dea05.firebaseapp.com/dance/{danceId}`, legacy `https://chorevo.app/dance/{danceId}` links, and the custom scheme `chorevo://dance/{danceId}` used by the web page to open the app.
- Opening a link in the app shows the home feed with the shared dance's video pinned first; the rest of the feed follows. Links opened while signed out are kept in memory and opened after sign-in.
- Opening a link without the app shows a web landing page with the dance title, educator, description and video, plus Open in Chorevo and Copy link buttons. On Android the page tries to open the app automatically.
- Link previews in chat/social apps show the dance's own title and description (Open Graph/Twitter tags rendered server-side).

### Creator Uploads

- Uploading is a five-step flow with a segmented step indicator ("Step N of 5") and Back/Next buttons; steps slide in the direction of travel:
  1. **Video**: pick a gallery video or record one with the camera; a large preview of the chosen video is shown.
  2. **Cover**: choose the cover frame with a slider over the video or one of five quick-pick frames; the chosen frame is stored as `coverFrameMs` (default 1 s) and used for the thumbnail in profile and liked grids.
  3. **Details**: title, dance type, and description (all required to continue). Dance type is chosen from a list of standard styles (Hip Hop, K-Pop, Ballet, Contemporary, Jazz, Salsa, …) or "Other…" for a custom style; a custom style that is a near miss of a standard one ("Hiphp") offers the standard spelling ("Did you mean Hip Hop?"). The Edit dialog uses the same picker.
  4. **Dancers**: "Analyze Dancers" runs on-device multi-dancer analysis (see section 13) and shows a dancer review panel; the creator selects which dancers to keep and the default dancer, then confirms. Alternatively they tick "Skip review and analyze in the background after upload".
  5. **Publish**: a summary card (cover, title, type, dancers, visibility), the Public/Private choice (default Public), upload progress, and the Publish button. A confirmed review uploads with that template; otherwise the template is generated later with WorkManager.
- After a successful publish the draft clears and the flow returns to step 1.
- Videos are compressed (720p, up to 24 fps) and the smaller of the original and compressed file is uploaded to `danceVideos/{uid}/{danceId}.mp4`.
- Dance metadata is written to `danceLibrary/{danceId}`.
- Users can list, preview, edit (title, dance type, description, visibility), and delete their own uploads.
- Visibility can be changed later from the profile's uploaded videos list (one-tap Make private / Make public, or in the Edit dialog).
- Uploads created before visibility existed have no `visibility` field and are treated as public.

### Pose Template Generation

- MediaPipe extracts landmarks from sampled video frames (up to 120 seconds of video).
- Two generation profiles:
  - **High-recall** (the "Analyze Dancers" button, on device, 8 fps): YOLO person detection (`person_detector_yolo.tflite`), ByteTrack tracking, low-light preprocessing, per-dancer crops through `holistic_landmarker.task` for pose + left/right hands, track fragment merging and short-gap interpolation. `pose_landmarker_full.task` is the body-only fallback (or `pose_landmarker_lite.task` if the full model is missing).
  - **Standard** (background WorkManager job): full-frame Holistic Landmarker for one dancer's pose + hands, with `pose_landmarker_lite.task` for body pose and as the fallback. No YOLO, preprocessing, or track cleanup.
- Face output is discarded and never stored.
- Generated templates are serialized to JSON and stored under `danceTemplates/{uid}/{danceId}.json` (templates created before this change remain at `danceTemplates/{danceId}.json`).
- The background worker retries failed generation up to three attempts, then marks the template `failed`.

### AR Practice and Scoring

- CameraX provides live camera preview behind a camera-permission gate.
- A 3-second "Get Ready" countdown (a large gradient circle; each number pops in) starts each run.
- Live landmarks come from Holistic Landmarker (body pose plus left/right hand landmarks), falling back to Pose Landmarker lite (body only) if Holistic is unavailable or fails. Face landmarks are never stored, rendered, or scored.
- Practice modes: Full Routine, or Step-by-Step with per-segment loop preview and Previous/Next.
- Overlays: a 3D instructor avatar driven by the mirrored reference landmarks, and the learner's body skeleton coloured by live accuracy (green good, amber OK, red needs work).
- Live HUD: a circular score ring (top-right) showing the live score, and a "COMBO" badge counting seconds of continuous good matching once it reaches 3 seconds. The controls panel is frosted glass with Align/Timing/Smooth (and Hands) stat pills, and fades to half opacity while dancing.
- `PoseSimilarityEvaluator` compares stable body joints against the selected reference track and produces the body score.
- When the template has hand tracks, `HandSimilarityEvaluator` scores hands and the screen shows an "Overall" score (85% body, 15% hands), a "Hands" score, and hand feedback.
- Saved session scores are body-only: alignment, timing consistency, smoothness, plus average pose error, timeline points, performance segments, and feedback messages (including hand feedback). `balanceScore` is a fixed placeholder (1.0). The quality flag is `GOOD` when alignment is at least 0.75, otherwise `DONE`.
- Submitting writes a Room `DanceSessionEntity`, attempts Firestore sync, awards points and badges, updates weekly challenges, and opens a full-screen results screen: an animated score count-up in a gradient ring, a grade badge (S ≥ 90, A ≥ 80, B ≥ 65, otherwise C), Alignment/Timing/Smooth scores, points, level progress and new badges, confetti on a personal best (best alignment for that dance on this device), and "Share my result", which shares a generated result-card image with the dance link.

### Analytics

- Learners can review their latest 60 sessions from `users/{uid}/danceLearningSessions` (profile Activity tab).
- Above the session list (Activity and Learners tabs) an overview shows: number tiles (average score with a sparkline and the change between the older and newer half of sessions, best score, sessions with active days this week, total practice time); a score trend chart of alignment, smoothness and timing over the last 12 sessions; an activity heatmap of sessions per day for the last 8 weeks; and a "What to work on" body diagram that colours arms, legs, posture and hands from green to red by how often live feedback corrected them, with the top areas listed.
- Creators can view collection-group analytics for sessions where `analysis.educatorUid` matches their UID, optionally filtered by dance (profile "Other Dance Analytics" tab), with learner names and the reference video.
- Analytics detail includes summary metrics, reference video playback, a timeline chart, and performance segments.

### Points, Levels and Badges

- Each submitted session awards up to 100 points: alignment × 50 + timing × 30 + smoothness × 20. Totals are stored in `users/{uid}/points/current`.
- Five levels: Beginner (0), Mover (200), Dancer (500), Performer (1000), Choreographer (2000).
- Ten badges, stored in `users/{uid}/achievements/current`: First Steps, Perfect Pose, Smooth Operator, In the Groove, High Scorer, Century, Dedicated, Elite, Streak Starter, Week Warrior. New badges appear in the post-session dialog; the profile shows level and points rather than the badge list.

### Streaks and Weekly Challenges

- A weekly streak (Monday start) is stored in `users/{uid}/streaks/current` and shown on the profile.
- Each week three personalised challenges are generated and stored at `users/{uid}/weeklyChallenges/{weekStart}`:
  - Focus Drill: two sessions above a threshold on the learner's weakest score (40 points).
  - Style Explorer: practise two dance types that exist in the library (30 points).
  - Momentum Goal: a session count scaled from recent weeks, between 2 and 7 (30 points).
  - Completing all three adds a 50-point bonus.
- Completed practice sessions update challenge progress, rewards go into the points total, and a celebration dialog shows completions. The full list opens from the profile summary card ("View all").

### Community Groups

- The Groups tab has two lists: "My groups" (groups the user belongs to, updating live) and "Discover" (public groups, most members first, with a name search by prefix).
- Anyone can create a group (name 3–60 characters, description up to 500) and choose Public or Private. The creator becomes the owner.
- Public groups are listed in Discover; anyone can open one, read its posts, and join with one tap.
- Private groups are not listed and cannot be read by non-members. Each has an 8-character invite code (letters and digits without look-alikes such as 0/O and 1/I/L). Members see the code in the group header and can copy it, share an invite link, or show it as a QR code. Others join by:
  - scanning the QR code in the app (scan icon on the Groups screen, or "Scan QR code instead" in the join dialog), which joins straight away;
  - scanning it with the phone camera, or opening the shared link (`https://chorevo-dea05.web.app/join/<code>`): the app opens on Groups with the code filled in, one tap to join (signed-out users sign in first). Without the app, the link shows a web page with the code;
  - entering the code with the key icon.
  Scanning the code of a group the user is already in just opens it.
- Members post in a group. A post has text (up to 1,500 characters) and optionally one attachment:
  - one of the member's own public, ready dances from the feed (shown with its cover; "Open dance" opens it in the Home feed), or
  - a new video just for the group, picked from the gallery or recorded (compressed to 720p when larger, under 200 MB, with a cover frame); it plays in a full-screen player.
- Posts show newest first (up to 100). Members reply to posts (up to 500 characters) in a bottom sheet; non-members of a public group can read replies but not write.
- Posts and replies cannot be edited. Authors can delete their own posts; the owner can delete any post.
- Members can leave a group. The owner can see the member list, remove members and delete the group; deleting removes its posts, replies, invite and group videos. The owner cannot leave without deleting.
- The member count is kept by a Cloud Function, so it can lag a join by a second or two.
- The old forum (`communityPosts`) is no longer shown; its data is kept.

### Leaderboard

- Global and Friends scopes, ranked by Sessions (total practice sessions), Similarity (average alignment score), or Active Days. Tied users share a rank.
- The top three stand on a podium (2nd left, 1st raised in the middle with a trophy, 3rd right) with gold, silver and bronze avatar rings, their metric value, and blocks that rise in on load. Everyone from 4th down is listed below with their rank. The user's own rank is pinned in a "Your position" card at the bottom of the screen; on the Global board it shows the real rank even outside the top 100 (one count of users with a higher score).
- Users add friends (+) from the Global board (podium or list) and remove them from the Friends board. The Friends view is the current user plus saved friends, read directly by user ID (so friends outside the global top 100 are included); the metric.
- Stats in `leaderboardStats/{uid}` are recomputed on the client after each synced session.

### Reminders

- Reminders are off by default. Users enable them in Settings (this requests the notification permission) and choose a time (default 09:00).
- Preferences are stored in DataStore and scheduled with Android alarms (exact when permitted). Reminders are rescheduled after reboot or app update.

### Offline and Retry Behavior

- Dance sessions are inserted into Room before upload.
- Unsynced sessions are identified through the `synced` flag.
- Each session records the account that saved it. When a session is restored at app launch, and after every sign-in, that account's unsynced sessions are retried (upload, streak update, leaderboard refresh, mark synced). Sessions saved before ownership was recorded are retried by whichever account signs in.
- Failed submissions preserve local session data and report the upload failure to the UI.

## 5. Out of Scope for the Current App

- Web or iOS clients (the web share page only previews a dance).
- Payments, subscriptions, or creator monetization.
- Admin/moderator dashboards and content reporting.
- Server-side trusted score recomputation.
- Direct messaging, and editing posts, replies or comments.
- Full choreography music beat alignment.
- Multi-camera capture.
- Raw learner video upload for practice sessions.
- Face tracking, face mesh scoring, facial expression scoring, and face landmark storage.

## 6. Functional Requirements

### Account

- Users must be able to sign up, sign in (email/password or Google), sign out, and restore an existing authenticated session.
- All protected data writes must be associated with the authenticated Firebase UID.
- Profile updates must affect only the signed-in user's document.

### Dance Discovery

- The app must fetch enabled library dances from Firestore and exclude private dances from the feed.
- Dances without a ready template must not be presented as practice-ready.
- Selecting Start Practice must load the dance's pose template before entering practice.
- Template loading failures must surface a clear user-facing message.
- Users must be able to like and unlike dances and read and post comments.
- Users must be able to switch the feed between For You, Trending and New, search it, and filter it by dance type.

### Upload Management

- A signed-in creator must be able to upload a video with title, dance type, description, and visibility.
- The app must write upload metadata and media storage paths consistently.
- The app must track template generation states: queued, processing, ready, failed, skipped, or saving.
- A creator must be able to review and confirm detected dancers before publishing, or choose background analysis instead.
- A creator must be able to choose the cover frame shown on the video's thumbnail.
- A creator must be able to update title, dance type, description, and visibility for their own uploads.
- A creator must be able to delete their own upload metadata, video object, and template object.
- A private dance must only be visible to its uploader: hidden from the feed, from other users' liked videos, from share links opened by other users, and from the web share page.

### Sharing

- Users must be able to share a dance through the in-app share sheet to installed social apps, SMS, email, the clipboard, or the system share sheet.
- Share links must open the shared dance's video (not the practice screen) in the app.
- Share links opened before sign-in must be kept and opened once the user signs in.
- The web share page must render the dance's own title, description and video for public dances, and generic text for missing, disabled or private dances.
- Invalid, deleted, disabled, private, or not-yet-processed links must show a clear message in the app instead of failing silently.

### Practice Session

- A learner must be able to practise against a selected dance using the camera, in Full Routine or Step-by-Step mode.
- The app must compute score snapshots during practice.
- The app must use `app/src/main/assets/holistic_landmarker.task` for live pose + hands when available, and fall back to body-only Pose Landmarker.
- The app must continue to support existing pose-only templates and body-only scoring.
- The app must not penalize the hand score when the reference template has no hand/finger data.
- On submission, the app must create a session entity with timestamps, duration, scores, quality flag, and analysis JSON.
- The app must save locally before attempting cloud upload.
- Successful cloud upload must mark the Room session as synced.

### Analytics

- A learner must be able to see recent own sessions and session details.
- A learner must be able to see overall progress (average and best score, trend, activity, and body areas to improve) without opening each session.
- A creator must be able to see learner sessions linked to their uploaded dances.
- Analytics must parse missing or partially populated analysis data safely.
- Timeline and segment data must be sorted before display.

### Engagement

- Completing a practice session must award points and badges and update weekly challenge progress.
- A successful session sync must update streak and leaderboard stats.
- Users must be able to view leaderboard rankings by metric.
- The Friends leaderboard must include the current user plus saved friend IDs.
- Users must be able to create, discover and join groups (public, or private by invite code), and post text, their dances or group-only videos and replies inside them.
- Users must be able to enable/disable reminders and set the reminder time.

## 7. Non-Functional Requirements

### Performance

- Camera preview and pose evaluation should remain responsive on supported devices.
- Uploads should compress videos to reduce bandwidth when beneficial.
- Pose templates should be stored outside the Firestore document when large.
- Firestore listeners should be scoped to the active screen and removed when collection flows close.

### Reliability

- Practice session data must not be lost when Firebase upload fails.
- WorkManager template generation should retry transient failures.
- Upload and generation status must remain visible through Firestore fields.

### Security and Privacy

- Firebase Authentication is required for Firestore reads/writes except public Storage reads for uploaded dance videos and thumbnails.
- Firestore rules must enforce owner-only writes for profiles, points, achievements, streaks, weekly challenges, friends, sessions, and uploads.
- Learner session reads must be limited to the owner or the educator linked in the analysis payload.
- Any signed-in user can read any `users/{uid}` profile document, including its email.
- Storage rules must enforce owner-only writes and content-type/size limits, including for pose templates (`danceTemplates/{uid}/…`).
- App Check debug provider is installed only for debug builds.
- Video privacy is enforced by Firestore rules: a private dance, its likes and its comments can only be read by its uploader, and feed queries must filter on `visibility == "public"`. The app and the share Cloud Function also check it.
- Server-managed dance fields (`likeCount`, `trendingScore`, `feedReady`, `danceTypeKey`, `searchTokens`, `shuffleKey`) can only be written by Cloud Functions.
- The share Cloud Function reads Firestore with the Admin SDK and must never expose private or disabled dances.

### Maintainability

- Feature code should remain grouped by package: `analytics`, `community`, `danceactivity`, `dancelibrarylist`, `firebase`, `leaderboard`, `notification`, `posedetection`, `videouploads`, `weeklychallenge`, `core`, and `ui/theme`.
- UI state should be represented with immutable data classes and exposed through read-only `StateFlow`.
- UI colours, fonts and shapes should come from the Chorevo theme rather than hardcoded values.
- Firebase, Room, and Storage access should stay inside repositories or focused infrastructure classes.
- Long-running or IO-heavy work should run in coroutines, WorkManager, or injected dispatchers.

### Testability

- Repository and ViewModel logic should be unit tested with MockK, coroutine test utilities, and Turbine where useful.
- Room DAO behavior and key Compose flows should be covered by instrumentation tests.
- CI should run JVM unit tests, Android instrumentation tests, and assemble debug/release builds.
- Cloud Function tests (`functions/`) and Firestore rules tests (`rules-tests/`) run in CI (`firebase.yml`) on every push; merges to `main` deploy Firebase automatically once the `FIREBASE_SERVICE_ACCOUNT` secret is set.

## 8. Key Data Objects

### Firestore Collections

- `users/{uid}`: uid, email, role (`USER`), displayName, photoUrl, lastSeen.
- `users/{uid}/danceLearningSessions/{sessionId}`: practice session summary (sessionId, timestamps, activity name/type, duration, alignment/balance/timing/smoothness scores, qualityFlag) and a nested `analysis` map (danceId, educatorUid, scores, average pose error, video URL and position, reference track, feedback messages, timeline points, performance segments).
- `users/{uid}/friends/{friendUid}`: uid, displayName, photoUrl, addedAt.
- `users/{uid}/points/current`: totalPoints, lastSessionPoints, lastUpdated.
- `users/{uid}/achievements/current`: earnedBadges, lastUpdated.
- `users/{uid}/streaks/current`: weekStart, daysMask (Mon–Sun bits), totalStreakActivities, lastActivityDate. (The rules also allow a legacy `streak/` path that no code uses.)
- `users/{uid}/weeklyChallenges/{weekStart}`: the week's three challenges, progress, and rewards.
- `danceLibrary/{danceId}`: creator-uploaded dance metadata, `visibility` (`public` or `private`; missing means public), `coverFrameMs` (cover thumbnail position in ms; missing means 1000), template state (`templateGenerationStatus`, `templateGenerationError`, `templateStoragePath`, `templateReviewStatus`, `dancerCount`), and upload details (original/compressed/uploaded sizes, `wasCompressed`, `storagePath`).
- `danceLibrary/{danceId}/likes/{uid}`: `{liked: true}`.
- `danceLibrary/{danceId}/comments/{commentId}`: commentId, uid, username, text, createdAt.
- `groups/{groupId}`: id, name, nameLower, description, visibility (`public` or `private`), ownerUid, ownerName, inviteCode (private only), createdAt, memberCount (written by a Cloud Function).
- `groups/{groupId}/members/{uid}`: uid, displayName, photoUrl, role (`owner` or `member`), joinedAt, inviteCode (when joined by code).
- `groups/{groupId}/posts/{postId}`: id, uid, displayName, photoUrl, body, kind (`text`, `dance` or `video`), danceId, danceTitle, videoUrl, thumbnailUrl, storagePath, thumbnailPath, createdAt.
- `groups/{groupId}/posts/{postId}/replies/{replyId}`: id, postId, uid, displayName, photoUrl, body, createdAt.
- `groupInvites/{code}`: groupId, createdAt (looks up a private group by its invite code).
- `communityPosts/{postId}` and its `replies`: the old forum; kept but no longer shown.
- `leaderboardStats/{uid}`: uid, displayName, photoUrl, totalPracticeSessions, avgSimilarityScore, totalActiveDays, streakDays (legacy), updatedAt.

### Storage Paths

- `danceVideos/{uid}/{danceId}.mp4`: uploaded reference videos (video, under 200 MB).
- `danceThumbnails/{uid}/{danceId}.jpg`: cover thumbnails (image, under 10 MB).
- `groupVideos/{uid}/{postId}.mp4`: group-only videos (video, under 200 MB), readable by signed-in users through their download URL, which only group members can see.
- `groupThumbnails/{uid}/{postId}.jpg`: covers for group videos (image, under 10 MB).
- `danceTemplates/{uid}/{danceId}.json`: generated pose templates (JSON, under 20 MB), writable only by the uploader. Older templates at `danceTemplates/{danceId}.json` are read-only.
- `profilePhotos/{uid}/profile.jpg`: profile pictures (image, under 10 MB).

### Room

- Database `app.db` (version 6; the 5→6 migration adds `ownerUid`, other schema changes fall back to destructive migration) with one table, `dance_sessions`: local practice session records (sessionId, timestamps, activity, duration, scores, qualityFlag, analysisJson) with a `synced` flag and the `ownerUid` of the account that saved it.

### DataStore

- `notification_preferences`: `push_enabled`, `notification_hour` (default 9), `notification_minute` (default 0), `theme_mode` (`dark`, `light` or `system`; missing means dark), `onboarding_seen` (welcome carousel shown).

### Web (Firebase Hosting + Cloud Functions)

- `/dance/{danceId}` on `chorevo-dea05.web.app`: rewritten to the `danceShare` Cloud Function (us-central1, Node.js 22), which renders the share landing page.
- Link previews show an image: the dance's cover-frame thumbnail (`og:image`, large card on X/Twitter, video poster on the page) or, for older uploads without one and for unavailable dances, the branded `og-default.png` (1200×630).
- `/.well-known/assetlinks.json`: served from `public/` (UTF-8, `application/json`) so Android can verify App Links. It lists the SHA-256 fingerprints of the signing certificates allowed to open links; add one for each key that signs a build people install (each developer's debug key, the release key).
- `danceDerivedFields` (Firestore trigger on `danceLibrary/{id}`): keeps `feedReady`, `danceTypeKey`, `searchTokens`, `shuffleKey` and default `visibility`/`likeCount`/`trendingScore` in step with the dance, and the category chip counts in `meta/danceCategories`.
- `danceLikeCount` (trigger on `danceLibrary/{id}/likes/{uid}`): recounts likes and refreshes the trending score.
- `refreshDiscovery` (every 6 hours): ages trending scores and rebuilds the category counts.
- `functions/scripts/backfill.js`: one-off backfill of those fields, exact like counts and like `uid`s for existing data.

## 9. Success Metrics

These are product targets. They are not instrumented yet: the Firebase Analytics SDK is included but no events are logged.

- Sign-up and sign-in success rate.
- Number of uploaded dances with ready templates.
- Template generation success/failure rate.
- Library-to-practice conversion rate.
- Practice completion and submission rate.
- Percentage of sessions successfully synced.
- Analytics view rate after session completion.
- Weekly active learners, streak retention, and weekly challenge completion.
- Leaderboard and community participation.
- Shares per dance and share-link opens (app and web).
- Share of uploads set to private.

## 10. Release Acceptance Criteria

- `testDebugUnitTest` passes.
- `connectedDebugAndroidTest` passes on a configured emulator.
- `assembleDebug` and `assembleRelease` complete in CI.
- Firebase rules and indexes are deployed for the target project, and the repo's `firestore.rules` matches the deployed ruleset.
- A signed-in user can upload a dance, generate or save a template, see it in the library, practise it, submit results, and view analytics.
- A failed session upload leaves a local unsynced record that can be retried.
- Unauthorized users cannot write or delete data owned by another UID.
- `functions` tests (`npm test` in `functions/`) pass and the Cloud Function and Hosting are deployed.
- A shared link opens the dance video in the app, and shows the dance's title and video on the web when the app is not installed.
- A private upload does not appear in the feed, in other users' liked videos, or through share links opened by other users.

## 11. Roadmap Candidates and Known Issues

### Roadmap Candidates

- Better search, filtering, and favorites in the dance library.
- More explicit role handling for learners, creators, and future admins.
- Server-side validation or recomputation for official leaderboard scores.
- Moderation/reporting workflows for comments and community posts.
- Richer body-part feedback, finger-specific coaching, and a hand overlay in practice.
- Better migration strategy for Room schema changes.
- More robust offline sync queue visibility in the UI.
- Analytics event instrumentation for the success metrics.

### Known Issues

- App builds from before the template-ownership change write templates to the now read-only `danceTemplates/{danceId}.json` path, so their uploads fail to save templates. Everyone who uploads needs an app build that includes this change.

## 12. Pose + Hands Holistic Practice (Implemented)

This upgrade adds hand/finger awareness to live dance practice while keeping the existing body-pose scoring and template compatibility. It is implemented.

### 12.1 Problem Statement

A body-only live practice path evaluates major joints such as shoulders, elbows, wrists, hips, knees, and ankles. It cannot tell whether a learner's fingers are open, pointing, curled, or matching a required hand gesture. For routines where hand shape matters, wrist-only tracking can give a high score even when the finger gesture is wrong.

### 12.2 Product Goals

- Keep the body-pose score as the main score. (Met: saved scores are body-only.)
- Add optional hand/finger landmarks for left and right hands. (Met: 21 landmarks per hand.)
- Use the bundled model asset `app/src/main/assets/holistic_landmarker.task` (about 13.7 MB). (Met.)
- Ignore face output completely. (Met: MediaPipe still computes face landmarks internally, but the app drops them.)
- Keep old pose-only templates valid and practice-ready. (Met.)
- Add hand feedback only when the reference template contains hand data. (Met.)

### 12.3 Functional Requirements

- Live practice uses `HolisticLandmarkerHelper` when `holistic_landmarker.task` is available and falls back to `PoseLandmarkerHelper` (lite) if Holistic fails to start or throws.
- The helper returns an app-owned model containing only `pose`, `leftHand`, and `rightHand` flattened landmarks, and ignores face landmarks, blendshapes, and transformation matrices.
- Body pose data remains compatible with `PoseSimilarityEvaluator`.
- Templates store optional hand tracks in the backward-compatible `handTracks` JSON field.
- `HandSimilarityEvaluator` computes a hand score only when both learner and reference hand landmarks are available.
- The saved practice score (`alignmentScore`) is body-only. When the template has hand tracks and hands are detected, the screen also shows "Overall" (85% body, 15% hands) and "Hands" scores. If the template has no hand data, or the learner's hand is not detected, no hand penalty is applied.
- Hand feedback messages are "Keep hands visible.", "Left hand not detected.", "Right hand not detected.", "Match the hand shape.", and "Good hand shape." They are shown during practice and saved with the session feedback. Finger-specific coaching (for example "Open your fingers more") is future work.

### 12.4 Non-Goals

- Face mesh scoring.
- Facial expression coaching.
- Storing face landmarks in local or cloud templates.
- Replacing `PoseSimilarityEvaluator`.
- Making YOLO or multi-dancer upload detection part of live camera practice.

### 12.5 Acceptance Criteria

- The app builds with `holistic_landmarker.task` in `app/src/main/assets`.
- Live practice still works with old pose-only templates.
- Body alignment, timing consistency, and smoothness scores still update.
- When a template has hand tracks, the UI shows hand/finger feedback.
- When a template has no hand tracks, no hand penalty is applied.
- No face landmarks are written to template JSON, Room, Firestore, logs, or analytics payloads.

## 13. Multi-Dancer AI (Implemented in High-Recall Analysis)

This extends MediaPipe template generation with multi-person detection and tracking for uploaded videos with several dancers, low lighting, partial occlusion, temporary out-of-frame movement, or overlapping dancers. It is implemented for the on-device "Analyze Dancers" step (the high-recall profile). The background analysis job (chosen with "Skip review and analyze in the background after upload") uses the standard profile and does not include these steps.

### 13.1 Problem Statement

Group videos can fail in these ways:

- A dancer is missed because they are small, dark, near the edge of the frame, or partially blocked.
- The same dancer is detected more than once because full-frame and crop-based passes overlap.
- The same dancer becomes multiple tracks after occlusion or a temporary confidence drop.
- Weak tracks are removed too aggressively even though they represent real dancers.
- Low-light frames reduce person detection and landmark confidence.

### 13.2 Product Goals

- Detect as many visible dancers as practical from uploaded reference videos (up to 8).
- Keep a stable identity for each dancer across frames.
- Preserve the MediaPipe 33-landmark template format.
- Require creator review/confirmation of detected dancers before publishing a confirmed template.
- Avoid inventing false data for hidden landmarks (interpolated landmarks get low visibility; there is no per-frame "inferred" flag).
- Keep YOLO and multi-dancer analysis out of live practice. Live practice uses single-person Holistic (pose + hands) and falls back to Pose Landmarker lite.

### 13.3 AI Stack

Current, for uploaded video analysis:

- YOLO person detector (`person_detector_yolo.tflite`, 640×640 input) running on TensorFlow Lite, with non-maximum suppression.
- ByteTrack-style multi-object tracking with high/low-confidence matching and lost-track recovery.
- MediaPipe Holistic Landmarker on padded per-dancer crops, using only pose and left/right hand outputs.
- MediaPipe Pose Landmarker `full` as the body-only fallback in high-recall analysis (`lite` if `full` is missing).
- Custom bitmap preprocessing for brightness, contrast, and gamma.

Current, for live camera practice:

- Holistic Landmarker (pose + hands), falling back to Pose Landmarker `lite`.

Future alternatives:

- ONNX Runtime Mobile or NCNN for YOLO.
- BoT-SORT or DeepSORT/Re-ID when dancer crossing and re-identification become common issues.
- Denoising and sharpening preprocessing.
- MoveNet Lightning/Thunder if live performance requires it.

### 13.4 Functional Requirements

- The upload analysis path supports a high-recall profile for multi-dancer videos (8 fps).
- High-recall analysis runs YOLO person detection before MediaPipe landmark extraction; YOLO is configurable and enabled automatically when hands are requested. If the detector model is missing or fails to load, analysis continues with a `personDetectorUnavailable` warning.
- For hand-enabled high-recall templates, the pipeline detects dancer boxes, tracks each dancer, crops each dancer with padding, and runs Holistic on each crop.
- The tracker assigns stable dancer IDs across frames.
- Pose and left/right hand landmarks are remapped from crop coordinates back to full-frame coordinates; face output is discarded.
- If Holistic initialization or crop inference fails, the pipeline falls back to body-only pose for that dancer rather than failing the whole upload.
- Duplicate same-frame detections are removed before tracks are updated.
- Fragmented tracks that likely belong to the same dancer are merged.
- Short gaps caused by occlusion, low confidence, or temporary out-of-frame movement are tolerated.
- The template format remains backward compatible with existing `PoseTemplate` and `PoseTemplateJson` consumers.
- The dancer review panel shows detected dancer count, preview thumbnails, include/exclude toggles, the default dancer, and creator-friendly warnings.

### 13.5 Low-Light Requirements

- High-recall analysis applies brightness/contrast/gamma preprocessing before detection by default.
- Preprocessing is configurable in code and easy to disable; there is no user-facing toggle.
- The app does not claim that preprocessing can recover dancers who are not visually present.
- Low quality is reported as template-level warnings (for example low light, low-confidence tracks, many missing frames), not per-frame flags.

### 13.6 Occlusion and Overlap Requirements

- Partially overlapping dancers keep separate IDs when visible evidence is sufficient.
- A fully hidden dancer's track is kept alive for a bounded time (three seconds of frames); missing frames are left out of the track rather than written as placeholders.
- Short missing sections (up to 3 frames or 500 ms) may be interpolated, with visibility capped at 0.20 to mark them as low confidence.
- The system does not fabricate high-confidence landmarks for fully hidden dancers.

### 13.7 Non-Goals

- Perfect detection of fully hidden dancers.
- Guaranteed identity recovery after long disappearances without re-identification support.
- Replacing all existing MediaPipe scoring and analytics code.
- Uploading learner practice videos to the cloud.
- A server-side AI backend.

### 13.8 Success Metrics (Evaluation Targets; Not Yet Instrumented)

Only `dancerCount` is recorded today. These targets need manual measurement or future telemetry:

- Higher average detected dancer count on known group videos.
- Lower duplicate-track count after post-processing.
- Fewer fragmented tracks for the same dancer.
- Higher template generation success rate for low-light uploads.
- Hand-enabled multi-dancer templates contain useful left/right hand references when hands are visible.
- The creator review screen shows useful warnings instead of silently failing.
- No regression to live camera practice responsiveness.

### 13.9 Known Limitations

- The standard background profile is effectively single-dancer when hands are requested.
- Live practice does not fall back to Pose Landmarker when Holistic returns an empty pose; it only falls back when Holistic fails or throws.
