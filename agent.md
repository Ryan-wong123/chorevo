# Agent Instructions for Chorevo

## Project Snapshot

Chorevo is a single-module native Android app for AI-assisted dance learning.

- Workspace root: `C:\Users\USER\Documents\chorevo`
- Android module: `:app`
- Package: `com.example.chorevo`
- Primary language: Kotlin
- UI: Jetpack Compose + Material 3
- Architecture: MVVM-style ViewModels with repositories
- DI: Hilt
- Cloud backend: Firebase Auth, Firestore, Firebase Storage, Firebase App Check
- Local data: Room + DataStore
- Background work: WorkManager
- Camera/video: CameraX + Media3
- Pose analysis: MediaPipe Tasks Vision

Read these docs before making substantial changes:

- `prd.md`: product requirements and feature scope
- `architecture.md`: app layers, data flows, Firebase paths, pose pipeline
- `coding_guidelines.md`: project coding style and contribution rules
- `README.md`: setup, build, Firebase, CI, and operational notes

## First Steps for Any Task

1. Check the current git state with `git status --short`.
2. Read the files directly related to the requested change.
3. Prefer `rg` / `rg --files` for search.
4. Keep edits scoped to the requested feature or bug.
5. Do not revert unrelated user changes.
6. If docs and source disagree, trust source first and update docs only when asked or clearly necessary.

## Build and Test Commands

Windows:

```bash
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
gradlew.bat assembleDebug assembleRelease
```

macOS/Linux:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug assembleRelease
```

Notes:

- Instrumentation tests require an emulator or physical Android device.
- CI uses JDK 17 and an API 29 emulator.
- The app compiles with SDK 36 and min SDK 26.
- Kotlin/JVM target is currently 11.

## Architecture Rules

- Keep app-wide routing in `core/routes` and `AppViewModel`.
- Keep feature logic in its feature package:
  - `analytics`
  - `danceactivity`
  - `dancelibrarylist`
  - `firebase`
  - `leaderboard`
  - `notification`
  - `posedetection`
  - `videouploads`
  - `ui/theme`
- Keep Firebase, Room, and Storage calls inside repositories or infrastructure classes.
- Keep Compose screens mostly stateless and pass callbacks down from route/screen owners.
- Expose ViewModel state as read-only `StateFlow`.
- Use immutable UI state data classes and `copy`.
- Use WorkManager for deferrable background work.
- Do not put long-running pose, video, upload, or database work on the main thread.

## Firebase and Data Rules

Important Firestore collections:

- `users/{uid}`
- `users/{uid}/danceLearningSessions/{sessionId}`
- `users/{uid}/friends/{friendUid}`
- `users/{uid}/achievements/current`
- `danceLibrary/{danceId}`
- `leaderboardStats/{uid}`

Important Storage paths:

- `danceVideos/{uid}/{danceId}.mp4`
- `danceTemplates/{danceId}.json`
- `profilePhotos/{uid}/profile.jpg`
- `danceThumbnails/{uid}/{fileName}`

When writing Firebase code:

- Preserve ownership fields such as `uid`, `educatorUid`, and `danceId`.
- Keep writes compatible with `firestore.rules` and `storage.rules`.
- Use `SetOptions.merge()` for partial document updates when appropriate.
- Store large pose templates in Storage, not inline Firestore documents.
- Avoid logging private media URLs, auth details, or sensitive profile data.

## Pose and Media Rules

- MediaPipe model assets live in `app/src/main/assets`.
- `pose_landmarker_lite.task` is the default fast model.
- `pose_landmarker_full.task` may be used for high-recall upload analysis when present.
- Pose landmark arrays are flattened as repeating `x, y, z, visibility` values.
- Use `PoseTemplateJson` for template serialization.
- Close `PoseLandmarkerHelper` instances after processing.
- Be careful changing `PoseSimilarityEvaluator`; scoring changes affect analytics, achievements, and leaderboards.

## Local Persistence Rules

- Completed practice sessions should be inserted into Room before cloud upload.
- `DanceSessionEntity.synced = false` means retry is still needed.
- Do not drop local session data because Firebase upload failed.
- Add a migration plan before changing Room schema in production-oriented work.

## Testing Expectations

Add or update focused tests when changing:

- ViewModel state transitions,
- repository Firestore/Storage mapping,
- Room DAO queries,
- pose template serialization,
- pose scoring math,
- upload flow behavior,
- analytics parsing,
- leaderboard ranking,
- notification preference behavior.

Use existing test patterns:

- `MainDispatcherRule` for coroutine ViewModel tests.
- MockK for collaborators.
- Turbine for Flow assertions.
- Android instrumentation tests for Compose and Room runtime behavior.

## Editing Guidance

- Use existing app patterns before introducing new abstractions.
- Prefer small, direct changes over broad refactors.
- Avoid renaming packages or moving files unless the task requires it.
- Avoid changing Firebase rules without considering matching repository queries and indexes.
- Avoid changing Gradle/plugin versions unless requested.
- Keep documentation in sync when behavior, setup, architecture, or Firebase paths change.

## Handoff Checklist

Before finishing a coding task, report:

- files changed,
- behavior changed,
- tests/builds run,
- tests/builds not run and why,
- any remaining risks or follow-up work.

For documentation-only tasks, a build is usually unnecessary.

## Multi-Dancer AI Upgrade Instructions

When implementing the multi-dancer upgrade, use the current project as the base. Do not replace the existing MediaPipe pipeline unless a task explicitly asks for it.

### Preferred Implementation Order

1. Add data models and interfaces for person detection, tracking, preprocessing, and quality reporting.
2. Implement same-frame duplicate removal and track-fragment merging around the existing MediaPipe tracker.
3. Add low-light preprocessing as an optional high-recall setting.
4. Add the YOLO detector behind a `PersonDetector` interface.
5. Add ByteTrack-style tracking or an adapter behind a `DancerTracker` interface.
6. Add per-dancer crop MediaPipe landmark extraction and remapping.
7. Update upload UI/ViewModel state to expose dancer count and warnings.
8. Add unit tests for duplicate removal, fragment merging, remapping, and interpolation.

### Constraints

- Preserve existing Firebase paths and template JSON compatibility.
- Keep live practice fast; do not force YOLO onto the live camera path in the first pass.
- Keep heavy uploaded-video analysis off the main thread.
- If model files are missing, provide fallback behavior instead of crashing.
- Treat occluded landmarks honestly: missing or inferred landmarks must not become high-confidence ground truth.
- Do not upload raw learner practice videos as part of this work.

### Files to Inspect First

Search for these before editing:

```bash
rg "VideoPoseTemplateGenerator|MultiPersonPoseTracker|PoseTemplate|PoseTemplateJson|PoseLandmarkerHelper|PoseSimilarityEvaluator"
rg "VideoUploadsViewModel|VideoUploadsRepository|WorkManager|templateStatus"
```

### Definition of Done

- Multi-dancer high-recall mode can run without breaking the existing MediaPipe-only path.
- Duplicate same-frame detections are reduced before tracking.
- Fragmented tracks can be merged after processing.
- Low-light preprocessing can be enabled/disabled.
- Missing model assets fail gracefully.
- Tests cover the pure math/logic portions.
- Documentation is updated if new assets, Gradle dependencies, or setup steps are added.

