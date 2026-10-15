# Chorevo Architecture

## 1. Overview

Chorevo is a single-module native Android application:

- Module: `:app`
- Package: `com.example.chorevo` (the router files in `core/routes` declare `package com.routes`)
- UI: Jetpack Compose and Material 3, with the Chorevo design system in `ui/theme`
- App architecture: MVVM-style presentation with repositories for data access
- Dependency injection: Hilt
- Local data: Room and DataStore
- Cloud data: Firebase Auth (email/password and Google via Credential Manager), Firestore, Firebase Storage, Firebase App Check (debug provider)
- Background work: WorkManager
- Camera/video: CameraX, Media3 ExoPlayer (feed and previews) and Media3 Transformer (upload compression), Coil (images)
- Pose analysis: MediaPipe Tasks Vision 0.10.26.1 (Pose Landmarker and Holistic Landmarker) and TensorFlow Lite 2.17.0 (YOLO person detector)
- 3D: SceneView for the instructor avatar overlay
- Web share links: Firebase Hosting (`chorevo-dea05.web.app`) rewriting `/dance/**` to the `danceShare` Cloud Function in `functions/` (Node.js 22, `firebase-functions` v2 HTTPS, Admin SDK)

Navigation Compose and Hilt Navigation Compose are declared as dependencies, but navigation uses a custom `Screen` enum rather than a `NavHost`. The Firebase Analytics SDK is included but logs no events.

The app starts in `Chorevo`, initializes Firebase/App Check, then `MainActivity` hosts the Compose tree and calls `AppRouter`.

## 2. Runtime Entry Points

### `Chorevo`

`Chorevo` is the `Application` class. It:

- enables Hilt through `@HiltAndroidApp`,
- initializes Firebase,
- installs the Firebase App Check debug provider when `BuildConfig.DEBUG` (release builds install no App Check provider; the `firebase-appcheck-debug` artifact is an `implementation` dependency),
- provides custom WorkManager configuration with a single-thread executor and `setMaxSchedulerLimit(20)`, and shuts the executor down in `onTerminate`.

The manifest removes `androidx.startup.InitializationProvider` (`tools:node="remove"`) so WorkManager uses this configuration.

### `MainActivity`

`MainActivity` is the launcher activity. It:

- enables edge-to-edge display,
- applies `ChorevoTheme`,
- hosts the root Compose `Surface`,
- creates `AppViewModel` with `by viewModels()` and passes it to `AppRouter` so both use the same instance,
- handles share-link intents in `onCreate` and `onNewIntent` (`launchMode="singleTask"`): only `ACTION_VIEW` intents with data are handled; `ShareLinks.parseDanceId` extracts the dance ID and passes it to `AppViewModel.openDanceById`.

Supported links (declared as intent filters in `AndroidManifest.xml`):

- `https://chorevo-dea05.web.app/dance/{danceId}` and `https://chorevo-dea05.firebaseapp.com/dance/{danceId}` (`autoVerify="true"`; App Links verification needs `assetlinks.json` to be served, see section 10),
- `https://chorevo.app/dance/{danceId}` (legacy links),
- `chorevo://dance/{danceId}` (custom scheme used by the web page to hand off into the app).

The manifest also declares a `<queries>` block so the share sheet can detect installed social apps and resolve send/SMS/email/browser intents on Android 11+.

### `AppRouter`

`AppRouter` (`core/routes/AppRouter.kt`) coordinates top-level routing from `AppViewModel` state:

- waits for auth restore and shows a loading route,
- routes unauthenticated users to auth,
- creates shared Hilt ViewModels for the dance library and uploads,
- wraps main content in `AppNavigationRoute` (floating bottom bar),
- switches with `when (screen)` over the `Screen` enum: `AUTH`, `HOME`, `DANCE_PRACTICE`, `UPLOAD`, `COMMUNITY`, `LEADERBOARD`, and the profile section (`PROFILE`, `SETTINGS`, `WEEKLY_CHALLENGES`, all rendered by `ProfileSectionRoute`),
- passes `AppUiState.sharedDance` to `HomeRoute` so a dance opened from a share link is pinned first in the feed,
- shows share-link errors (missing, disabled, private, not ready) as a snackbar.

## 3. Layered Structure

### Presentation Layer

Compose screens and routes live in feature packages. They observe ViewModel state and emit callbacks.

Screens:

- `AuthScreen`: email/password and "Continue with Google".
- `DanceLibraryListView`: home video feed with like, comments sheet, Share and Start Practice.
- `DanceARPracticeScreen`: camera practice with countdown, Full Routine / Step-by-Step modes, instructor avatar, learner skeleton, scores, and results dialogs.
- `VideoUploadActivityScreen`: upload form, video picker/recorder, dancer review panel, visibility.
- `AnalyticsDashboardView`: learner and creator analytics.
- `LeaderboardScreen`: Global/Friends leaderboard.
- `CommunityScreen`: forum posts and replies.
- `UserProfileScreen`: profile header, streak card, level panel, weekly challenge summary, and tabs (Activity History, My Videos via `VideoUploadsTab`, Liked, Other Dance Analytics), plus the full-screen liked-videos feed.
- `SettingsScreen`: profile photo, notifications, reminder time, sign out.
- `WeeklyChallengesScreen`, `WeeklyChallengeSummaryCard`, `WeeklyChallengeCelebrationDialog`.

Shared components:

- `AppBottomNavBar` (`core/AppBottomNavBar.kt`): floating bottom bar.
- `ShareBottomSheet` (`core/share/ShareSheet.kt`): share sheet used by the home feed and the liked-videos feed.
- `VideoVisibilitySelector` (`videouploads/VideoVisibilitySelector.kt`): Public/Private chips used by the upload form and the Edit dialog.
- `InstructorAvatarOverlay` (`posedetection`): 3D avatar driven by reference landmarks.

### State and Coordination Layer

ViewModels own screen state and user actions. All of these are `@HiltViewModel`:

- `AppViewModel`: auth/profile restore, selected top-level screen, selected dance, navigation helpers (`openUpload`, `openCommunity`, `openLeaderboard`, `openProfile`, `openSettings`, `openWeeklyChallenges`, `backToHome`), reminder preferences, profile photo upload state, starting the unsynced-session retry, and share-link handling (`openDanceById`, pending link while signed out, `sharedDance`, `deepLinkLoading`, `deepLinkError`).
- `AuthViewModel`: email/password and Google sign-in state, and one-off `AuthEvent`s.
- `DanceLibraryListViewModel`: library loading, template preparation, per-dance social cache (likes and comments, optimistic updates), and practice session submission.
- `VideoUploadsViewModel`: upload draft (including visibility), dancer analysis and review, upload progress, edit/delete flows, and per-upload visibility toggles (`setVisibility`, `updatingVisibilityIds`).
- `AnalyticsDashboardViewModel`: session list and selected session detail.
- `LeaderboardViewModel`: leaderboard scope, metric, friend actions.
- `ProfileViewModel`: streak, points, and liked dances for the profile.
- `AchievementViewModel`: per-session points, badges, and level-ups.
- `CommunityViewModel`: community posts and replies.
- `WeeklyChallengeViewModel`: the week's challenge set, progress, and celebration dialog.

`StreakViewModel` and `StreakViewModelFactory` (`danceactivity`) are legacy and unused.

ViewModels expose read-only `StateFlow` and mutate immutable UI state data classes.

### Data Layer

Repositories isolate Firebase, Room, and Storage APIs:

- `FirebaseAuthRepository`: sign up, sign in, Google sign-in (`signInWithGoogle`), sign out, profile restore, profile photo upload. Every auth path merge-writes `users/{uid}` and mirrors displayName/photoUrl into `leaderboardStats/{uid}`.
- `DanceLibraryRepository`: library query (excludes private dances), liked dances (excludes other users' private dances), `getDanceById(danceId, viewerUid)` for share links, likes (`loadSocialData`, `toggleLike`), comments (`loadComments`, `loadMoreComments`, `postComment`), template download, local session insert, session upload.
- `VideoUploadsRepository`: video compression/upload, dance metadata write (including `visibility`), template upload or WorkManager queue, upload management, `updateDance` (optional visibility), `setVisibility`, and `deleteDance`.
- `AnalyticsRepository`: learner and educator session listeners, Firestore mapping, learner display names, and reference video availability.
- `LeaderboardRepository`: leaderboard stats, ranking, friend list management.
- `FirebaseStreakRepository`, `AchievementRepository`, `PointsRepository`: streaks, badges, and points.
- `CommunityRepository`: real-time community posts and replies.
- `WeeklyChallengeRepository`: weekly challenge generation, progress, and rewards.

### Infrastructure Layer

Shared infrastructure includes:

- `AppDb` and `DanceSessionDao` for Room.
- Hilt modules under `core/Database/di`.
- `AutoRetryUploaderService` (`firebase`): a plain injected class (not an Android `Service`) that re-uploads unsynced sessions.
- Camera helpers (`CameraPreview`, `CameraBinder`, `CameraUseCaseFactory`) and video player helpers (`FeedPlayerFactory`, `VideoPlayerCache`, `SharedPlayerView`) under `core`.
- `VideoTemplateGenerationWorker` and `VideoCompressor` under `videouploads`.
- Notification scheduling under `notification` (`ReminderScheduler`, `ReminderReceiver`, `BootReceiver`, `NotificationChannels`, `NotificationPreferencesDataStore`).
- Pose detection and scoring under `posedetection`.
- `ShareLinks` under `core/share`: single source of truth for building and parsing share links.

## 3a. Design System

Theme code lives in `ui/theme`:

- `Color.kt`: the Chorevo "stage" palette (brand teal primary, magenta secondary, violet tertiary, dark ink and light mist surfaces).
- `Theme.kt`: `ChorevoTheme { }` applies fixed dark and light colour schemes (dynamic colour is off by default so the brand looks the same on every device), the type scale and shapes. `ChorevoTheme.colors` exposes brand tokens Material has no slot for: `accent`/`onAccent` (teal), `like` (magenta), `success`, `gradientStart`/`gradientEnd`/`onGradient` and the teal-to-magenta `brandGradient`, frosted `glass`/`glassBorder`, and `onMedia` for content over video.
- `Type.kt`: Sora for display, headline and `titleLarge` styles; Plus Jakarta Sans for `titleMedium`/`titleSmall`, body and labels. Both are bundled in `res/font` under the SIL Open Font License (`licenses/`).
- `Shape.kt`: rounded `ChorevoShapes` (10–28dp), `PillShape` for buttons and the bottom bar, and a 4dp-grid `Spacing` scale.
- `SegmentColors.kt`: good/ok/bad colours for scoring.

Screens should read colours from `MaterialTheme.colorScheme` or `ChorevoTheme.colors` rather than hardcoding hex values; buttons on the accent use `onAccent` for text. Third-party brand colours in the share sheet are the exception.

Navigation chrome: `AppNavigationRoute` floats `AppBottomNavBar` (a 64dp translucent pill, 12dp above the system bar) over the content. Tabs are Home, Score (leaderboard), a raised gradient "+" Upload button, Forum (community), and Profile. The bar is hidden on `AUTH` and `DANCE_PRACTICE`. Regular screens receive extra bottom padding; the home feed is drawn full-bleed and reads `LocalFloatingBarInset` to keep its overlay above the bar, and sets `LocalFloatingBarState.hidden` while the pager is scrolling so the bar slides away. `res/values(-night)/themes.xml` set matching window backgrounds to avoid a white flash on launch.

## 4. Dependency Injection

Hilt modules live in `core/Database/di`:

- `FirebaseModule`: FirebaseAuth, FirebaseFirestore, and FirebaseStorage singletons.
- `DatabaseModule`: Room `AppDb` (`app.db`, `fallbackToDestructiveMigration`) and `DanceSessionDao`.
- `DispatcherModule`: an `@IoDispatcher` `CoroutineDispatcher` (qualifier declared in `IoQualifier.kt`); used by `AutoRetryUploaderService`.
- `RepositoryModule` (file `RespositoryModule.kt`): intended repository providers. It imports `com.google.android.datatransport.runtime.dagger.Provides` instead of `dagger.Provides`, so Hilt does not use these methods. Repositories are actually bound through their `@Inject` constructors and are `@Singleton`, except `PointsRepository`, which is unscoped.

ViewModels receive repositories through constructor injection. `VideoTemplateGenerationWorker` is not a `@HiltWorker`; it calls `FirebaseFirestore.getInstance()` and `FirebaseStorage.getInstance()` directly. `ReminderScheduler` is constructed inside `AppViewModel`.

## 5. Data Storage

### Firestore

Main collections:

- `users/{uid}`: profile records (uid, email, role, displayName, photoUrl, lastSeen).
- `users/{uid}/danceLearningSessions/{sessionId}`: learner session results with a nested `analysis` map.
- `users/{uid}/friends/{friendUid}`: friend references (uid, displayName, photoUrl, addedAt).
- `users/{uid}/points/current`: totalPoints, lastSessionPoints, lastUpdated.
- `users/{uid}/achievements/current`: earnedBadges, lastUpdated.
- `users/{uid}/streaks/current`: weekStart, daysMask, totalStreakActivities, lastActivityDate, updatedAt. (`firestore.rules` also allows a legacy `streak/` path that no code uses.)
- `users/{uid}/weeklyChallenges/{weekStart}`: weekly challenge set and progress.
- `danceLibrary/{danceId}`: uploaded dance metadata, including `visibility` (`"public"` or `"private"`; a missing field reads as `"public"` through the `DanceLibraryModel` default), template status fields, and upload size details.
- `danceLibrary/{danceId}/likes/{uid}`: `{liked: true}`.
- `danceLibrary/{danceId}/comments/{commentId}`: commentId, uid, username, text, createdAt.
- `communityPosts/{postId}` and `communityPosts/{postId}/replies/{replyId}`: forum posts and replies.
- `leaderboardStats/{uid}`: aggregate leaderboard stats (totalPracticeSessions, avgSimilarityScore, totalActiveDays, legacy streakDays, displayName, photoUrl, updatedAt).

`DanceLibraryModel` exposes `isPrivate` (annotated `@get:Exclude` so it is not written back to Firestore) and `isVisibleTo(viewerUid)`, which returns true for public dances and for private dances viewed by their uploader. `isTemplateReady` is not excluded, so a `templateReady` field is also written.

Important query patterns:

- Enabled library items: `danceLibrary where enabled == true`, then filtered client-side to ready templates and non-private dances. The query does not filter on `visibility` because older documents have no such field.
- Liked dances: the enabled-library query, then one `likes/{uid}` read per dance.
- Comments: `orderBy createdAt desc limit 20`; "Load more" uses `orderBy createdAt asc startAfter(cursor)`.
- Creator uploads: `danceLibrary where educatorUid == uid orderBy createdAt desc`.
- Learner sessions: `users/{uid}/danceLearningSessions orderBy tsStart desc limit 30`.
- Creator analytics: collection group `danceLearningSessions where analysis.educatorUid == uid`, optionally also `analysis.danceId`.
- Weekly challenge generation: `users/{uid}/danceLearningSessions orderBy tsStart desc limit 40`.
- Community: `communityPosts orderBy createdAt desc limit 50`; replies `orderBy createdAt asc limit 100`.
- Leaderboard: `leaderboardStats orderBy metric desc limit 100`.

`firestore.indexes.json` holds the composite indexes for creator uploads (`educatorUid`, `createdAt desc`) and filtered creator analytics (`analysis.educatorUid`, `analysis.danceId`).

### Firebase Storage

Main paths:

- `danceVideos/{uid}/{danceId}.mp4`
- `danceTemplates/{danceId}.json`
- `profilePhotos/{uid}/profile.jpg`
- `danceThumbnails/{uid}/{fileName}` (allowed by the rules; not used by the app yet)

Storage rules validate content type and file size, and owner writes for videos, thumbnails and profile photos (see section 8).

### Room

Room database `app.db` (version 5, `exportSchema = false`, destructive migration) stores `DanceSessionEntity` in `dance_sessions`.

Key fields:

- `sessionId`
- `tsStart`, `tsEnd`, `durationMs`
- `activityName`, `activityType`
- `smoothnessScore`, `balanceScore`, `timingConsistencyScore`, `alignmentScore`
- `qualityFlag`
- `analysisJson`
- `synced`

`DanceSessionDao` provides `insert` (replace on conflict), `latestFlow()`, `sinceFlow(sinceTs)`, `unsynced()`, and `markSynced(id)`. Production code uses `insert`, `unsynced`, and `markSynced`.

### DataStore

`NotificationPreferencesDataStore` (`notification_preferences`) stores `push_enabled`, `notification_hour` (default 9), and `notification_minute` (default 0). `AppViewModel` observes these flows and `ReminderScheduler` reschedules alarms when settings change.

## 6. Key Flows

### Authentication Flow

1. `AppViewModel` asks `FirebaseAuthRepository.getCurrentUserProfile()`.
2. While restoring, `AppRouter` shows a loading route.
3. If no profile exists, `AuthRoute` is shown.
4. Email/password: `AuthViewModel` calls sign up or sign in on `FirebaseAuthRepository`.
5. Google: `AuthViewModel.requestGoogleSignIn` emits `AuthEvent.LaunchGoogleSignIn`; `AuthScreen` gets an ID token from `GoogleIdTokenProvider` (Credential Manager, `GetGoogleIdOption` with `default_web_client_id`, authorized accounts first, then all accounts); `signInWithGoogle` exchanges it through `GoogleAuthProvider`.
6. Every auth path, including restore in step 1, merge-writes `users/{uid}`, reads it back, and merge-writes `leaderboardStats/{uid}`.
7. `AppViewModel.onAuthed(profile)` stores the profile and routes to home.

### Dance Upload Flow

1. User picks a gallery video or records one in the upload UI.
2. Optional "Analyze Dancers": `VideoUploadsViewModel.analyzeDancers` runs `VideoPoseTemplateGenerator.generate` with the high-recall profile (8 fps, YOLO person detector, hands) on device; the user selects dancers and a default dancer and confirms.
3. `VideoUploadsViewModel.uploadDance` gathers profile and draft data (including visibility).
4. `VideoUploadsRepository` compresses the video (720p, up to 24 fps) and uploads the smaller of the original and compressed files.
5. Video uploads to `danceVideos/{uid}/{danceId}.mp4`.
6. Metadata writes to `danceLibrary/{danceId}`, then status fields are written (`saving`, `queued` or `skipped`, and `templateReviewStatus`).
7. "Upload Confirmed Dance": the confirmed template (filtered to the selected dancers and hand tracks) uploads to `danceTemplates/{danceId}.json`, and the status becomes `ready` with `templateReviewStatus = "confirmed"` and `dancerCount`.
8. "Upload and analyze in background": WorkManager enqueues `VideoTemplateGenerationWorker` (network required; inputs `dance_id`, `storage_path`, `template_fps`, `collect_hand_landmarks`).
9. The worker sets `processing`, downloads the video, generates a template with the standard profile, uploads the JSON, and writes `ready` with `templateStoragePath` and `dancerCount` (removing any legacy inline `template` field). It retries up to three attempts, then writes `failed` with `templateGenerationError`.

### Share Flow

1. The user taps Share on a dance in the home feed or the profile liked-videos feed.
2. The caller passes a `ShareContent` (dance ID, title, educator) to `ShareBottomSheet`; its `link` comes from `ShareLinks.danceLink`.
3. On open, the sheet checks which social apps are installed (`PackageManager`) and loads their launcher icons.
4. Tapping an installed app sends an `ACTION_SEND` intent targeted at that package (Facebook receives the bare link because it ignores free text).
5. Apps that are not installed but have a web fallback (WhatsApp, Facebook, Telegram, X) open their web share URL. Apps without one (Instagram, Messenger, Snapchat, LINE) are hidden. If launching fails, the system chooser is used.
6. Copy link writes to the clipboard; SMS and Email use `ACTION_SENDTO`; More opens `Intent.createChooser`.

### Share Link Open Flow

In the app:

1. `MainActivity` receives the link and calls `AppViewModel.openDanceById(danceId)`.
2. If auth has not resolved or no one is signed in, the ID is parked in memory, the screen is set to `AUTH`, and the ID is consumed after sign-in.
3. `DanceLibraryRepository.getDanceById(danceId, viewerUid)` loads the dance and fails for missing, disabled, private-to-someone-else, or not-ready dances.
4. On success, `AppUiState.sharedDance` is set and the screen becomes `HOME`; on failure, `deepLinkError` is shown as a snackbar.
5. `DanceLibraryListView(pinnedDance = ...)` places the shared dance first and scrolls the pager to it. Practice is only entered through Start Practice.
6. `AppViewModel.backToHome` and logout clear `sharedDance`.

On the web:

1. Firebase Hosting rewrites `/dance/**` to the `danceShare` function (`firebase.json`).
2. `functions/index.js` parses the ID (`render.parseDanceId`) and reads `danceLibrary/{id}` with the Admin SDK.
3. `functions/render.js` fills `functions/dance.html` with the dance title, description, video, Open Graph and Twitter tags. All values are HTML-escaped.
4. Missing, disabled and private dances render generic text with no title or video. (Template readiness is not checked, and the private check is case-sensitive, unlike the app's.)
5. The page sets `Cache-Control: public, max-age=300, s-maxage=600`.
6. In the browser, the page tries `intent://dance/{id}#Intent;scheme=chorevo;package=com.example.chorevo;...` on Android, with a fallback back to the page (`?app=missing`).

### Video Visibility Flow

1. The upload form's `VideoVisibilitySelector` updates `UploadDraft.visibility` (default `public`).
2. `VideoUploadsRepository.uploadDance` writes it into the `danceLibrary/{danceId}` document.
3. In the profile, each upload card shows its visibility and a Make private / Make public button that calls `VideoUploadsViewModel.setVisibility`, which merges `{visibility}` into the document and updates the listed item in place.
4. The Edit dialog includes the same selector; `saveEdit` passes it to `updateDance`.
5. Readers apply the rule: the feed hides all private dances, liked lists and share links use `isVisibleTo(viewerUid)`, and the web share page treats private dances as unavailable.

### Social Flow (Likes and Comments)

1. When a feed page appears, `DanceLibraryListViewModel.loadSocialData` reads the like count, the user's like, and the first page of comments into a per-dance `socialCache`.
2. Like (button or double-tap) toggles `danceLibrary/{danceId}/likes/{uid}` with an optimistic update.
3. Posting a comment writes `danceLibrary/{danceId}/comments/{commentId}` and updates the cache optimistically; "Load more" fetches the next page.

### Practice Flow

1. Learner taps Start Practice on a dance in the feed.
2. `DanceLibraryListViewModel.preparePracticeItem` loads or caches the pose template.
3. `AppViewModel.pickDance` routes to practice.
4. `DanceARPracticeScreen` shows a 3-second countdown, then uses CameraX and `LiveLandmarkDetector`, which runs Holistic Landmarker (pose + left/right hands, face ignored) and falls back to Pose Landmarker lite (body only).
5. `PoseSimilarityEvaluator` compares learner pose landmarks to the selected reference track; `HandSimilarityEvaluator` compares hands only when the template contains hand tracks.
6. On submit, a `DanceSessionEntity` and analysis payload are created (body-only scores; hand feedback is included in the feedback messages).
7. `DanceLibraryListViewModel.saveSessionAndUpload` inserts the session locally, uploads it to Firestore, and marks it synced. On success, streak and leaderboard stats refresh.
8. In parallel, without waiting for the upload, `AchievementViewModel.processCompletedSession` awards points (`users/{uid}/points/current`) and badges (`achievements/current`), and `WeeklyChallengeViewModel.recordCompletedSession` updates weekly challenges. A results dialog shows points, level-ups, and badges.
9. On upload failure, the local session remains unsynced. `AutoRetryUploaderService.retryUnsyncedSessions`, called from `AppViewModel` after a session restore at launch, re-uploads it, updates the streak, refreshes leaderboard stats, and marks it synced.

### Weekly Challenge Flow

1. The profile calls `WeeklyChallengeViewModel.loadForWeek`, which calls `getOrCreateCurrentWeek`. If the week has no document yet, `WeeklyChallengeEngine` generates three challenges from the last 40 sessions and the dance types in the library.
2. After each practice session, `recordCompletedSession` updates progress in a transaction, credits reward and bonus points through `PointsRepository`, and shows `WeeklyChallengeCelebrationDialog` for completions.

### Community Flow

1. `CommunityViewModel` observes the latest 50 posts through a snapshot listener.
2. Creating a post or reply writes `communityPosts/{postId}` or `communityPosts/{postId}/replies/{replyId}` with the author's uid, name, and photo.
3. Opening a post observes its replies (oldest first, up to 100).

### Analytics Flow

1. `AnalyticsDashboardViewModel.setArgs` sets learner (`MY_DANCE`) or creator (`OTHER_ANALYTICS`) mode.
2. `AnalyticsRepository.observeSessions` chooses the appropriate Firestore listener.
3. Session documents are mapped into `DanceSessionSummary` (keys use `sessionId||docPath`); creator mode also reads learner display names from `users/{uid}`.
4. Selecting a session observes detail by session ID or document path, and checks `danceLibrary/{danceId}` for reference video availability.
5. Nested `analysis` maps are parsed into `AnalysisData`.
6. UI renders summary metrics, video context, timeline points, and performance segments.

### Leaderboard Flow

1. After a successful session sync, `DanceLibraryListViewModel.saveSessionAndUpload` (and `AutoRetryUploaderService`) calls `LeaderboardRepository.refreshUserStats`.
2. The repository reads the profile and sessions, computes session count, average alignment ("similarity"), and active days, and writes `leaderboardStats/{uid}` (including the legacy `streakDays`).
3. Leaderboard UI observes the top 100 ordered by the selected metric.
4. Friends scope filters that top-100 list to the current user plus `users/{uid}/friends`.
5. Ranking is computed client-side; ties share a rank.

### Reminder Flow

1. User toggles reminders or changes the time in Settings.
2. `AppViewModel` writes preferences to DataStore.
3. `ReminderScheduler.rescheduleFromPreferences` cancels the alarm when reminders are disabled; a time change reschedules only when reminders are enabled. Exact alarms are used when `canScheduleExactAlarms()`, otherwise `setAndAllowWhileIdle`.
4. `ReminderReceiver` creates the `daily_reminders` channel, posts the notification if `POST_NOTIFICATIONS` is granted, and re-arms the next alarm.
5. `BootReceiver` restores scheduling after boot, locked boot, or package replacement when reminders are enabled.

## 7. Pose Pipeline

### Models

Model assets live in `app/src/main/assets`:

- `pose_landmarker_lite.task`: body-only model for the live-practice fallback and for standard (background WorkManager) template generation; also used by high-recall generation if the full model is missing.
- `pose_landmarker_full.task`: body-only model used by high-recall analysis for full-frame pose and as the per-crop fallback when Holistic crop inference fails. Not used by the standard profile.
- `holistic_landmarker.task`: pose + hands model for live practice and hand-enabled template generation. Face landmarks from this model are not stored, scored, or rendered.
- `person_detector_yolo.tflite`: YOLO person detector for high-recall analysis (TensorFlow Lite, 640×640 input, minimum confidence 0.25, NMS IoU 0.45).
- `models/instructor.glb`: 3D instructor avatar used by `InstructorAvatarOverlay`.

### Template Format

`PoseTemplate` (fps default 2) contains:

- `fps`
- legacy `frames` (`PoseFrame{tms, landmarks}`)
- multi-person `tracks` (`PoseTrack{trackId, label, frames, avgVisibility}`)
- `defaultTrackId`
- optional `selectionPreview` (timeMs, frame size, and candidate landmarks and bounds per track)
- `qualityWarnings` (`[{code, message}]`)
- `handTracks`

Each landmark frame is flattened as repeating `x, y, z, visibility` values. Legacy single-track templates are exposed through `availableTracks()`.

#### Hand Landmark Extension

Hand data is additive, so older pose-only templates still load:

```kotlin
data class HandLandmarkFrame(
    @PropertyName("tms") var tMs: Long = 0L,     // JSON key "tms"
    val leftHand: List<Float> = emptyList(),     // 21 landmarks * x,y,z,visibility
    val rightHand: List<Float> = emptyList()     // 21 landmarks * x,y,z,visibility
)

data class HandTrack(
    val trackId: String = "",
    val frames: List<HandLandmarkFrame> = emptyList()
)

// PoseTemplate
val handTracks: List<HandTrack> = emptyList()
```

Rules:

- Existing `frames`, `tracks`, `defaultTrackId`, and `selectionPreview` are unchanged.
- Hand landmark arrays use the same flattened `x, y, z, visibility` format as pose arrays. MediaPipe hand landmarks have no visibility, so it is filled with 1.0.
- Left and right hands each contain 21 MediaPipe hand landmarks when detected; a missing hand is an empty list.
- Frames where neither hand is detected are left out of the hand track.
- Hand-track IDs are matched to pose-track IDs by string (see section 11.9).
- There are no face fields in the template.

### Template Generation

`VideoPoseTemplateGenerator` (an `object`) runs on `Dispatchers.Default`. It:

- reads frames with `MediaMetadataRetriever` (up to 120 seconds of video; at least 5 fps for the standard profile and 8 fps for high-recall),
- runs full-frame MediaPipe in VIDEO mode; per-crop inference uses IMAGE mode,
- **standard profile** (background worker): Pose Landmarker lite, plus full-frame Holistic (single person) when hands are requested. Holistic's pose becomes the frame's detection and hands are stored under `track_1`. If Holistic cannot initialize, a `handExtractionPartial` warning is added and generation continues body-only.
- **high-recall profile** (on-device "Analyze Dancers"): low-light preprocessing, YOLO person detection (enabled automatically when hands are requested), same-frame duplicate removal, ByteTrack tracking, per-dancer padded crops through Holistic (falling back per dancer to body-only pose), remapping of pose and hand landmarks to full-frame coordinates, track fragment merging, and short-gap interpolation. Fixed-region crop passes run only for high-recall body-only generation, which the app does not currently request.
- ignores all face landmarks even if the Holistic result contains them,
- merges duplicate detections,
- tracks people across frames with `MultiPersonPoseTracker` (up to 8 dancers),
- attaches hand tracks and quality warnings,
- returns a serializable `PoseTemplate`, or fails only when no dancers are detected.

### Similarity Evaluation

`PoseSimilarityEvaluator`:

- selects the reference frame by timestamp (fps index, nearest of ±1 frames) from `template.frames`; the practice screen substitutes the selected track's frames,
- normalizes learner and reference poses around the hip midpoint (else shoulders, else nose), scaled by shoulder width (else hip width),
- optionally mirrors the reference X axis (on in live practice),
- compares stable joints 11–16 (shoulders, elbows, wrists) and 23–28 (hips, knees, ankles) with minimum visibility 0.3,
- maps error to score with a Gaussian curve (sigma 0.85),
- computes timing consistency from score variance (1 − std/0.25) and smoothness from error changes (1 − mean jerk/0.15),
- emits timeline points every 250 ms and performance segments labeled GOOD (≥ 0.65), OK (≥ 0.45), or BAD,
- produces body-part feedback such as "Lift/Lower/Move your …",
- remains the source of truth for the saved body alignment score.

#### Hand/Finger Similarity Evaluation

`HandSimilarityEvaluator` is separate from `PoseSimilarityEvaluator`:

- It scores only when learner and reference frames have at least one matching hand, and scores left and right hands independently.
- Each hand is normalized relative to its wrist and scaled by the mean wrist-to-MCP distance (MCPs 5, 9, 13, 17), falling back to the visible-landmark bounding-box diagonal (minimum 0.04).
- Landmarks 1–20 are compared with a visibility-weighted Gaussian (sigma 0.55, minimum visibility 0.25). The hand score is the average of the per-hand session means.
- Feedback: "Keep hands visible." (both missing), "Left hand not detected.", "Right hand not detected.", "Match the hand shape." (score below 0.72), or "Good hand shape."
- No face, facial expression, or face mesh data is scored.

`combineWithBodyScore` drives the on-screen readout only:

```text
if hand reference and learner hand score are available:
    displayedOverall = bodyScore * 0.85 + handScore * 0.15
else:
    displayedOverall = bodyScore
```

Persisted session scores, achievements, and weekly challenges use the body alignment score. Hand feedback messages are saved with the session feedback.

#### Live Practice Pose + Hands Flow

```text
Camera frame
↓
LiveLandmarkDetector
  → HolisticLandmarkerHelper (holistic_landmarker.task, IMAGE mode)
  → fallback: PoseLandmarkerHelper (pose_landmarker_lite.task) if Holistic is missing, fails to start, or throws
↓
HolisticFrameLandmarks(pose, leftHand, rightHand); HolisticFrameSelector picks one person
↓
PoseSimilarityEvaluator for the body score (built when the template has frames)
↓
HandSimilarityEvaluator for the hand score (built when the template has hand tracks)
↓
On-screen Overall/Hands scores + feedback
```

Implementation notes:

- `PoseLandmarkerHelper` remains the fallback; old pose-only templates work unchanged.
- The practice HUD shows hand feedback and the hand score. The learner overlay draws the body skeleton only; hand landmarks are not drawn.
- Face landmarks are never persisted or rendered.

## 8. Security Architecture

Firestore rules (`firestore.rules`, matching the deployed ruleset) enforce:

- signed-in access for protected data,
- `users/{uid}`: readable by any signed-in user (including email); written only by the owner; not deletable,
- owner-only reads and writes for `points`, `achievements`, `streaks` (and the legacy `streak`), `weeklyChallenges`, and `friends` under `users/{uid}`,
- `danceLearningSessions`: owner writes; reads by the owner or by the educator named in `analysis.educatorUid` (including a collection-group rule); create/update require `analysis.danceId` to reference an existing dance whose `educatorUid` equals `analysis.educatorUid`; `analysis.danceId` and `analysis.educatorUid` cannot change on update; no deletes,
- `danceLibrary/{danceId}`: readable by any signed-in user; create requires `educatorUid == auth.uid` and `danceId == docId`; update and delete only by the creator, with `educatorUid` and `danceId` unchanged,
- `leaderboardStats/{uid}`: readable by any signed-in user, written only by the owner, no deletes,
- `communityPosts/{postId}` and `replies/{replyId}`: readable by signed-in users; create validated (allowed fields only, author is the caller, title ≤ 120, body ≤ 1,500, reply ≤ 500, replies only on existing posts); no updates; delete only by the author.

There are no rules for `danceLibrary/{danceId}/likes` or `danceLibrary/{danceId}/comments`, so Firestore denies them by default.

Storage rules enforce:

- owner-only writes for `danceVideos/{uid}/` (video, < 200 MB), `danceThumbnails/{uid}/` (image, < 10 MB), and `profilePhotos/{uid}/` (image, < 10 MB),
- public reads for dance videos and thumbnails, and authenticated reads for profile photos and templates,
- `danceTemplates/{fileName}`: any signed-in user may create, update, or delete (JSON, < 20 MB); there is no ownership check,
- deny-by-default fallback.

Video privacy:

- Enforced in the app (`DanceLibraryRepository`, `DanceLibraryModel.isVisibleTo`) and in the `danceShare` function, not in Firestore rules. `danceLibrary` documents remain readable by any signed-in user, so a private dance's metadata can still be read directly through the Firebase API.
- Video files stay at token-protected Storage download URLs; making a dance private does not revoke an existing URL.
- The `danceShare` function uses the Admin SDK (bypasses rules) and only renders public, enabled dances.

## 9. Testing and CI

Test locations:

- Unit tests: `app/src/test`
- Instrumented tests: `app/src/androidTest`
- Cloud Function tests: `functions/test`

Unit tests (`app/src/test`):

- `analytics`: `AnalyticsDashboardViewModelTest`, `AnalyticsRepositoryTest`
- `core`: `AppUiStateTest`, `AppViewModelTest` (including share-link handling)
- `dancelibrarylist`: `DanceLibraryModelTest`, `DanceLibraryRepositoryTest` (including video privacy)
- `firebase`: `FirebaseAuthRepositoryTest`
- `leaderboard`: `LeaderboardRepositoryTest`, `LeaderboardViewModelTest`
- `posedetection`: `ByteTrackDancerTrackerTest`, `ConservativeFramePreprocessorTest`, `CropPoseGeometryTest`, `HandSimilarityEvaluatorTest`, `HolisticFrameLandmarksTest`, `HolisticFrameSelectorTest`, `MissingLandmarkInterpolatorTest`, `MultiPersonPoseTrackerTest`, `PersonDetectorFactoryTest`, `PoseDetectionMergerTest`, `PoseSimilarityEvaluatorTest`, `PoseTemplateJsonTest`, `TrackFragmentMergerTest`, `YoloPersonDetectionParserTest`
- `videouploads`: `VideoUploadTest` (upload ViewModel, dancer review, confirmed templates, visibility)
- `weeklychallenge`: `WeeklyChallengeEngineTest`, `WeeklyChallengeViewModelTest`

Instrumented tests (`app/src/androidTest`): `AnalyticsDashboardViewTest`, `DanceARPracticeScreenTest`, `DanceSessionDaoTest` (Room DAO), `DanceLibraryListViewTest`, `AuthScreenTest`, `LeaderboardScreenTest`, `VideoUploadActivityScreenTest`.

Cloud Function tests: `functions/test/render.test.js` (link parsing, title rendering, HTML escaping, fallbacks for missing/disabled/private dances), run with `npm test` in `functions/`. They are not part of GitHub Actions.

GitHub Actions (`.github/workflows/android.yml`) runs on push and pull request to all branches, cancelling in-progress runs for the same ref, with JDK 17 (Temurin). Jobs run in order:

1. Unit tests: `testDebugUnitTest`.
2. Integration tests: `connectedDebugAndroidTest` on an API 29 x86_64 `pixel_5` emulator (2 GB AVD disk, 300 s boot timeout).
3. Build: `assembleDebug assembleRelease`.

Artifacts (kept 7 days): `chorevo-debug-apk`, `chorevo-release-apk`, and `instrumented-test-results` (uploaded even on failure).

CI notes:

- `android-actions/setup-android` installs only `platform-tools`; the action's default also requests the removed legacy `tools` package, which fails.
- The emulator job frees runner disk space (removes .NET, GHC, Boost and CodeQL) and caps the AVD disk at 2 GB so the emulator can create its userdata partition.

Deployment:

- Hosting and the share function are deployed manually with `cd functions && npm install && cd .. && firebase deploy --only functions,hosting`. The project is on the Blaze plan.
- Firestore and Storage rules are deployed separately; keep `firestore.rules` and `storage.rules` in sync with the deployed rulesets.
- The functions artifact repository has a cleanup policy that deletes old build images.

## 10. Known Architectural Considerations

- Navigation is a custom state machine (`AppUiState.screen` plus `when` in `AppRouter`); the Navigation Compose dependencies are declared but unused. This may become hard to scale if nested flows grow.
- Package segments `ViewModel`, `Repository` and `Database` are mixed-case, `videouploads/repository` is lowercase, and the router files declare `package com.routes`. New packages should be lowercase and match their folders.
- `RepositoryModule` imports the wrong `@Provides` annotation and is ignored by Hilt; repositories rely on `@Inject` constructors instead.
- Room has `exportSchema = false` and destructive migration, which is convenient for prototype speed but loses unsynced sessions on schema changes. `dance_sessions` has no user column, so unsynced sessions are not scoped to an account.
- Leaderboard values are client-written; high-trust ranking would need server-side verification.
- Template generation is compute-heavy and runs off the main thread (`Dispatchers.Default` or WorkManager).
- Firestore indexes in `firestore.indexes.json` must stay aligned with analytics and creator upload queries.
- Video privacy is client-enforced; rules-level enforcement needs a `visibility` backfill and a `visibility == "public"` feed query.
- Likes and comments have no Firestore rules and are denied by default.
- App Links are declared with `autoVerify`. `public/.well-known/assetlinks.json` exists, but the `firebase.json` hosting `ignore` rule `**/.*` excludes it from deploys, so links are not verified. A copy in `app/src/main/assets/assetlinks.json` is bundled in the APK and serves no purpose.
- The share function and Hosting are not deployed by CI, so web changes must be deployed manually after merging.
- `PoseEstimator`/`NoOpPoseEstimator` and `PoseTemplateRepository` in `posedetection` are unused scaffolding.

## 11. Multi-Dancer AI Architecture

### 11.1 High-Level Design

The standard MediaPipe path remains for the background worker and simple videos. The high-recall path (on-device "Analyze Dancers", followed by creator confirmation) adds person detection and tracking before per-dancer Holistic landmark extraction.

```text
Uploaded video
↓
Frame extraction at 8 fps (high-recall)
↓
Low-light preprocessing (brightness, contrast, gamma)
↓
YOLO person detection using person_detector_yolo.tflite
↓
Same-frame duplicate person-box removal
↓
ByteTrack dancer tracking (drives per-dancer cropping)
↓
Per-dancer crop generation with padding for hands/fingers
↓
Holistic Landmarker on each crop using holistic_landmarker.task
↓
Extract pose + leftHand + rightHand only; discard face output
↓
Remap pose and hand landmarks to original full-frame coordinates
↓
MultiPersonPoseTracker assigns template track IDs
↓
Track fragment merge and short-gap interpolation
↓
Creator reviews and confirms dancers
↓
PoseTemplateJson serialization with handTracks
↓
Firebase Storage upload to danceTemplates/{danceId}.json
```

Fallbacks:

- If Holistic fails for a crop (or returns no pose), that dancer is processed by the body-only crop estimator (`pose_landmarker_full.task`, or `lite` if missing).
- If Holistic cannot initialize, analysis continues body-only with a `handExtractionPartial` warning.

### 11.2 Component Responsibilities

#### `posedetection` package

Implemented classes:

- `PersonDetector` (interface) with `TfliteYoloPersonDetector`, `YoloPersonDetectionParser` (both YOLO output layouts, NMS), `PersonDetectorFactory`, and `NoOpPersonDetector`.
- `DetectionBox`: normalized bounding box, confidence, label, class ID, and source.
- `DancerTracker` (interface) with `ByteTrackDancerTracker`.
- `TrackedDancerBox`: tracker output with `trackId`, frame index, timestamp, box, state, and confidence.
- `FramePreprocessor` (interface) with `ConservativeFramePreprocessor` (brightness, contrast, gamma) and `NoOpFramePreprocessor`.
- `CropPoseEstimator` (interface) with `MediaPipeCropPoseEstimator` (body-only) and `MediaPipeCropHolisticEstimator` (pose + hands), returning `CropPoseEstimationResult(poses, handFrames, warnings)`.
- `CropPoseGeometry`: crop padding, clamping, and remapping.
- `PoseDetectionMerger`: removes same-frame duplicate boxes and landmark detections.
- `MultiPersonPoseTracker`: assigns template track IDs and builds quality warnings.
- `TrackFragmentMerger`: merges split tracks after the video has been processed.
- `MissingLandmarkInterpolator`: fills short gaps only when confidence and timing make it safe.

Quality warnings are produced by several components rather than a central reporter: `lowConfidenceTracks`, `manyMissingFrames` (`MultiPersonPoseTracker`), `lowLightQuality` (preprocessor), `personDetectorUnavailable` (factory), `cropPoseMissing`, `cropHandExtractionPartial` (crop estimators), and `handExtractionPartial`, `mergedTrackFragments`, `inferredLowConfidenceLandmarks`, `trackCleanupSkipped` (generator). `VideoUploadsUiState` maps them to creator-friendly text.

Not implemented: a central `PoseAnalysisQualityReporter`, denoising, and sharpening.

#### `videouploads` package

- Owns the upload flow, dancer review state, WorkManager scheduling, and creator-facing status.
- Passes the high-recall profile, person-detector config, and hand option into the generator for "Analyze Dancers".
- Surfaces detected dancer count, preview thumbnails, warnings, and review state in the upload UI.

#### `dancelibrarylist` and `danceactivity` packages

- Keep practice preparation and scoring compatible with `PoseTemplate` output.
- Live practice does not use YOLO or multi-dancer analysis.

### 11.3 Data Models

```kotlin
enum class DetectionSource { MediaPipeFullFrame, MediaPipeCrop, Yolo, NoOp, Unknown }

data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val label: String = PERSON_LABEL,
    val classId: Int = PERSON_CLASS_ID, // 0
    val source: DetectionSource = DetectionSource.Unknown // the YOLO parser sets Yolo
)

data class TrackedDancerBox(
    val trackId: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val box: DetectionBox,
    val state: TrackState,
    val confidence: Float
)

enum class TrackState {
    Visible,
    TemporarilyLost,
    Recovered,
    Ended
}
```

`DetectionBox` also provides computed width, height, area and bounds, and a `normalized(...)` factory that clamps coordinates. Detector boxes and landmarks use normalized coordinates in `0f..1f`.

### 11.4 Inference Backends

```kotlin
interface PersonDetector : AutoCloseable {
    suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox>
}
```

Implementations:

- `TfliteYoloPersonDetector` (the only real backend)
- `NoOpPersonDetector` (used when the model is missing or fails to load, and in tests)

ONNX Runtime Mobile and NCNN backends are possible future alternatives.

### 11.5 Detection and Tracking Rules

- YOLO person detection runs first in high-recall mode, filtered to the `person` class.
- Confidence-sorted NMS (IoU 0.45) and `PoseDetectionMerger` run before the tracker update.
- ByteTrack-style two-stage matching (high-confidence, then low-confidence detections, IoU threshold 0.30) keeps low-confidence detections useful for association.
- Lost tracks are kept alive for `fps * 3` frames.
- Recent lost tracks are considered before new tracks are created, and only high-confidence detections create tracks.
- Up to 8 dancers are tracked (`MAX_TRACKED_DANCERS` in the generator; `DancerTrackerConfig.maxTracks`).
- After processing, fragmented tracks are merged when their time ranges, positions, body sizes, and movement direction are compatible.

### 11.6 Crop Landmark Estimation Rules

- Crops are made only for tracks in state `Visible` or `Recovered`.
- Each tracked box is padded before cropping (0.18 of the box for body, 0.32 for hands) and clamped to the frame.
- Hand-enabled high-recall analysis runs `holistic_landmarker.task` on each padded crop and keeps only pose, leftHand, and rightHand.
- Crop landmarks are remapped back to full-frame normalized coordinates:

```text
fullX = cropLeft + cropX * cropWidth
fullY = cropTop + cropY * cropHeight

fullHandX = cropLeft + handX * cropWidth
fullHandY = cropTop + handY * cropHeight
```

- `z` is preserved; x, y and visibility are clamped to `0..1`.
- Invalid, too-small, or failed crops are dropped with a warning (`cropPoseMissing` / `cropHandExtractionPartial`); Holistic failures fall back per dancer to body-only pose.

### 11.7 Low-Light Preprocessing Rules

`ConservativeFramePreprocessor` applies, by default:

- brightness +8,
- contrast 1.08,
- gamma 1.10,

with contrast and gamma clamped to safe ranges, and a `lowLightQuality` warning when average luminance is below 45. Preprocessing runs before YOLO detection and before crop estimation in high-recall mode. It is configurable in code (`FramePreprocessorConfig`) but has no user-facing toggle. Denoising and sharpening are not implemented.

### 11.8 Duplicate and Fragment Cleanup

There are two cleanup stages:

1. Same-frame duplicate removal before tracking.
2. Cross-time track-fragment merging after tracking.

Same-frame duplicates are removed using IoU and center distance:

```text
keep the larger, more confident detection (area × confidence, or area × average visibility for landmarks)
remove the other if IoU > 0.45 or center distance < 0.08
```

Fragmented tracks are merged when:

- their time ranges do not significantly overlap (overlap ratio ≤ 0.20),
- the gap is at most 3 seconds,
- the last position of the first track is close to the first position of the second (center distance ≤ 0.18 plus 0.015 per gap frame),
- body size is similar (size ratio 0.45–2.2),
- movement direction is plausible (direction cosine ≥ −0.2).

Short gaps of up to 3 frames or 500 ms are then interpolated, with visibility capped at 0.20.

### 11.9 Fallback Strategy and Known Limitations

If the YOLO model is missing, unsupported, or fails at runtime:

1. `PersonDetectorFactory` logs a safe warning without private paths or media URLs and returns `NoOpPersonDetector`.
2. Analysis falls back to full-frame body-only MediaPipe detection (no crop passes when hands are requested, and no hand tracks).
3. A `personDetectorUnavailable` warning is surfaced.
4. The upload fails only if no dancers are detected.

Known limitations:

- Template pose-track IDs come from `MultiPersonPoseTracker`, while hand frames are keyed by ByteTrack IDs. `withHandTracks` matches them by string only, so a hand track can attach to the wrong dancer or be dropped. The standard path hardcodes `track_1`.
- The standard background profile is effectively single-dancer when hands are requested.
- Live practice does not fall back to Pose Landmarker when Holistic returns an empty pose.

### 11.10 Performance Boundaries

- High-recall analysis runs on-device from `VideoUploadsViewModel` via `withContext(Dispatchers.Default)` (not an injected dispatcher and not WorkManager). The WorkManager worker runs the standard profile.
- Neither profile runs on the main thread.
- Frame sampling is at least 5 fps for the standard profile and 8 fps for high-recall.
- The maximum number of tracked dancers is 8.
- Crop bitmaps and the preprocessor's copy are recycled; the frame bitmap from `getFrameAtTime` is not recycled (partially implemented).
- Detector, tracker, MediaPipe, and frame resources are closed in `finally` blocks.
