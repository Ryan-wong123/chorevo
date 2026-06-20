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
- Pose analysis: MediaPipe Tasks Vision, including the active pose + hands Holistic Landmarker upgrade

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
- `pose_landmarker_lite.task` is the default fast body-only fallback model.
- `holistic_landmarker.task` is expected at `app/src/main/assets/holistic_landmarker.task` for the active pose + hands live practice upgrade and hand-enabled uploaded-video template generation.
- `pose_landmarker_full.task` is the body-only fallback for high-recall upload analysis when Holistic fails or hand-enabled generation is disabled.
- Pose and hand landmark arrays are flattened as repeating `x, y, z, visibility` values.
- Hand landmarks are 21 points per hand; each detected hand should flatten to `21 * 4` values.
- Use `PoseTemplateJson` for template serialization and keep old pose-only templates readable.
- Close `PoseLandmarkerHelper` and `HolisticLandmarkerHelper` instances after processing.
- Do not store, render, score, or upload face landmarks for this task.
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

## Active Codex Task: Pose + Hands Holistic Upgrade

Implement this before doing unrelated AI refactors.

Goal: use `app/src/main/assets/holistic_landmarker.task` to add hand/finger awareness to live AR practice while keeping the existing body pose scoring path working.

### Required Behavior

- Prefer Holistic Landmarker for live practice when the model asset exists.
- Consume only body pose, left hand, and right hand landmarks.
- Ignore all face landmarks and face-related outputs.
- Pass body pose landmarks into the existing `PoseSimilarityEvaluator` unchanged.
- Add hand scoring as a separate optional evaluator, not as a rewrite of body scoring.
- Keep final score body-only when the selected reference template has no hand data.
- Keep old pose-only templates readable and practice-ready.
- Add optional hand template data in a backward-compatible way.

### Files to Inspect First

```bash
rg "DanceARPracticeScreen|PoseLandmarkerHelper|PoseSimilarityEvaluator|PoseTemplate|PoseTemplateJson" app/src/main/java
rg "VideoPoseTemplateGenerator|MultiPersonPoseTracker|templateStatus" app/src/main/java
rg "tasks-vision|mediapipe" app/build.gradle.kts gradle/libs.versions.toml
```

### Suggested Implementation Order

1. Add `HolisticFrameLandmarks`, hand template models, and flattening helpers.
2. Add `HolisticLandmarkerHelper` that returns only pose + left/right hand data.
3. Update `DanceARPracticeScreen` to use holistic pose output for the existing body score.
4. Update `PoseTemplateJson` for optional hand tracks while preserving old JSON compatibility.
5. Add `HandSimilarityEvaluator` and combine scores only when hand reference data exists.
6. Add tests for conversion, JSON compatibility, missing hands, and body-only fallback.

### Done Criteria

- The app builds with `holistic_landmarker.task` in assets.
- Existing pose-only dances still work.
- Body score behavior remains available even if hands are missing.
- Hand feedback appears only when reference hand data exists.
- No face data appears in app models, template JSON, Room, Firestore, analytics payloads, logs, or UI.

## Multi-Dancer AI Upgrade Instructions

When implementing the multi-dancer upgrade, use the current project as the base. Do not replace the existing MediaPipe pipeline unless a task explicitly asks for it.

### Preferred Implementation Order

1. Add data models and interfaces for person detection, tracking, preprocessing, and quality reporting.
2. Implement same-frame duplicate removal and track-fragment merging around the existing MediaPipe tracker.
3. Add low-light preprocessing as an optional high-recall setting.
4. Add the YOLO detector behind a `PersonDetector` interface.
5. Add ByteTrack-style tracking or an adapter behind a `DancerTracker` interface.
6. Add per-dancer crop MediaPipe landmark extraction and remapping. For hand-enabled multi-dancer generation, this means YOLO detection/tracking followed by `holistic_landmarker.task` on each padded dancer crop.
7. Update upload UI/ViewModel state to expose dancer count and warnings.
8. Add unit tests for duplicate removal, fragment merging, remapping, and interpolation.

### Constraints

- Preserve existing Firebase paths and template JSON compatibility.
- Keep live practice fast; do not force YOLO onto the live camera path in the first pass.
- For high-recall uploaded-video templates that need hands/fingers, use `person_detector_yolo.tflite` to locate/track dancers and `holistic_landmarker.task` to extract pose + left/right hands from each padded crop.
- Keep `pose_landmarker_full.task` as the fallback body-only crop estimator when Holistic fails or hand mode is disabled.
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
- Hand-enabled multi-dancer high-recall mode uses YOLO + Holistic as the preferred path and Pose Landmarker Full only as body-only fallback.
- Duplicate same-frame detections are reduced before tracking.
- Fragmented tracks can be merged after processing.
- Low-light preprocessing can be enabled/disabled.
- Missing model assets fail gracefully.
- Tests cover the pure math/logic portions.
- Documentation is updated if new assets, Gradle dependencies, or setup steps are added.

