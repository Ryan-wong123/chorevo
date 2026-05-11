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
- Pose analysis: MediaPipe Tasks Vision

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
- renders `AppRouter`.

### `AppRouter`

`AppRouter` coordinates top-level app routing from `AppViewModel` state:

- waits for auth restore,
- routes unauthenticated users to auth,
- creates shared Hilt ViewModels for the dance library and uploads,
- wraps main content in `AppNavigationRoute`,
- switches between home, practice, upload, leaderboard, profile, settings, and auth screens.

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

### State and Coordination Layer

ViewModels own screen state and user actions:

- `AppViewModel`: global auth/profile, selected top-level screen, selected dance, reminder preferences.
- `DanceLibraryListViewModel`: library loading, template preparation, practice session submission.
- `VideoUploadsViewModel`: upload draft, dancer analysis, upload progress, edit/delete flows.
- `AnalyticsDashboardViewModel`: session list and selected session detail.
- `LeaderboardViewModel`: leaderboard scope, metric, friend actions.
- `AuthViewModel`, `ProfileViewModel`, `StreakViewModel`, `AchievementViewModel`: feature-specific state.

ViewModels expose read-only `StateFlow` and mutate immutable UI state data classes.

### Data Layer

Repositories isolate Firebase, Room, and Storage APIs:

- `FirebaseAuthRepository`: sign up, sign in, sign out, profile restore, profile photo upload.
- `DanceLibraryRepository`: library query, template download, local session insert, session upload.
- `VideoUploadsRepository`: video compression/upload, dance metadata write, template queue, upload management.
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
- `danceLibrary/{danceId}`: uploaded dance metadata.
- `leaderboardStats/{uid}`: aggregate leaderboard stats.

Important query patterns:

- Enabled library items: `danceLibrary where enabled == true`.
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

### Practice Flow

1. Learner chooses a dance from home.
2. `DanceLibraryListViewModel.preparePracticeItem` loads or caches the pose template.
3. `AppViewModel.pickDance` routes to practice.
4. `DanceARPracticeScreen` uses CameraX and MediaPipe pose landmarks.
5. `PoseSimilarityEvaluator` compares learner landmarks to the selected reference track.
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

- `pose_landmarker_lite.task`: default fast model.
- `pose_landmarker_full.task`: optional stronger model for high-recall upload analysis.

### Template Format

`PoseTemplate` contains:

- `fps`
- legacy `frames`
- multi-person `tracks`
- `defaultTrackId`
- optional `selectionPreview`

Each landmark frame is flattened as repeating `x, y, z, visibility` values.

### Template Generation

`VideoPoseTemplateGenerator`:

- reads frames with `MediaMetadataRetriever`,
- runs MediaPipe in VIDEO mode,
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
- maps error to score with a Gaussian curve,
- computes timing consistency from score variance,
- computes smoothness from error changes,
- emits downsampled timeline points and labeled performance segments.

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
- Compose screen tests for auth, library, upload, AR practice, analytics, and leaderboard.

GitHub Actions runs:

- `testDebugUnitTest`
- `connectedDebugAndroidTest` on an API 29 emulator
- `assembleDebug assembleRelease`
- debug APK upload as a CI artifact

## 10. Known Architectural Considerations

- Navigation is intentionally simple but may become hard to scale if nested flows grow.
- Some package names use mixed-case segments such as `ViewModel`; future packages should prefer lowercase.
- Room has `exportSchema = false`, which is convenient for coursework/prototype speed but weak for production migrations.
- Some official leaderboard values are client-written; high-trust ranking would need server-side verification.
- Template generation can be compute-heavy and should remain isolated from UI recomposition and main-thread work.
- Firestore indexes in `firestore.indexes.json` must stay aligned with analytics and creator upload queries.

## 11. Multi-Dancer AI Upgrade Architecture

### 11.1 High-Level Design

The current MediaPipe-only path remains valid for simple videos. The upgraded high-recall upload path adds person detection and tracking before per-dancer landmark extraction.

```text
Uploaded video
↓
Frame extraction at 5–8 fps
↓
Optional low-light preprocessing
↓
YOLO person detection
↓
Same-frame duplicate person-box removal
↓
ByteTrack dancer ID assignment
↓
Per-dancer crop generation
↓
MediaPipe Pose Landmarker full/heavy on each crop
↓
Landmark remapping to original frame coordinates
↓
Track merge and cleanup
↓
Optional short-gap interpolation
↓
PoseTemplateJson serialization
↓
Firebase Storage upload to danceTemplates/{danceId}.json
```

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
- `CropPoseEstimator`: crops tracked dancers, calls `PoseLandmarkerHelper`, and remaps landmarks.
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

### 11.6 Crop Pose Estimation Rules

- Expand each tracked person box with padding before cropping so hands/feet are not cut off.
- Clamp crops to the source frame.
- Run MediaPipe on the crop using `pose_landmarker_full.task` when available.
- Remap crop landmarks back to full-frame normalized coordinates:

```text
fullX = cropLeft + cropX * cropWidth
fullY = cropTop + cropY * cropHeight
```

- Preserve `z` and visibility values from MediaPipe.
- Mark landmarks as missing/low-confidence when the crop is invalid or the pose model fails.

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
2. Fall back to current MediaPipe full-frame/crop high-recall logic.
3. Surface an analysis warning such as `personDetectorUnavailable`.
4. Do not fail the entire upload unless no pose template can be generated.

### 11.10 Performance Boundaries

- Keep high-recall YOLO + MediaPipe crop analysis in WorkManager or an injected IO/default dispatcher.
- Do not run high-recall upload processing on the main thread.
- Use frame sampling around 5 fps by default and 8 fps for high-recall analysis.
- Limit max tracked dancers to a configurable value, for example 8.
- Recycle bitmaps or use scoped allocations carefully to avoid memory pressure.
- Close detector, tracker, MediaPipe, and frame resources deterministically.

