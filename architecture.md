# Chorevo Architecture

## 1. Overview

Chorevo is a single-module native Android application:

- Module: `:app`
- Package: `com.example.chorevo` (the router files in `core/routes` declare `package com.routes`)
- UI: Jetpack Compose and Material 3, with the Chorevo design system in `ui/theme`
- App architecture: MVVM-style presentation with repositories for data access
- Dependency injection: Hilt
- Local data: Room and DataStore
- Cloud data: Firebase Auth (email/password and Google via Credential Manager), Firestore, Firebase Storage, Firebase App Check (Play Integrity in release, debug provider in debug builds)
- Background work: WorkManager
- Camera/video: CameraX, Media3 ExoPlayer (feed and previews) and Media3 Transformer (upload compression), Coil (images)
- Pose analysis: MediaPipe Tasks Vision 0.10.26.1 (Pose Landmarker and Holistic Landmarker) and TensorFlow Lite 2.17.0 (YOLO person detector)
- 3D: SceneView for the instructor avatar overlay
- Web share links: Firebase Hosting (`chorevo-dea05.web.app`) rewriting `/dance/**` to the `danceShare` Cloud Function in `functions/` (Node.js 22, `firebase-functions` v2 HTTPS, Admin SDK)
- Discovery maintenance: Cloud Functions `danceDerivedFields` (Firestore trigger on `danceLibrary/{id}`), `danceLikeCount` (trigger on `danceLibrary/{id}/likes/{uid}`) and `refreshDiscovery` (scheduled every 6 hours) keep the feed fields, like counts, trending scores and `meta/danceCategories` current. The field logic is in `functions/discovery.js` (pure, tested) and the jobs in `functions/maintenance.js`, shared with the one-off `functions/scripts/backfill.js`.
- Push notifications: `notifyGroupInvitation` (trigger on creating `groupInvitations/{id}`) sends the invitee an FCM message (`notification` + `data {type: "group_invitation", groupId}`, Android channel `group_invites`) to the tokens in `users/{uid}/fcmTokens` and deletes tokens FCM reports as dead. Message building is in `functions/groups.js` (`invitationMessage`, `deadTokens`, tested).
- Groups maintenance: `groupMemberCount` (trigger on `groups/{id}/members/{uid}`) keeps `memberCount`, and `groupCleanup` (on group delete) removes the invite code, pending invitations, group files and subcollections.

Navigation Compose and Hilt Navigation Compose are declared as dependencies, but navigation uses a custom `Screen` enum rather than a `NavHost`. The Firebase Analytics SDK is included but logs no events.

The app starts in `Chorevo`, initializes Firebase/App Check, then `MainActivity` hosts the Compose tree and calls `AppRouter`.

## 2. Runtime Entry Points

### `Chorevo`

`Chorevo` is the `Application` class. It:

- enables Hilt through `@HiltAndroidApp`,
- initializes Firebase,
- installs App Check through `AppCheckInstaller` (`src/release`: Play Integrity; `src/debug`: the debug provider, a `debugImplementation` dependency that never ships), and creates the notification channels,
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
- switches with `when (screen)` inside an `AnimatedContent` (practice slides up over the feed and back down; other screen changes fade through with a slight scale; the practice content keeps the last selected dance while it animates out) over the `Screen` enum: `AUTH`, `HOME`, `DANCE_PRACTICE`, `UPLOAD`, `COMMUNITY`, `LEADERBOARD`, and the profile section (`PROFILE`, `SETTINGS`, `WEEKLY_CHALLENGES`, all rendered by `ProfileSectionRoute`),
- passes `AppUiState.sharedDance` to `HomeRoute` so a dance opened from a share link is pinned first in the feed,
- shows share-link errors (missing, disabled, private, not ready) as a snackbar,
- handles system back with a `PredictiveBackHandler` (enabled when `Screen.backTarget` is non-null): drag progress scales the content down with rounded corners, and release calls `AppViewModel.navigateBack()` (Practice/Upload/Groups/Score/Profile → Home, Settings/Weekly Challenges → Profile; Home and Auth fall through so the system closes the app). `android:enableOnBackInvokedCallback="true"` is set in the manifest. Screens with inner states register their own `BackHandler`, which takes priority: upload steps, an open group (its replies sheet first), and the profile's liked-videos player.

## 3. Layered Structure

### Presentation Layer

Compose screens and routes live in feature packages. They observe ViewModel state and emit callbacks.

Screens:

- `OnboardingScreen` (`firebase`): first-launch welcome carousel (`HorizontalPager`, page dots, Skip / Next / Get started). `AppRouter` shows it instead of `AuthRoute` while `AppUiState.onboardingSeen == false` (null while loading shows the startup route); `AppViewModel.completeOnboarding` saves `onboarding_seen`.
- `AuthScreen`: email/password and "Continue with Google" on the stage background with the animated dancer and gradient wordmark; `systemBarsPadding`, `imePadding` and vertical scroll keep the form above the keyboard.
- `DancingFigure` / `StageBackground` (`core/DancingFigure.kt`): a pose skeleton looping through keyframed poses (smoothstep interpolation, brand-gradient bones with a glow) and a dark background with two drifting brand glows; used by onboarding and sign-in.
- `DanceLibraryListView`: home video feed with a right-hand action rail (creator, like, comments, Share), bottom-left info (creator, title, expandable caption, chips, gradient Practice button), a seekable progress bar, and the comments sheet. `DiscoveryBar` (`dancelibrarylist/DiscoveryBar.kt`) is drawn over the top of the feed: tabs (`feedTab_*` tags), a search field (`searchButton`, `searchField`) and category chips (`categoryChip_all`, `categoryChip_<key>`). The feed shows `DanceLibraryUiState.visibleItems`, and a filter with no matches shows an empty state with `clearFilters` while keeping the bar mounted.
- `DanceARPracticeScreen`: draws the camera practice screen from `PracticeViewModel`'s state: countdown, Full Routine / Step-by-Step modes, instructor avatar, accuracy-coloured learner skeleton, live score ring and combo badge, frosted-glass controls, and the results screen. HUD pieces live in `PracticeHud.kt` and the results screen (grade, confetti, personal bests, share card) in `PracticeResults.kt`.
- `DanceStylePicker` (`videouploads/DanceStylePicker.kt`): dance-type dropdown of `STANDARD_DANCE_STYLES` plus "Other…" free text with a "Did you mean …?" suggestion from `closestStandardStyle`; used by the upload Details step and the Edit dialog.
- `VideoUploadActivityScreen`: five-step upload flow (Video → Cover → Details → Dancers → Publish) with Back/Next buttons and steps animated by `AnimatedContent`; step state is screen-local (`rememberSaveable`) and resets to the first step when a publish clears the draft. Step pieces (`UploadStep`, `UploadStepIndicator`, `LocalVideoFrame`, `CoverFramePicker`, `PublishSummary`) live in `videouploads/UploadWizard.kt`.
- `AnalyticsDashboardView`: learner and creator analytics. With `showOverview = true` (the profile's Activity and Learners tabs) it renders `AnalyticsOverview` above the list: stat tiles with a sparkline, a draw-in trend chart, an 8-week activity heatmap, and a body diagram. The numbers come from `computeOverviewStats` and the body focus from `bodyFocusFromFeedback` (`analytics/AnalyticsStats.kt`, plain Kotlin, unit tested), which counts corrections per `BodyArea` in each session's stored feedback messages.
- `LeaderboardScreen`: Global/Friends leaderboard with a top-three `Podium` (medal rings, rising blocks, `podium_rank_N` test tags), ranks 4+ in a list, and the user's "Your position" card pinned at the bottom. Add/remove-friend buttons come from `friendActionFor` for both podium and list.
- `GroupsScreen`: "My groups" and "Discover" tabs, create-group and join-by-code dialogs, QR scanning (`InviteQr.scan`, the Google code scanner — no camera permission), and the join prompt for an invite link handed over by `AppViewModel.pendingGroupInvite`. `GroupDetailScreen`: the open group's header (visibility, member count, invite code with QR/copy/share; the QR code, drawn with ZXing by `InviteQr.bitmap`, encodes `ShareLinks.groupInviteLink`), join banner, posts (text, shared dance, group video with a full-screen player), the member composer (dance picker, gallery/record video), replies bottom sheet, members dialog, leave/delete.
- `UserProfileScreen`: header, streak-ring avatar, stats row, streak card, level panel, weekly challenge summary, and icon tabs (Uploads and Private as thumbnail grids via `VideoUploadsTab(gridFilter = …)` with an action sheet, Liked, Activity, Learners), plus the full-screen liked-videos feed.
- `SettingsScreen`: profile photo, username (`@name · Change`), notifications, reminder time, sign out.
- `UsernameDialog` (`firebase/`, with `UsernameViewModel`): pick or change the username with a live availability check. `AppRouter` shows it once per launch, with "Later", while the profile has no username.
- `WeeklyChallengesScreen`, `WeeklyChallengeSummaryCard`, `WeeklyChallengeCelebrationDialog`.

Shared components:

- `AppBottomNavBar` (`core/AppBottomNavBar.kt`): floating bottom bar.
- `ShareBottomSheet` (`core/share/ShareSheet.kt`): share sheet used by the home feed and the liked-videos feed.
- `VideoVisibilitySelector` (`videouploads/VideoVisibilitySelector.kt`): Public/Private chips used by the upload form and the Edit dialog.
- `InstructorAvatarOverlay` (`posedetection`): 3D avatar driven by reference landmarks.
- `VideoThumbnailTile` (`core/VideoThumbnailTile.kt`): 9:16 video thumbnail — the uploaded `thumbnailUrl` JPEG via Coil when present, otherwise the frame at the dance's `coverFrameMs` with title and an optional badge, used by the liked and uploads grids; shimmers while the frame loads.
- `Shimmer.kt` (`core`): `Modifier.shimmer()` plus `ShimmerBlock`, `ShimmerList` and `ShimmerGrid` loading placeholders (used by the feed skeleton, profile/uploads grids, leaderboard, weekly challenges and analytics detail).
- `AnimatedEmptyState` (`core/EmptyState.kt`): floating icon in a pulsing halo, title and hint, faded in on first show; used for empty feed, uploads, liked, leaderboard and challenges.
- `Haptics` / `rememberHaptics()` (`core/Haptics.kt`): `tick()` (likes) and `success()` (combo milestones, results grade, finished publish) via the view's haptic feedback.

### State and Coordination Layer

ViewModels own screen state and user actions. All of these are `@HiltViewModel`:

- `AppViewModel`: auth/profile restore, selected top-level screen, selected dance, navigation helpers (`openUpload`, `openCommunity`, `openLeaderboard`, `openProfile`, `openSettings`, `openWeeklyChallenges`, `backToHome`), reminder preferences, profile photo upload state, starting the unsynced-session retry, and username updates (`onUsernameSaved`), group invite links (`openGroupInvite`, `pendingGroupInvite`), share-link handling (`openDanceById`, pending link while signed out, `sharedDance`, `deepLinkLoading`, `deepLinkError`).
- `AuthViewModel`: email/password and Google sign-in state, and one-off `AuthEvent`s.
- `DanceLibraryListViewModel`: library loading, template preparation, per-dance social cache (likes and comments, optimistic updates), and practice session submission.
- `VideoUploadsViewModel`: upload draft (including visibility and `coverFrameMs`, reset when a new video is picked), dancer analysis and review, upload progress, edit/delete flows, and per-upload visibility toggles (`setVisibility`, `updatingVisibilityIds`).
- `AnalyticsDashboardViewModel`: session list and selected session detail.
- `LeaderboardViewModel`: leaderboard scope, metric, friend actions.
- `ProfileViewModel`: streak, points, and liked dances for the profile.
- `AchievementViewModel`: per-session points, badges, and level-ups.
- `PracticeViewModel`: one practice session: countdown and timer, live pose detection and scoring (camera frames arrive on the camera analysis thread), Full Routine / Step-by-Step mode, reference dancer, and building the session to submit. Each attempt gets fresh evaluators. It keeps the session through a rotation and is released when the screen closes.
- `GroupsViewModel`: my groups (live), discover search (300 ms debounce), create/join/leave/delete, the open group's live group, membership, members and posts, the composer (text, one of the user's public ready dances, or a local video with upload progress), and replies of the open post.
- `WeeklyChallengeViewModel`: the week's challenge set, progress, and celebration dialog.

ViewModels expose read-only `StateFlow` and mutate immutable UI state data classes.

### Data Layer

Repositories isolate Firebase, Room, and Storage APIs:

- `FirebaseAuthRepository`: sign up, sign in, Google sign-in (`signInWithGoogle`), sign out, profile restore, profile photo upload. Every auth path merge-writes `users/{uid}` (uid, name, photo, lastSeen; never email or role, and it removes an email stored by older versions). `deleteAccount` deletes the Firebase Auth user (the `deleteUserData` function removes their data); `sendPasswordReset` emails a reset link; sign-up sends a verification email.
- `DanceLibraryRepository`: library query (excludes private dances), liked dances (excludes other users' private dances), `getDanceById(danceId, viewerUid)` for share links, likes (`loadSocialData`, `toggleLike`), comments (`loadComments`, `loadMoreComments`, `postComment`), template download, local session insert, session upload.
- `VideoUploadsRepository`: video compression/upload, dance metadata write (including `visibility` and `coverFrameMs`), template upload or WorkManager queue, upload management, `updateDance` (optional visibility), `setVisibility`, and `deleteDance`.
- `AnalyticsRepository`: learner and educator session listeners, Firestore mapping, learner display names, and reference video availability.
- `LeaderboardRepository`: leaderboard stats, ranking, friend list management.
- `FirebaseStreakRepository`, `AchievementRepository`, `PointsRepository`: read the streak, badges and points (written only by Cloud Functions).
- `SessionRewardsRepository`: observes `users/{uid}/sessionRewards/{sessionId}`, the server's result for one session.
- `UsernameRepository`: availability checks, a free suggestion from the display name, claiming/changing a username (one batch: `usernames/{new}`, `users/{uid}.username`, delete `usernames/{old}`), and prefix search for invites. Format rules live in `firebase/Usernames.kt`.
- `GroupsRepository`: groups, members, posts and replies (snapshot listeners), the user's invitations by username (send, accept as one batch with the membership, decline), creating a group with its owner membership and invite in one batch, joining by invite code via `groupInvites/{code}`, and group video uploads (720p compression, cover frame via `core/VideoMedia`).
- `WeeklyChallengeRepository`: reads this week's challenges; the first time in a week it calls the `weeklyChallenges` callable function to create them (via `FirebaseFunctions`).

### Infrastructure Layer

Shared infrastructure includes:

- `AppDb` and `DanceSessionDao` for Room.
- Hilt modules under `core/Database/di`.
- `AutoRetryUploaderService` (`firebase`): a plain injected class (not an Android `Service`) that re-uploads an account's unsynced sessions.
- Camera helpers (`CameraPreview`, which owns one `CameraBinder` and releases the camera when it leaves the screen, `CameraUseCaseFactory`) and video player helpers (`FeedPlayerFactory`, `VideoPlayerCache`, `SharedPlayerView`) under `core`.
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
- `SegmentColors.kt`: good/ok/bad colours for scoring, picking the light or dark shade from the app theme (surface luminance) rather than the phone setting.

Screens should read colours from `MaterialTheme.colorScheme` or `ChorevoTheme.colors` rather than hardcoding hex values; buttons on the accent use `onAccent` for text. Third-party brand colours in the share sheet are the exception.

Theme choice: `MainActivity` reads `AppUiState.themeMode` (Dark by default, Light, or System) and passes `darkTheme` to `ChorevoTheme`; it re-calls `enableEdgeToEdge` with `SystemBarStyle.auto { darkTheme }` so status and navigation bar icons match the app theme rather than the phone's. Both `values` and `values-night` window backgrounds are dark so launch matches the default. Settings shows the choice in an Appearance card (`themeMode_dark|light|system` tags).

Navigation chrome: `AppNavigationRoute` floats `AppBottomNavBar` (a 64dp translucent pill, 12dp above the system bar) over the content. Tabs are Home, Score (leaderboard), a raised gradient "+" Upload button, Groups (community), and Profile. The bar is hidden on `AUTH` and `DANCE_PRACTICE`. Regular screens receive extra bottom padding; the home feed is drawn full-bleed and reads `LocalFloatingBarInset` to keep its overlay above the bar, and sets `LocalFloatingBarState.hidden` while the pager is scrolling so the bar slides away. `res/values(-night)/themes.xml` set matching window backgrounds to avoid a white flash on launch.

## 4. Dependency Injection

Hilt modules live in `core/Database/di`:

- `FirebaseModule`: FirebaseAuth, FirebaseFirestore, FirebaseStorage, FirebaseFunctions and FirebaseMessaging singletons.
- `DatabaseModule`: Room `AppDb` (`app.db`, `fallbackToDestructiveMigration`) and `DanceSessionDao`.
- `DispatcherModule`: `@IoDispatcher` and `@DefaultDispatcher` `CoroutineDispatcher`s (qualifiers in `IoQualifier.kt`), injected into repositories and ViewModels instead of hardcoding `Dispatchers`.
- `WorkModule`: the app's `WorkManager`. `Chorevo` configures WorkManager with `HiltWorkerFactory`, so workers can be `@HiltWorker`s.
Repositories are bound through their `@Inject` constructors and are `@Singleton`.

ViewModels receive repositories through constructor injection. `VideoTemplateGenerationWorker` is a `@HiltWorker` with Firestore, Storage and `VideoPoseTemplateGenerator` injected. `ReminderScheduler`, `PushTokens` and `VideoPoseTemplateGenerator` are injected `@Singleton`s; `ChorevoMessagingService` is an `@AndroidEntryPoint`, and the reminder BroadcastReceivers get their dependencies through `NotificationEntryPoint` (a Hilt `@EntryPoint`).

## 5. Data Storage

### Firestore

Main collections:

- `users/{uid}`: profile records (uid, email, role, displayName, photoUrl, lastSeen, username).
- `users/{uid}/fcmTokens/{token}`: `{token, platform, updatedAt}` for push notifications, written by `notification/PushTokens` (registered by `AppViewModel` whenever the signed-in user changes) and on token refresh (`ChorevoMessagingService.onNewToken`). Sign-out deletes the device token (`FirebaseMessaging.deleteToken`), and the function removes tokens that stop working.
- `usernames/{handle}`: `{uid, createdAt}`; the document ID is the username, which makes it unique.
- `users/{uid}/danceLearningSessions/{sessionId}`: learner session results with a nested `analysis` map.
- `users/{uid}/friends/{friendUid}`: friend references (uid, displayName, photoUrl, addedAt).
- `users/{uid}/points/current`: totalPoints, lastSessionPoints, lastUpdated.
- `users/{uid}/achievements/current`: earnedBadges, lastUpdated.
- `users/{uid}/streaks/current`: weekStart, daysMask, totalStreakActivities, lastActivityDate, updatedAt.
- `users/{uid}/sessionRewards/{sessionId}`: the server's reward summary for one session.
- Points, achievements, streaks, weekly challenges and session rewards are written only by Cloud Functions (see Rewards Flow).
- `users/{uid}/weeklyChallenges/{weekStart}`: weekly challenge set and progress.
- `danceLibrary/{danceId}`: uploaded dance metadata, including `visibility` (`"public"` or `"private"`; a missing field reads as `"public"` through the `DanceLibraryModel` default), `coverFrameMs` (cover thumbnail position; missing reads as 1000), template status fields, and upload size details.
- `danceLibrary/{danceId}/likes/{uid}`: `{liked: true}`.
- `danceLibrary/{danceId}/comments/{commentId}`: commentId, uid, username, text, createdAt.
- `groups/{groupId}` with `members/{uid}`, `posts/{postId}` and `posts/{postId}/replies/{replyId}`: community groups (fields in `prd.md`). `memberCount` is written only by the `groupMemberCount` function.
- `groupInvites/{code}`: `{groupId, createdAt}` for private groups.
- `groupInvitations/{groupId}_{inviteeUid}`: invitations by username (groupId, groupName, inviteeUid, inviteeUsername, inviterUid, inviterName, createdAt). The group name is copied in because the invitee can't read a private group before joining.
- `communityPosts/{postId}` and replies: the old forum; no longer read by the app.
- `leaderboardStats/{uid}`: aggregate leaderboard stats (totalPracticeSessions, avgSimilarityScore, totalActiveDays, legacy streakDays, displayName, photoUrl, updatedAt), written only by Cloud Functions (`rewardsFromSession` and `leaderboardFromProfile` in `functions/index.js`, `functions/leaderboard.js`).

`DanceLibraryModel` exposes `isPrivate` (annotated `@get:Exclude` so it is not written back to Firestore) and `isVisibleTo(viewerUid)`, which returns true for public dances and for private dances viewed by their uploader. `isTemplateReady` is also excluded.

Important query patterns:

- Feed pages (`DanceLibraryRepository.loadFeedPage`, 12 per page): `danceLibrary where visibility == "public" and feedReady == true`, plus `danceTypeKey == key` for a chip and `searchTokens array-contains <first search word>` for a search, ordered by `shuffleKey` (For You), `createdAt desc` (New) or `trendingScore desc` (Trending), continued with `startAfter`. For You starts at a random seed (`shuffleKey >= seed`), then wraps once (`shuffleKey < seed`). Further search words are matched on the device (`matchesSearch`). All 12 combinations have composite indexes in `firestore.indexes.json`. `DanceLibraryListViewModel` appends pages (`loadMore`, triggered three videos from the end), keeps fetching while pages come back empty after search filtering (up to 5), and debounces search by 300 ms.
- Search (`DanceLibraryRepository.searchFeed`, used when the search box has text): two queries run in parallel — `searchTokens array-contains <first word>` in tab order (limit 40) and `searchTrigrams array-contains-any <first word's 3-letter pieces>` (limit 60, best effort) — then every candidate is scored with `searchScore` (each query word must prefix, sit inside, or be within `allowedTypos` edits of a word in the title, type, creator, description or style key) and sorted by score. One page, no paging. The trigram queries have their own composite indexes.
- Category chips: `meta/danceCategories` (`counts` and `labels` per `danceTypeKey`), maintained by Cloud Functions.
- Likes: the like count is the dance's `likeCount` (falling back to a `count()` query before the function has set it) and "liked" is one read of `likes/{uid}`. Like documents store `{liked, uid, createdAt}`.
- Liked dances: `collectionGroup("likes") where uid == me`, newest first, then one read per dance (private or deleted dances are skipped).
- Comments: `orderBy createdAt desc limit 20`; "Load more" continues with the same order and `startAfter(cursor)`.
- Creator uploads: `danceLibrary where educatorUid == uid orderBy createdAt desc`.
- Learner sessions: `users/{uid}/danceLearningSessions orderBy tsStart desc limit 30`.
- Creator analytics: collection group `danceLearningSessions where analysis.educatorUid == uid`, optionally also `analysis.danceId`.
- Weekly challenge generation: `users/{uid}/danceLearningSessions orderBy tsStart desc limit 40`.
- My groups: `collectionGroup("members") where uid == me`, then one read per group.
- Discover: `groups where visibility == "public" orderBy memberCount desc limit 50`, or with a search `orderBy nameLower` between the prefix and prefix + `\uf8ff` (both indexed).
- Group posts: `orderBy createdAt desc limit 100`; replies `orderBy createdAt asc limit 100`.
- Leaderboard: `leaderboardStats orderBy metric desc limit 100`.

`firestore.indexes.json` holds the composite indexes for creator uploads (`educatorUid`, `createdAt desc`) and filtered creator analytics (`analysis.educatorUid`, `analysis.danceId`).

### Firebase Storage

Main paths:

- `danceVideos/{uid}/{danceId}.mp4`
- `danceTemplates/{uid}/{danceId}.json` (built by `PoseTemplateStorage.jsonPath`; older templates live at `danceTemplates/{danceId}.json`)
- `profilePhotos/{uid}/profile.jpg`
- `danceThumbnails/{uid}/{danceId}.jpg` (cover thumbnails)
- `groupVideos/{uid}/{postId}.mp4` and `groupThumbnails/{uid}/{postId}.jpg` (group-only videos and their covers)

Storage rules validate content type and file size, and owner writes for videos, thumbnails and profile photos (see section 8).

### Room

Room database `app.db` (version 6, `exportSchema = false`) stores `DanceSessionEntity` in `dance_sessions`. `AppDb.MIGRATION_5_6` adds the `ownerUid` column; other schema changes fall back to destructive migration.

Key fields:

- `sessionId`
- `tsStart`, `tsEnd`, `durationMs`
- `activityName`, `activityType`
- `smoothnessScore`, `balanceScore`, `timingConsistencyScore`, `alignmentScore`
- `qualityFlag`
- `analysisJson`
- `synced`
- `ownerUid` (account that saved the session; blank for sessions saved before v6)

`DanceSessionDao` provides `insert` (replace on conflict), `latestFlow()`, `sinceFlow(sinceTs)`, `unsynced()`, `unsyncedFor(uid)` (that account's sessions plus legacy ones with no owner), and `markSynced(id)`. Production code uses `insert`, `unsyncedFor`, and `markSynced`.

### SharedPreferences

`PersonalBestStore` (`practice_personal_bests`) keeps each user's best alignment per dance on the device, used to trigger the personal-best celebration on the results screen.

### DataStore

`NotificationPreferencesDataStore` (`notification_preferences`) stores `push_enabled`, `notification_hour` (default 9), `notification_minute` (default 0), and `theme_mode` (`ThemeMode` key; missing means dark), and `onboarding_seen`. `AppViewModel` observes these flows (exposing `AppUiState.themeMode`) and `ReminderScheduler` reschedules alarms when settings change.

## 6. Key Flows

### Authentication Flow

1. `AppViewModel` asks `FirebaseAuthRepository.getCurrentUserProfile()`.
2. While restoring, `AppRouter` shows a loading route.
3. If no profile exists, `AuthRoute` is shown.
4. Email/password: `AuthViewModel` calls sign up or sign in on `FirebaseAuthRepository`.
5. Google: `AuthViewModel.requestGoogleSignIn` emits `AuthEvent.LaunchGoogleSignIn`; `AuthScreen` gets an ID token from `GoogleIdTokenProvider` (Credential Manager, `GetGoogleIdOption` with `default_web_client_id`, authorized accounts first, then all accounts); `signInWithGoogle` exchanges it through `GoogleAuthProvider`.
6. Every auth path, including restore in step 1, merge-writes `users/{uid}` and reads it back. The leaderboard copy of name and photo is updated by the `leaderboardFromProfile` function.
7. `AppViewModel.onAuthed(profile)` stores the profile and routes to home.

### Dance Upload Flow

1. Step 1 (Video): user picks a gallery video or records one. Step 2 (Cover): `CoverFramePicker` sets `UploadDraft.coverFrameMs`. Step 3 (Details): title, dance type, description.
2. Step 4 (Dancers), "Analyze Dancers" or the "analyze in the background" checkbox: `VideoUploadsViewModel.analyzeDancers` runs the injected `VideoPoseTemplateGenerator.generate` with the high-recall profile (8 fps, YOLO person detector, hands) on device; the user selects dancers and a default dancer and confirms.
3. Step 5 (Publish): `VideoUploadsViewModel.uploadDance` gathers profile and draft data (including visibility and `coverFrameMs`); it uses `generateTemplate = false` when the review was confirmed and `true` (background analysis) otherwise.
4. `VideoUploadsRepository` compresses the video (720p, up to 24 fps) and uploads the smaller of the original and compressed files.
5. Video uploads to `danceVideos/{uid}/{danceId}.mp4`.
6. Metadata writes to `danceLibrary/{danceId}`, then status fields are written (`saving`, `queued` or `skipped`, and `templateReviewStatus`).
7. Confirmed review: the confirmed template (filtered to the selected dancers and hand tracks) uploads to `danceTemplates/{uid}/{danceId}.json`, and the status becomes `ready` with `templateReviewStatus = "confirmed"` and `dancerCount`.
8. Background analysis: WorkManager enqueues `VideoTemplateGenerationWorker` as expedited work that runs in the foreground with an "Analysing your dance" notification (network required; inputs `dance_id`, `storage_path`, `template_fps`, `collect_hand_landmarks`).
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
4. `DanceARPracticeScreen` shows a 3-second countdown, then CameraX delivers frames on a background analysis thread (`CameraBinder`) to `PracticeViewModel`'s `LiveLandmarkDetector`, which runs Holistic Landmarker (pose + left/right hands, face ignored) and falls back to Pose Landmarker lite (body only).
5. `PoseSimilarityEvaluator` compares learner pose landmarks to the selected reference track; `HandSimilarityEvaluator` compares hands only when the template contains hand tracks.
6. On submit, a `DanceSessionEntity` and analysis payload are created (body-only scores; hand feedback is included in the feedback messages).
7. `DanceLibraryListViewModel.saveSessionAndUpload` inserts the session locally, uploads it to Firestore, and marks it synced. The server then awards points, badges, streak, challenges and leaderboard stats (Rewards Flow).
8. `AchievementViewModel.awaitSessionRewards` waits (up to 20 s) for the server's `sessionRewards/{sessionId}` and shows the results dialog with points, level-up and badges, then `WeeklyChallengeViewModel.onSessionRewards` shows any completed challenges. Offline, the dialog says the points follow when the session syncs.
9. On upload failure, the local session remains unsynced. `AutoRetryUploaderService.retryUnsyncedSessions(uid)`, called from `AppViewModel` after a session restore at launch and after every sign-in (`onAuthed`), re-uploads that account's sessions (`unsyncedFor`) and marks them synced; the server rewards each one as it arrives.

### Weekly Challenge Flow

1. The profile calls `WeeklyChallengeViewModel.loadForWeek`, which calls `getOrCreateCurrentWeek`. If the week has no document yet, it calls the `weeklyChallenges` callable function, which generates three challenges from the last 40 sessions and the dance types in the feed (`functions/rewards.js`, `generateChallenges`).
2. After each synced session, `rewardsFromSession` advances progress and credits challenge and bonus points; the app shows `WeeklyChallengeCelebrationDialog` for completions.

### Rewards Flow

1. The app uploads a practice session to `users/{uid}/danceLearningSessions/{sessionId}` with its scores (each 0..1, enforced by the rules) and the phone's time-zone offset. Sessions can't be changed afterwards; a retry of an uploaded session is skipped.
2. `rewardsFromSession` (Firestore trigger on create) runs once per session in a transaction: session points (alignment × 50 + timing × 30 + smoothness × 20, max 100), new badges, the weekly streak (one activity per local day, using the server's clock), weekly challenge progress and rewards, and `leaderboardStats/{uid}`. It writes the summary to `users/{uid}/sessionRewards/{sessionId}`.
3. Limits: at most 30 rewarded sessions per user per day; scores are clamped; a user's history from before server-side rewards is counted once (seeded from their existing sessions).
4. The app reads points, badges, streak, challenges and session rewards; `firestore.rules` deny client writes to all of them.

### Groups Flow

1. `GroupsViewModel.bind` observes the user's memberships (collection group `members`) and loads public groups for Discover.
2. Creating a group writes, in one batch, `groups/{id}`, the owner's `members/{uid}` (role `owner`) and, for a private group, `groupInvites/{code}` with a random 8-character code. The rules require all of these together.
3. Invite links `https://chorevo-dea05.web.app/join/<code>` (the QR code's content) are App Links (`/join/` in the manifest filter) and `chorevo://join/<code>`. `MainActivity` passes them through `ShareLinks.parseInviteCode` (`inviteCodeFromText`, which also reads bare codes) to `AppViewModel.openGroupInvite`, which waits for sign-in if needed and opens Groups with the join dialog filled in. Hosting serves `public/join.html` for `/join/**` when the app is missing.
4. Invitations by username: a member searches `usernames` by document-ID prefix (limit 10, then one profile read each) and writes `groupInvitations/{groupId}_{inviteeUid}`. `GroupsViewModel` is bound in `AppRouter`, so the invitee's invitations (`where inviteeUid == me`) badge the Groups tab anywhere in the app. Accepting writes `members/{uid}` and deletes the invitation in one batch; the members rule accepts a join when that invitation exists. `groupCleanup` deletes a deleted group's invitations. Creating an invitation also triggers `notifyGroupInvitation`; `ChorevoMessagingService` shows the push when the app is in the foreground (the system shows it otherwise), and tapping it calls `AppViewModel.openGroupInvitations` from `MainActivity` (held until sign-in if needed). `AppRouter` registers the device token and, on Android 13+, asks for `POST_NOTIFICATIONS` once per launch.
5. Joining a public group writes `members/{uid}`. Joining a private group reads `groupInvites/{code}` (get only; invites cannot be listed), then writes `members/{uid}` carrying the same `inviteCode`, which the rules compare with the group's.
6. The `groupMemberCount` function recounts members on every join or leave and writes `memberCount`.
7. Opening a group observes the group, the user's membership, members and posts. A post is text, a shared dance (`danceId`, title, cover and video URL copied from the user's public dance; "Open dance" calls `AppViewModel.openDanceById`), or a group video (compressed to 720p if larger, uploaded to `groupVideos/{uid}/{postId}.mp4` with a cover in `groupThumbnails/`).
8. Deleting a post removes its replies and, for the author, its files. Deleting a group triggers `groupCleanup`, which deletes the invite, every group file, and the whole subtree (`recursiveDelete`).

### Analytics Flow

1. `AnalyticsDashboardViewModel.setArgs` sets learner (`MY_DANCE`) or creator (`OTHER_ANALYTICS`) mode.
2. `AnalyticsRepository.observeSessions` chooses the appropriate Firestore listener.
3. Session documents are mapped into `DanceSessionSummary` (keys use `sessionId||docPath`), including `bodyFocus` counted from `analysis.feedbackMessages`; creator mode also reads learner display names from `users/{uid}`. Learner mode lists the latest 60 sessions.
4. Selecting a session observes detail by session ID or document path, and checks `danceLibrary/{danceId}` for reference video availability.
5. Nested `analysis` maps are parsed into `AnalysisData`.
6. UI renders summary metrics, video context, timeline points, and performance segments.

### Leaderboard Flow

1. After a successful session sync, `DanceLibraryListViewModel.saveSessionAndUpload` (and `AutoRetryUploaderService`) ticks the weekly streak.
2. Cloud Functions recompute `leaderboardStats/{uid}` whenever a session, the streak or the profile name/photo changes: session count and average alignment ("similarity") by aggregation queries, active days from the streak (capped at the session count), scores clamped to 0..1 (`functions/leaderboard.js`). Clients can't write the leaderboard.
3. Global scope observes the top 100 ordered by the selected metric. If the user isn't in it, `observeMyGlobalEntry` watches their own stats and computes their rank as 1 + a `count()` of users with a higher score, for the pinned "Your position" card.
4. Friends scope reads `leaderboardStats` for the current user plus `users/{uid}/friends` directly by document ID (`whereIn`, chunks of 30), so friends outside the top 100 appear.
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
- Hand frames are attached to dancer tracks by `HandTrackAssigner`, which matches each hand observation to the template frame with the same timestamp and the closest body pose.
- There are no face fields in the template.

### Template Generation

`VideoPoseTemplateGenerator` (an injected singleton) runs on the `@DefaultDispatcher`. It:

- reads frames with `MediaMetadataRetriever` (up to 120 seconds of video; at least 5 fps for the standard profile and 8 fps for high-recall),
- runs full-frame MediaPipe in VIDEO mode; per-crop inference uses IMAGE mode,
- **standard profile** (background worker): Pose Landmarker lite, plus full-frame Holistic (single person) when hands are requested. Holistic's pose becomes the frame's detection, and its hands are attached to that pose. If Holistic cannot initialize, a `handExtractionPartial` warning is added and generation continues body-only.
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
- `users/{uid}`: single profiles readable by signed-in users, never listable; written only by the owner with validated fields (no email, no role changes, names up to 60 characters, https photo URLs), a `username` only when `usernames/{username}` belongs to them after the write, and changing it must release the old handle; not deletable by clients (account deletion runs server-side). See SECURITY.md for the full model,
- `users/{uid}/fcmTokens/{token}`: owner only; the document ID must equal `token`; fields token, platform, updatedAt,
- `usernames/{handle}`: get by signed-in users, listed at most 20 at a time; created only by the claiming user with a well-formed handle (`^[a-z0-9_]{3,20}$`) in the batch that puts it on their profile (an existing handle can't be re-created, so names are unique); deleted only by its owner once their profile no longer uses it; no updates,
- `groupInvitations/{groupId}_{inviteeUid}`: read by the invitee or inviter; created by a group member as themselves, for an existing user who isn't a member yet and isn't the inviter, with the group's current name; no updates; deleted by the invitee, the inviter or the group owner,
- owner-only reads and writes for `points`, `achievements`, `streaks` (and the legacy `streak`), `weeklyChallenges`, and `friends` under `users/{uid}`,
- `danceLearningSessions`: owner writes; reads by the owner or by the educator named in `analysis.educatorUid` (including a collection-group rule); create/update require `analysis.danceId` to reference an existing dance whose `educatorUid` equals `analysis.educatorUid`; `analysis.danceId` and `analysis.educatorUid` cannot change on update; no deletes,
- `danceLibrary/{danceId}`: readable by signed-in users when `visibility == "public"`, otherwise only by the uploader (the rule reads the field directly, because a `get('visibility', 'public')` default lets list queries through unchecked; every dance has the field). List queries must filter on `visibility == "public"` or `educatorUid == auth.uid`. Create requires `educatorUid == auth.uid` and `danceId == docId`; update and delete only by the creator, with `educatorUid` and `danceId` unchanged. Clients may not set or change the server-managed fields (`likeCount`, `trendingScore`, `feedReady`, `danceTypeKey`, `searchTokens`, `searchTrigrams`, `shuffleKey`),
- `meta/{docId}`: readable by signed-in users, written only by Cloud Functions,
- `leaderboardStats/{uid}`: readable by any signed-in user, written only by Cloud Functions,
- `groups/{groupId}`: readable when `visibility == "public"` or the caller is a member (read directly, so list queries must filter on `visibility == "public"`). Create only together with the caller's owner membership (`existsAfter`), allowed fields only, `ownerUid == auth.uid`, name 3–60 with `nameLower` matching, description ≤ 500, an 8-character `inviteCode` exactly when private. Only the owner updates (name, nameLower, description only) or deletes; `memberCount` is server-only.
- `groups/{groupId}/members/{uid}`: readable when the group is; users only add themselves — as `owner` only while creating their own group (`getAfter`), otherwise as `member` of a public group or with the matching `inviteCode`. No updates. Members leave (except the owner); the owner removes others. A collection-group rule lets users list their own memberships.
- `groups/{groupId}/posts/{postId}` and `replies/{replyId}`: readable when the group is; created only by members as themselves, allowed fields only, body ≤ 1,500 (reply 1–500), `kind` text (non-empty body), dance (`danceId`) or video (`videoUrl`); no updates; delete by the author or the group owner.
- `groupInvites/{code}`: `get` by signed-in users, never listed; created only by the group owner with the code matching the group's; no updates or deletes from clients.
- `communityPosts/{postId}` and `replies/{replyId}` (old forum, no longer used by the app): readable by signed-in users; no new posts; delete only by the author.

- `danceLibrary/{danceId}/likes/{uid}`: readable when the dance is readable; users like (`liked: true`, optional `uid` equal to their own and `createdAt`) and unlike only as themselves, and only dances they can read; the dance owner may delete likes on their dance. A collection-group rule lets users query their own likes (`uid == auth.uid`).
- `danceLibrary/{danceId}/comments/{commentId}`: readable, and creatable, when the dance is readable; create validated (allowed fields only, author is the caller, ID matches, text 1–1,000 characters); no updates; delete by the author or the dance owner.

Storage rules enforce:

- owner-only writes for `danceVideos/{uid}/` and `groupVideos/{uid}/` (video, < 200 MB), `danceThumbnails/{uid}/`, `groupThumbnails/{uid}/` and `profilePhotos/{uid}/` (image, < 10 MB),
- signed-in reads for group videos and covers. Their download URLs are only stored in member-readable posts, but anyone holding a URL can play it (same as dance videos),
- no listing of any folder; direct reads by path only by the owner for dance and group videos/thumbnails (others play them through download URLs), signed-in reads for profile photos and templates; owners can delete their files; file names pinned to the IDs the app uses,
- `danceTemplates/{userId}/{fileName}`: written and deleted only by that user (JSON, < 20 MB); legacy `danceTemplates/{fileName}` files are read-only. Older app builds that still write to the legacy path can no longer save templates.
- deny-by-default fallback.

Video privacy:

- Enforced in Firestore rules (above), and also checked in the app (`DanceLibraryModel.isVisibleTo`; a permission-denied read of a shared private dance shows "This video is private.") and in the `danceShare` function.
- Rules tests: `rules-tests/` (`npm install`, then `npm test`; runs the Firestore emulator) covers dance privacy, likes and comments of private dances, the liked-videos query, server-managed fields and `meta`, groups (`groups.test.js`: creation with owner membership, public/private access, invite codes, posting, replies, leaving and removal, owner edits, my-groups query), and usernames, invitations and push tokens (`invitations.test.js`).
- Video files stay at token-protected Storage download URLs; making a dance private does not revoke an existing URL.
- The `danceShare` function uses the Admin SDK (bypasses rules) and only renders public, enabled dances.

## 9. Testing and CI

GitHub Actions has two workflows. `android.yml` runs JVM unit tests, emulator tests and the APK build. `firebase.yml` runs the Cloud Function tests (`functions/`) and the Firestore rules tests (`rules-tests/`, Firestore emulator) on every push, and on pushes to `main` deploys indexes, Storage rules, functions and Hosting, runs `functions/scripts/backfill.js`, then deploys the Firestore rules — using the `FIREBASE_SERVICE_ACCOUNT` secret (skipped when it is not set; setup in the README).

Test locations:

- Unit tests: `app/src/test`
- Instrumented tests: `app/src/androidTest`
- Cloud Function tests: `functions/test`

Unit tests (`app/src/test`):

- `analytics`: `AnalyticsDashboardViewModelTest`, `AnalyticsRepositoryTest`, `AnalyticsStatsTest` (overview numbers, trend, heatmap days, body focus)
- `core`: `AppUiStateTest`, `AppViewModelTest` (including share-link and group-invite handling, system back targets, theme mode, and onboarding)
- `dancelibrarylist`: `DanceLibraryModelTest`, `DanceLibraryRepositoryTest` (including video privacy), `DiscoveryTest` (category grouping, search, trending score, tab ordering), `DanceLibraryDiscoveryViewModelTest` (tabs, one-time like-count load, chips, search)
- `community`: `GroupModelsTest` (invite-code normalising, invite links and QR text, name limits, composer and state helpers), `GroupsViewModelTest` (binding, opening a group, posting by kind and membership, join by code, owner-only delete, invitations: accept, decline, search and send)
- `firebase`: `FirebaseAuthRepositoryTest`, `UsernamesTest` (normalising, limits, suggestions), `UsernameViewModelTest` (suggestion, availability, save)
- `leaderboard`: `LeaderboardRepositoryTest`, `LeaderboardViewModelTest`
- `posedetection`: `ByteTrackDancerTrackerTest`, `ConservativeFramePreprocessorTest`, `CropPoseGeometryTest`, `HandSimilarityEvaluatorTest`, `HandTrackAssignerTest`, `HolisticFrameLandmarksTest`, `HolisticFrameSelectorTest`, `MissingLandmarkInterpolatorTest`, `MultiPersonPoseTrackerTest`, `PersonDetectorFactoryTest`, `PoseDetectionMergerTest`, `PoseSimilarityEvaluatorTest`, `PoseTemplateJsonTest`, `TrackFragmentMergerTest`, `YoloPersonDetectionParserTest`
- `videouploads`: `VideoUploadTest` (upload ViewModel, dancer review, confirmed templates, visibility, cover frame)
- `weeklychallenge`: `WeeklyChallengeViewModelTest` (the challenge rules are tested server-side in `functions/test/rewards.test.js`)
- `danceactivity`: `SessionRewardsTest` (parsing the server summary; result dialog waits for it, or shows the pending state)

Instrumented tests (`app/src/androidTest`): `AnalyticsDashboardViewTest`, `DanceARPracticeScreenTest`, `DanceSessionDaoTest` (Room DAO), `DanceLibraryListViewTest`, `AuthScreenTest`, `OnboardingScreenTest`, `LeaderboardScreenTest`, `VideoUploadActivityScreenTest`.

Cloud Function tests: `functions/test/render.test.js` (link parsing, title rendering, HTML escaping, fallbacks for missing/disabled/private dances) and `functions/test/discovery.test.js` (search tokens, feedReady, trending score, derived-field updates without loops, category counts) and preview images, and `functions/test/groups.test.js` (group storage paths to clean up, invitation push message, dead-token cleanup), run with `npm test` in `functions/` and by the `firebase.yml` workflow.

GitHub Actions (`.github/workflows/android.yml`) runs on push and pull request to all branches, cancelling in-progress runs for the same ref, with JDK 17 (Temurin). Jobs run in order:

1. Unit tests: `testDebugUnitTest`.
2. Integration tests: `connectedDebugAndroidTest` on an API 29 x86_64 `pixel_5` emulator (2 GB AVD disk, 300 s boot timeout).
3. Build: `assembleDebug assembleRelease`.

Artifacts (kept 3 days; uploads never fail the build): `chorevo-debug-apk`, `chorevo-release-apk`, `chorevo-release-aab` (when signing secrets are set), and `instrumented-test-results` (only when emulator tests fail).

CI notes:

- `android-actions/setup-android` installs only `platform-tools`; the action's default also requests the removed legacy `tools` package, which fails.
- The emulator job frees runner disk space (removes .NET, GHC, Boost and CodeQL) and caps the AVD disk at 2 GB so the emulator can create its userdata partition.

Deployment:

- Hosting and the share function are deployed manually with `cd functions && npm install && cd .. && firebase deploy --only functions,hosting`. The project is on the Blaze plan.
- Firestore and Storage rules are deployed separately; keep `firestore.rules` and `storage.rules` in sync with the deployed rulesets.
- The functions artifact repository has a cleanup policy that deletes old build images.

## 10. Known Architectural Considerations

- Navigation is a custom state machine (`AppUiState.screen` plus an animated `when` in `AppRouter`); the Navigation Compose dependencies are declared but unused. This may become hard to scale if nested flows grow.
- Package segments `ViewModel`, `Repository` and `Database` are mixed-case, `videouploads/repository` is lowercase, and the router files declare `package com.routes`. New packages should be lowercase and match their folders.
- Room has `exportSchema = false` and falls back to destructive migration for schema changes without an explicit migration, which loses unsynced sessions; add a `Migration` for each schema change (as `MIGRATION_5_6` does).
- Leaderboard values are computed by Cloud Functions from sessions, but practice scores themselves are computed on the phone; high-trust ranking would need server-side pose scoring.
- Threading: template generation, template JSON parsing and serialising use the `@DefaultDispatcher`; file and media work uses the `@IoDispatcher` or `Dispatchers.IO`; live pose detection runs on the camera analysis thread. Firebase calls are already asynchronous.
- `VideoUploadsRepository.uploadDance` and `GroupsRepository`'s post upload still take a `Context` from the caller (for compression and the cover frame) instead of the application context; `VideoCompressor`, `VideoMedia` and `VideoPlayerCache` are still `object`s.
- Firestore indexes in `firestore.indexes.json` must stay aligned with analytics and creator upload queries.
- Search is built on Firestore: candidates come from word-prefix tokens and trigrams, and are scored on the device, so it handles prefixes and small typos but not synonyms, stemming or relevance learned from usage. Results are capped (40 exact + 60 typo candidates per search). A hosted search service (Algolia, Typesense) would be the next step for a much larger library.
- Older uploads have no `thumbnailUrl` (nothing generates thumbnails for existing videos); their grids decode a video frame and their link previews use the default image.
- Like counts and trending scores are written by Cloud Functions, so they lag a like by a second or two, and trending ages every 6 hours.
- Older app builds query the feed with `enabled == true` only; once the privacy rules are deployed that query is denied, so those builds show an empty feed until updated.
- App Links: `public/.well-known/assetlinks.json` is UTF-8 and deployed with Hosting (the old `**/.*` ignore rule that skipped it is gone). Verification only succeeds for builds signed by a certificate whose SHA-256 fingerprint is listed there; CI builds are signed with a throwaway debug key per run, so links from CI APKs open the chooser or browser.
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
Firebase Storage upload to danceTemplates/{uid}/{danceId}.json
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
- `HandTrackAssigner`: attaches hand observations to template tracks by matching the body pose each hand was detected with (same timestamp, closest pose).
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

- The standard background profile is effectively single-dancer when hands are requested.
- Live practice does not fall back to Pose Landmarker when Holistic returns an empty pose.

### 11.10 Performance Boundaries

- High-recall analysis runs on-device from `VideoUploadsViewModel` via `withContext(Dispatchers.Default)` (not an injected dispatcher and not WorkManager). The WorkManager worker runs the standard profile.
- Neither profile runs on the main thread.
- Frame sampling is at least 5 fps for the standard profile and 8 fps for high-recall.
- The maximum number of tracked dancers is 8.
- Crop bitmaps and the preprocessor's copy are recycled; the frame bitmap from `getFrameAtTime` is not recycled (partially implemented).
- Detector, tracker, MediaPipe, and frame resources are closed in `finally` blocks.
