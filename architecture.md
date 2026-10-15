# Chorevo Architecture

## 1. Overview

Chorevo is a single-module native Android application:

- Module: `:app`
- Package: `com.example.chorevo`
- UI: Jetpack Compose and Material 3
- App architecture: MVVM-style presentation with repositories for data access
- Dependency injection: Hilt
- Local data: Room and DataStore
- Cloud data: Firebase Auth, Firestore, Firebase Storage
- Background work: WorkManager
- Camera/video: CameraX and Media3
- Pose analysis: MediaPipe Tasks Vision, using Pose Landmarker for body-only fallback flows and Holistic Landmarker for pose + hand/finger live practice plus hand-enabled uploaded-video template generation
- Web share links: Firebase Hosting (`chorevo-dea05.web.app`) rewriting `/dance/**` to the `danceShare` Cloud Function in `functions/` (Node.js 22, `firebase-functions` v2 HTTPS, Admin SDK)

The app starts in `Chorevo`, initializes Firebase/App Check, then `MainActivity` hosts the Compose tree and calls `AppRouter`.

## 2. Runtime Entry Points

### `Chorevo`

`Chorevo` is the `Application` class. It:

- enables Hilt through `@HiltAndroidApp`,
- initializes Firebase,
- installs the Firebase App Check debug provider for debug builds,
- provides custom WorkManager configuration with a single-thread executor.

### `MainActivity`

`MainActivity` is the launcher activity. It:

- enables edge-to-edge display,
- applies `ChorevoTheme`,
- hosts the root Compose `Surface`,
- renders `AppRouter`,
- handles share-link intents in `onCreate` and `onNewIntent` (`launchMode="singleTask"`): `ShareLinks.parseDanceId` extracts the dance ID and passes it to `AppViewModel.openDanceById`.

Supported links (declared as intent filters in `AndroidManifest.xml`):

- `https://chorevo-dea05.web.app/dance/{danceId}` and `https://chorevo-dea05.firebaseapp.com/dance/{danceId}` (`autoVerify="true"`; App Links verification still needs `assetlinks.json`),
- `https://chorevo.app/dance/{danceId}` (legacy links),
- `chorevo://dance/{danceId}` (custom scheme used by the web page to hand off into the app).

The manifest also declares a `<queries>` block so the share sheet can detect installed social apps and resolve send/SMS/email/browser intents on Android 11+.

### `AppRouter`

`AppRouter` coordinates top-level app routing from `AppViewModel` state:

- waits for auth restore,
- routes unauthenticated users to auth,
- creates shared Hilt ViewModels for the dance library and uploads,
- wraps main content in `AppNavigationRoute`,
- switches between home, practice, upload, leaderboard, profile, settings, and auth screens,
- passes `AppUiState.sharedDance` to `HomeRoute` so a dance opened from a share link is pinned first in the feed,
- shows share-link errors (missing, disabled, private) as a snackbar.

The app currently uses a simple `Screen` enum for top-level navigation rather than a full Navigation Compose graph for every screen.

## 3. Layered Structure

### Presentation Layer

Compose screens and routes live in feature packages. They observe ViewModel state and emit callbacks.

Examples:

- `AuthScreen`
- `DanceLibraryListView`
- `DanceARPracticeScreen`
- `VideoUploadActivityScreen`
- `AnalyticsDashboardView`
- `LeaderboardScreen`
- `UserProfileScreen`
- `SettingsScreen`
- `ShareBottomSheet` (`core/share/ShareSheet.kt`): shared share sheet used by the home feed and the profile liked-videos feed.
- `VideoVisibilitySelector` (`videouploads/VideoVisibilitySelector.kt`): Public/Private chips used by the upload form and the Edit dialog.

### State and Coordination Layer

ViewModels own screen state and user actions:

- `AppViewModel`: global auth/profile, selected top-level screen, selected dance, reminder preferences, and share-link handling (`openDanceById`, pending link while signed out, `sharedDance`, `deepLinkError`).
- `DanceLibraryListViewModel`: library loading, template preparation, practice session submission.
- `VideoUploadsViewModel`: upload draft (including visibility), dancer analysis, upload progress, edit/delete flows, and per-upload visibility toggles (`setVisibility`, `updatingVisibilityIds`).
- `AnalyticsDashboardViewModel`: session list and selected session detail.
- `LeaderboardViewModel`: leaderboard scope, metric, friend actions.
- `AuthViewModel`, `ProfileViewModel`, `StreakViewModel`, `AchievementViewModel`: feature-specific state.

ViewModels expose read-only `StateFlow` and mutate immutable UI state data classes.

### Data Layer

Repositories isolate Firebase, Room, and Storage APIs:

- `FirebaseAuthRepository`: sign up, sign in, sign out, profile restore, profile photo upload.
- `DanceLibraryRepository`: library query (excludes private dances), liked dances (excludes other users' private dances), `getDanceById(danceId, viewerUid)` for share links, template download, local session insert, session upload.
- `VideoUploadsRepository`: video compression/upload, dance metadata write (including `visibility`), template queue, upload management, `updateDance` (optional visibility) and `setVisibility`.
- `AnalyticsRepository`: learner and educator session listeners and Firestore mapping.
- `LeaderboardRepository`: leaderboard stats, ranking, friend list management.
- `FirebaseStreakRepository`, `AchievementRepository`, `PointsRepository`: progress and rewards.

### Infrastructure Layer

Shared infrastructure includes:

- `AppDb` and `DanceSessionDao` for Room.
- Hilt modules under `core/Database/di`.
- Camera helpers under `core`.
- Video player helpers under `core`.
- Notification scheduling under `notification`.
- Pose detection and scoring under `posedetection`.
- `ShareLinks` under `core/share`: single source of truth for building and parsing share links.

## 3a. Design System

Theme code lives in `ui/theme`:

- `Color.kt`: the Chorevo "stage" palette (brand teal primary, magenta accent, violet tertiary, dark ink and light mist surfaces).
- `Theme.kt`: `ChorevoTheme { }` applies fixed dark and light colour schemes (dynamic colour is off by default so the brand looks the same on every device), the type scale and shapes. `ChorevoTheme.colors` exposes brand tokens Material has no slot for: `accent`/`onAccent`, `like`, `success`, the teal-to-magenta `brandGradient`, frosted `glass`/`glassBorder`, and `onMedia` for content over video.
- `Type.kt`: Sora (headings, titles, numbers) and Plus Jakarta Sans (body, labels), bundled in `res/font` under the SIL Open Font License (`licenses/`).
- `Shape.kt`: rounded `ChorevoShapes` (10–28dp), `PillShape` for buttons and the bottom bar, and a 4dp-grid `Spacing` scale.
- `SegmentColors.kt`: good/ok/bad colours for scoring.

Screens should read colours from `MaterialTheme.colorScheme` or `ChorevoTheme.colors` rather than hardcoding hex values; buttons on the accent use `onAccent` for text. Third-party brand colours in the share sheet are the exception.

Navigation chrome: `AppNavigationRoute` floats `AppBottomNavBar` (a translucent pill with a raised gradient Upload button) over the content. Regular screens receive extra bottom padding; the home feed is drawn full-bleed and reads `LocalFloatingBarInset` to keep its overlay above the bar, and sets `LocalFloatingBarState.hidden` while the pager is scrolling so the bar slides away. `res/values(-night)/themes.xml` set matching window backgrounds to avoid a white flash on launch.

## 4. Dependency Injection

Hilt provides repositories and infrastructure from modules under `core/Database/di`:

- `FirebaseModule`: Firebase Auth, Firestore, Storage.
- `DatabaseModule`: Room database and DAO.
- `DispatcherModule` / `IoQualifier`: coroutine dispatcher wiring.
- `RepositoryModule`: repository singletons.

Most ViewModels are `@HiltViewModel` and receive repositories through constructor injection.

## 5. Data Storage

### Firestore

Main collections:

- `users/{uid}`: profile records.
- `users/{uid}/danceLearningSessions/{sessionId}`: learner session results.
- `users/{uid}/friends/{friendUid}`: friend references.
- `users/{uid}/achievements/current`: earned badges.
- `users/{uid}/streaks/...` and `users/{uid}/streak/...`: streak data.
- `danceLibrary/{danceId}`: uploaded dance metadata, including `visibility` (`"public"` or `"private"`; a missing field reads as `"public"` through the `DanceLibraryModel` default).
- `leaderboardStats/{uid}`: aggregate leaderboard stats.

`DanceLibraryModel` exposes `isPrivate` (annotated `@get:Exclude` so it is not written back to Firestore) and `isVisibleTo(viewerUid)`, which returns true for public dances and for private dances viewed by their uploader.

Important query patterns:

- Enabled library items: `danceLibrary where enabled == true`, then filtered client-side to ready templates and non-private dances. The query does not filter on `visibility` because older documents have no such field.
- Creator uploads: `danceLibrary where educatorUid == uid orderBy createdAt desc`.
- Learner sessions: `users/{uid}/danceLearningSessions orderBy tsStart desc`.
- Creator analytics: collection group `danceLearningSessions where analysis.educatorUid == uid`.
- Filtered creator analytics: collection group with `analysis.educatorUid` and `analysis.danceId`.
- Leaderboard: `leaderboardStats orderBy metric desc limit 100`.

### Firebase Storage

Main paths:

- `danceVideos/{uid}/{danceId}.mp4`
- `danceTemplates/{danceId}.json`
- `profilePhotos/{uid}/profile.jpg`
- `danceThumbnails/{uid}/{fileName}`

Storage rules validate owner writes, content type, and file size.

### Room

Room stores `DanceSessionEntity` in `dance_sessions`.

Key fields:

- `sessionId`
- `tsStart`, `tsEnd`, `durationMs`
- `activityName`, `activityType`
- `smoothnessScore`, `balanceScore`, `timingConsistencyScore`, `alignmentScore`
- `qualityFlag`
- `analysisJson`
- `synced`

`DanceSessionDao` supports insert, latest session flow, time-window queries, unsynced lookup, and marking synced.

### DataStore

`NotificationPreferencesDataStore` stores reminder enablement and selected reminder time. `AppViewModel` observes these flows and `ReminderScheduler` reschedules alarms when settings change.

## 6. Key Flows

### Authentication Flow

1. `AppViewModel` asks `FirebaseAuthRepository.getCurrentUserProfile()`.
2. While restoring, `AppRouter` shows a loading route.
3. If no profile exists, `AuthRoute` is shown.
4. Sign up/sign in writes or reads `users/{uid}`.
5. `AppViewModel.onAuthed(profile)` stores the profile and routes to home.

### Dance Upload Flow

1. User selects or records a video in upload UI.
2. Optional high-recall dancer analysis runs through `VideoPoseTemplateGenerator`.
3. `VideoUploadsViewModel.uploadDance` gathers profile and draft data.
4. `VideoUploadsRepository` compresses video when useful.
5. Video uploads to `danceVideos/{uid}/{danceId}.mp4`.
6. Metadata writes to `danceLibrary/{danceId}`.
7. If a confirmed template exists, JSON uploads to `danceTemplates/{danceId}.json` and status becomes `ready`.
8. Otherwise, WorkManager enqueues `VideoTemplateGenerationWorker`.
9. Worker downloads the uploaded video, generates a template, writes template JSON, and updates Firestore status.

### Share Flow

1. The user taps Share on a dance in the home feed or the profile liked-videos feed.
2. `ShareBottomSheet` builds a `ShareContent` (dance ID, title, educator) and the link from `ShareLinks.danceLink`.
3. On open, the sheet checks which social apps are installed (`PackageManager`) and loads their launcher icons.
4. Tapping an installed app sends an `ACTION_SEND` intent targeted at that package (Facebook receives the bare link because it ignores free text).
5. When the app is not installed, WhatsApp, Facebook, Telegram and X open their web share URLs; otherwise the system chooser is used.
6. Copy link writes to the clipboard; SMS and Email use `ACTION_SENDTO`; More opens `Intent.createChooser`.

### Share Link Open Flow

In the app:

1. `MainActivity` receives the link and calls `AppViewModel.openDanceById(danceId)`.
2. If auth has not resolved or no one is signed in, the ID is parked and consumed after sign-in.
3. `DanceLibraryRepository.getDanceById(danceId, viewerUid)` loads the dance and fails for missing, disabled, private-to-someone-else, or not-ready dances.
4. On success, `AppUiState.sharedDance` is set and the screen becomes `HOME`; on failure, `deepLinkError` is shown as a snackbar.
5. `DanceLibraryListView(pinnedDance = ...)` places the shared dance first and scrolls the pager to it. Practice is only entered through Start Practice.
6. `AppViewModel.backToHome` and logout clear `sharedDance`.

On the web:

1. Firebase Hosting rewrites `/dance/**` to the `danceShare` function (`firebase.json`).
2. `functions/index.js` parses the ID (`render.parseDanceId`) and reads `danceLibrary/{id}` with the Admin SDK.
3. `functions/render.js` fills `functions/dance.html` with the dance title, description, video, Open Graph and Twitter tags. All values are HTML-escaped.
4. Missing, disabled and private dances render generic text with no title or video.
5. The page sets `Cache-Control: public, max-age=300, s-maxage=600`.
6. In the browser, the page tries `intent://dance/{id}#Intent;scheme=chorevo;package=com.example.chorevo;...` on Android, with a fallback back to the page (`?app=missing`).

### Video Visibility Flow

1. The upload form's `VideoVisibilitySelector` updates `UploadDraft.visibility` (default `public`).
2. `VideoUploadsRepository.uploadDance` writes it into the `danceLibrary/{danceId}` document.
3. In the profile, each upload card shows its visibility and a Make private / Make public button that calls `VideoUploadsViewModel.setVisibility`, which merges `{visibility}` into the document and updates the listed item in place.
4. The Edit dialog includes the same selector; `saveEdit` passes it to `updateDance`.
5. Readers apply the rule: the feed hides all private dances, liked lists and share links use `isVisibleTo(viewerUid)`, and the web share page treats private dances as unavailable.

### Practice Flow

1. Learner chooses a dance from home.
2. `DanceLibraryListViewModel.preparePracticeItem` loads or caches the pose template.
3. `AppViewModel.pickDance` routes to practice.
4. `DanceARPracticeScreen` uses CameraX and MediaPipe landmarks. The upgraded live path should use Holistic Landmarker to read pose + left/right hand landmarks while ignoring face output.
5. `PoseSimilarityEvaluator` compares learner pose landmarks to the selected reference track, and an optional hand evaluator compares finger/hand landmarks only when the selected template contains hand data.
6. On submit, a `DanceSessionEntity` and analysis payload are created.
7. `DanceLibraryRepository.saveSessionAndUpload` inserts the session locally, uploads to Firestore, and marks it synced.
8. On success, streak and leaderboard stats refresh.
9. On failure, the local session remains available for retry.

### Analytics Flow

1. `AnalyticsDashboardViewModel.setArgs` sets learner or creator mode.
2. `AnalyticsRepository.observeSessions` chooses the appropriate Firestore listener.
3. Session documents are mapped into `DanceSessionSummary`.
4. Selecting a session observes detail by session ID or document path.
5. Nested `analysis` maps are parsed into `AnalysisData`.
6. UI renders summary metrics, video context, timeline points, and performance segments.

### Leaderboard Flow

1. Practice completion calls `LeaderboardRepository.refreshUserStats`.
2. Repository reads profile and session count, computes average similarity, and writes `leaderboardStats/{uid}`.
3. Leaderboard UI observes the top 100 ordered by selected metric.
4. Friends scope combines all stats with `users/{uid}/friends`.
5. Ranking is computed client-side with tie handling.

### Reminder Flow

1. User toggles reminders or changes time.
2. `AppViewModel` writes preferences to DataStore.
3. `ReminderScheduler` reschedules the alarm.
4. `ReminderReceiver` posts the reminder notification.
5. `BootReceiver` restores scheduling after reboot, locked boot, or package replacement.

## 7. Pose Pipeline

### Models

MediaPipe model assets live in `app/src/main/assets`:

- `pose_landmarker_lite.task`: default fast body-only model and fallback for live practice.
- `pose_landmarker_full.task`: optional stronger body-only fallback for uploaded-video template generation when holistic hand extraction fails or hand mode is disabled.
- `holistic_landmarker.task`: pose + hands model for live practice and hand-enabled uploaded-video template generation. The asset is expected at `app/src/main/assets/holistic_landmarker.task`. Face landmarks from this model must not be stored, scored, or rendered for this feature.

### Template Format

`PoseTemplate` contains:

- `fps`
- legacy `frames`
- multi-person `tracks`
- `defaultTrackId`
- optional `selectionPreview`

Each landmark frame is flattened as repeating `x, y, z, visibility` values.

#### Optional Hand Landmark Extension

New templates may include optional hand/finger landmarks without breaking older pose-only templates. The extension should be additive and nullable/default-empty in Kotlin and JSON:

```kotlin
data class HandLandmarkFrame(
    val timestampMs: Long,
    val leftHand: List<Float> = emptyList(),   // 21 landmarks * x,y,z,visibility
    val rightHand: List<Float> = emptyList()   // 21 landmarks * x,y,z,visibility
)

data class HandTrack(
    val trackId: String,
    val frames: List<HandLandmarkFrame> = emptyList()
)
```

Recommended `PoseTemplate` addition:

```kotlin
val handTracks: List<HandTrack> = emptyList()
```

Rules:

- Keep existing `frames`, `tracks`, `defaultTrackId`, and `selectionPreview` unchanged.
- Hand landmark arrays use the same flattened `x, y, z, visibility` format as pose arrays.
- Left and right hands each contain 21 MediaPipe hand landmarks when detected.
- Missing hands are represented by an empty list, not fabricated landmarks.
- Do not add face fields to the template for this task.

### Template Generation

`VideoPoseTemplateGenerator`:

- reads frames with `MediaMetadataRetriever`,
- runs MediaPipe in VIDEO mode,
- for body-only templates, continues using Pose Landmarker,
- when hand/finger templates are requested and `holistic_landmarker.task` is present, runs Holistic Landmarker and stores only pose + left/right hand landmarks,
- ignores all face landmarks even if the Holistic result contains them,
- for high-recall multi-dancer hand-enabled templates, runs YOLO person detection/tracking first, crops each dancer with padding, then runs Holistic Landmarker on each crop,
- remaps both pose and hand landmarks from crop coordinates back to full-frame normalized coordinates,
- falls back to `pose_landmarker_full.task` for body-only generation if crop Holistic inference fails,
- optionally runs crop passes in high-recall mode,
- merges duplicate detections,
- tracks people across frames with `MultiPersonPoseTracker`,
- returns a serializable `PoseTemplate`.

### Similarity Evaluation

`PoseSimilarityEvaluator`:

- selects reference frames by timestamp,
- normalizes learner and reference poses around hips or shoulders,
- optionally mirrors the reference X axis,
- compares stable upper/lower body joints,
- remains the source of truth for the main body alignment score,
- maps error to score with a Gaussian curve,
- computes timing consistency from score variance,
- computes smoothness from error changes,
- emits downsampled timeline points and labeled performance segments.

#### Optional Hand/Finger Similarity Evaluation

Add a separate `HandSimilarityEvaluator` instead of mixing hand math into `PoseSimilarityEvaluator`.

Recommended behavior:

- Use hand scoring only when both learner and reference frames have at least one matching hand.
- Normalize each hand relative to its wrist landmark so scoring focuses on finger shape instead of absolute screen position.
- Prefer scale-normalizing by palm size or average MCP distance when enough landmarks are present.
- Score left and right hands independently, then average available hands.
- If no reference hand data exists, keep the final score body-only and show no hand penalty.
- If reference hand data exists but learner hands are missing for many frames, surface feedback such as `Try to keep your hands visible`.
- Do not score face, facial expression, or face mesh data.

Recommended final score composition:

```text
if hand reference is available:
    finalScore = bodyScore * 0.85 + handScore * 0.15
else:
    finalScore = bodyScore
```

#### Live Practice Pose + Hands Flow

```text
Camera frame
↓
HolisticLandmarkerHelper using app/src/main/assets/holistic_landmarker.task
↓
HolisticFrameLandmarks(pose, leftHand, rightHand)
↓
PoseSimilarityEvaluator for existing body score
↓
Optional HandSimilarityEvaluator for finger/hand score
↓
Combined score + feedback
```

Implementation constraints:

- Keep `PoseLandmarkerHelper` available as a fallback if holistic initialization fails or the asset is missing.
- Keep the existing pose overlay and scoring behavior working with old pose-only templates.
- Add hand overlay/feedback only after the data path is stable.
- Do not persist or render face landmarks.

## 8. Security Architecture

Firestore rules enforce:

- signed-in access for protected data,
- owner-only profile/session/friend/streak/achievement writes,
- creator-only dance metadata create/update/delete,
- educator read access to learner sessions linked by `analysis.educatorUid`,
- immutable linkage for session `analysis.danceId` and `analysis.educatorUid` on update,
- no deletes for learner sessions or leaderboard stats.

Storage rules enforce:

- owner-only writes for dance videos, thumbnails, and profile photos,
- size and content-type limits,
- authenticated reads for profile photos and templates,
- public reads for uploaded dance videos and thumbnails,
- deny-by-default fallback.

Video privacy:

- Enforced in the app (`DanceLibraryRepository`, `DanceLibraryModel.isVisibleTo`) and in the `danceShare` function, not in Firestore rules. `danceLibrary` documents remain readable by any signed-in user, so a private dance's metadata can still be read directly through the Firebase API.
- Video files stay at token-protected Storage download URLs; making a dance private does not revoke an existing URL.
- The `danceShare` function uses the Admin SDK (bypasses rules) and only renders public, enabled dances.

## 9. Testing and CI

Test locations:

- Unit tests: `app/src/test`
- Instrumented tests: `app/src/androidTest`

Current coverage includes:

- Firebase auth repository tests,
- dance library repository tests,
- analytics repository and ViewModel tests,
- leaderboard repository and ViewModel tests,
- pose template JSON and multi-person tracking tests,
- video upload tests,
- Room DAO tests,
- Compose screen tests for auth, library, upload, AR practice, analytics, and leaderboard,
- share-link handling in `AppViewModelTest`,
- video privacy in `DanceLibraryModelTest`, `DanceLibraryRepositoryTest` and `VideoUploadTest`,
- share page rendering in `functions/test/render.test.js` (run with `npm test` in `functions/`; not part of GitHub Actions).

GitHub Actions runs:

- `testDebugUnitTest`
- `connectedDebugAndroidTest` on an API 29 emulator
- `assembleDebug assembleRelease`
- debug APK upload as a CI artifact

CI notes:

- `android-actions/setup-android` installs only `platform-tools`; the action's default also requests the removed legacy `tools` package, which fails.
- The emulator job frees runner disk space (removes .NET, GHC, Boost and CodeQL) and caps the AVD disk at 2 GB so the emulator can create its userdata partition.

Deployment:

- Hosting and the share function are deployed manually with `cd functions && npm install && cd .. && firebase deploy --only functions,hosting`. The project must be on the Blaze plan.
- The functions artifact repository has a cleanup policy that deletes old build images.

## 10. Known Architectural Considerations

- Navigation is intentionally simple but may become hard to scale if nested flows grow.
- Some package names use mixed-case segments such as `ViewModel`; future packages should prefer lowercase.
- Room has `exportSchema = false`, which is convenient for coursework/prototype speed but weak for production migrations.
- Some official leaderboard values are client-written; high-trust ranking would need server-side verification.
- Template generation can be compute-heavy and should remain isolated from UI recomposition and main-thread work.
- Firestore indexes in `firestore.indexes.json` must stay aligned with analytics and creator upload queries.
- Video privacy is client-enforced; rules-level enforcement needs a `visibility` backfill and a `visibility == "public"` feed query.
- App Links are declared with `autoVerify` but not verified until `/.well-known/assetlinks.json` is hosted. The `firebase.json` hosting `ignore` rule `**/.*` would also skip a `.well-known` folder and must be adjusted first.
- The share function and Hosting are not deployed by CI, so web changes must be deployed manually after merging.

## 11. Multi-Dancer AI Upgrade Architecture

### 11.1 High-Level Design

The current MediaPipe-only path remains valid for simple videos and as a body-only fallback. The preferred high-recall upload path for multi-dancer + hands/fingers adds person detection and tracking before per-dancer Holistic landmark extraction.

```text
Uploaded video
↓
Frame extraction at 5–8 fps
↓
Optional low-light preprocessing
↓
YOLO person detection using person_detector_yolo.tflite
↓
Same-frame duplicate person-box removal
↓
ByteTrack dancer ID assignment
↓
Per-dancer crop generation with padding for hands/fingers
↓
Holistic Landmarker on each crop using holistic_landmarker.task
↓
Extract pose + leftHand + rightHand only
↓
Discard face landmarks and face-related outputs
↓
Remap pose and hand landmarks to original full-frame coordinates
↓
Track merge and cleanup
↓
Optional short-gap interpolation
↓
PoseTemplateJson serialization with optional handTracks
↓
Firebase Storage upload to danceTemplates/{danceId}.json
```

Fallback path:

```text
person_detector_yolo.tflite
↓
tracked dancer crop
↓
pose_landmarker_full.task
↓
body-only pose template
```

Use the fallback only when Holistic initialization fails, crop Holistic inference fails, or hand-enabled generation is disabled.

### 11.2 Component Responsibilities

#### `posedetection` package

Add or extend focused classes here:

- `PersonDetector`: interface for person bounding-box detection.
- `YoloPersonDetector`: Android inference implementation for YOLO person detection.
- `DetectionBox`: normalized bounding box, confidence, class label, and source metadata.
- `DancerTracker`: interface for assigning persistent dancer IDs across frames.
- `ByteTrackDancerTracker`: default tracker implementation or adapter.
- `TrackedDancerBox`: tracker output containing `trackId`, frame index, timestamp, bbox, confidence, and state.
- `FramePreprocessor`: brightness, contrast, gamma, optional denoise/sharpen pipeline.
- `CropPoseEstimator`: crops tracked dancers, calls `PoseLandmarkerHelper` for body-only fallback, and remaps pose landmarks.
- `CropHolisticEstimator` or equivalent mode in `CropPoseEstimator`: crops tracked dancers with extra padding, calls `HolisticLandmarkerHelper`, extracts pose + left/right hand landmarks, discards face data, and remaps pose/hand landmarks.
- `PoseDetectionMerger`: removes same-frame duplicates from full-frame/crop/person detections.
- `TrackFragmentMerger`: merges split tracks after the video has been processed.
- `MissingLandmarkInterpolator`: fills short gaps only when confidence and timing make it safe.
- `PoseAnalysisQualityReporter`: produces warnings such as low light, many missing frames, or possible duplicate tracks.

#### `videouploads` package

- Continue to own upload flow, WorkManager scheduling, and creator-facing status.
- Pass high-recall options into the template generator.
- Surface detected dancer count, warnings, and review state in upload UI state.

#### `dancelibrarylist` and `danceactivity` packages

- Keep practice preparation and scoring compatible with existing `PoseTemplate` output.
- Do not introduce YOLO or heavy uploaded-video dependencies into live practice code unless explicitly required.

### 11.3 Data Models

```kotlin
data class DetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classId: Int,
    val label: String = "person",
    val source: DetectionSource = DetectionSource.Yolo
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

Use normalized coordinates in the range `0f..1f` for detector boxes and landmarks unless a class explicitly documents pixel coordinates.

### 11.4 Inference Backends

Prefer a small interface boundary so the app can switch deployment format later:

```kotlin
interface PersonDetector : AutoCloseable {
    suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox>
}
```

Possible implementations:

- `TfliteYoloPersonDetector`
- `OnnxYoloPersonDetector`
- `NcnnYoloPersonDetector`
- `NoOpPersonDetector` for tests or fallback

Start with only one real backend. Do not build all three at once unless required.

### 11.5 Detection and Tracking Rules

- Run YOLO person detection first in high-recall upload mode.
- Filter for class `person` only.
- Apply non-maximum suppression or IoU-based merging before tracker update.
- Use ByteTrack-style matching to keep low-confidence detections useful for association.
- Keep lost tracks alive for about `fps * 3` frames for dance videos.
- Avoid creating a new track until recent lost tracks have been considered.
- After processing, merge fragmented tracks when their time ranges, positions, body sizes, and movement direction are compatible.

### 11.6 Crop Landmark Estimation Rules

- Expand each tracked person box with padding before cropping so hands, fingers, and feet are not cut off.
- Clamp crops to the source frame.
- For hand-enabled multi-dancer templates, run `holistic_landmarker.task` on each padded crop.
- Extract only pose, leftHand, and rightHand landmarks from the Holistic result. Discard face landmarks and face-related outputs.
- Remap crop pose and hand landmarks back to full-frame normalized coordinates:

```text
fullX = cropLeft + cropX * cropWidth
fullY = cropTop + cropY * cropHeight

fullHandX = cropLeft + handX * cropWidth
fullHandY = cropTop + handY * cropHeight
```

- Preserve `z` and visibility values from MediaPipe.
- Mark landmarks as missing/low-confidence when the crop is invalid or Holistic fails.
- If Holistic initialization or crop Holistic inference fails, fall back to `pose_landmarker_full.task` for body-only crop pose estimation.

### 11.7 Low-Light Preprocessing Rules

`FramePreprocessor` may apply:

- brightness increase,
- contrast adjustment,
- gamma correction,
- denoise,
- mild sharpening.

Preprocessing should be applied before YOLO detection and before MediaPipe crop estimation in high-recall mode. Keep settings conservative because over-enhancement can harm detection.

### 11.8 Duplicate and Fragment Cleanup

There are two cleanup stages:

1. Same-frame duplicate removal before tracking.
2. Cross-time track-fragment merging after tracking.

Same-frame duplicates can be removed using IoU and center distance:

```text
keep higher-confidence pose/box
remove lower-confidence detection if IoU > 0.45 or center distance < 0.08
```

Fragmented tracks can be merged when:

- their time ranges do not significantly overlap,
- the gap is less than around 3 seconds,
- the last known position of the first track is close to the first position of the second track,
- body size is similar,
- movement direction is plausible.

### 11.9 Fallback Strategy

If YOLO model assets are missing, unsupported, or fail at runtime:

1. Log a safe warning without exposing private paths or media URLs.
2. Fall back to the current body-only MediaPipe full-frame/crop high-recall logic using `pose_landmarker_full.task` when available.
3. Surface an analysis warning such as `personDetectorUnavailable`.
4. Do not fail the entire upload unless no pose template can be generated.

### 11.10 Performance Boundaries

- Keep high-recall YOLO + Holistic crop analysis, and its body-only fallback, in WorkManager or an injected IO/default dispatcher.
- Do not run high-recall upload processing on the main thread.
- Use frame sampling around 5 fps by default and 8 fps for high-recall analysis.
- Limit max tracked dancers to a configurable value, for example 8.
- Recycle bitmaps or use scoped allocations carefully to avoid memory pressure.
- Close detector, tracker, MediaPipe, and frame resources deterministically.

