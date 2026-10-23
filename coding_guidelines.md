# Chorevo Coding Guidelines

## 1. Project Defaults

- Use Kotlin for Android app code.
- Use Jetpack Compose for UI.
- Use Hilt for dependency injection.
- Use coroutines and `StateFlow` for async state.
- Use Firebase repositories for cloud data access.
- Use Room for local persisted practice sessions.
- Use WorkManager for deferrable background work such as template generation.
- Keep code in the existing single Android module unless there is a strong reason to split modules.

## 2. Package Organization

Keep new code near the feature it supports:

- `core`: app-wide navigation, shared UI helpers, camera/player infrastructure, database setup, Hilt modules.
- `analytics`: analytics models, repository, ViewModel, and dashboard UI.
- `danceactivity`: AR practice, scoring submission, sessions, streaks, achievements, and points.
- `dancelibrarylist`: dance feed/list models, repository, ViewModel, and UI.
- `firebase`: authentication, profile, retry upload service, and related screens.
- `leaderboard`: leaderboard models, repository, ViewModel, and UI.
- `notification`: reminder settings, scheduling, receivers, channels, and settings UI.
- `posedetection`: MediaPipe integration, pose template models, serialization, tracking, and similarity evaluation.
- `videouploads`: upload UI, upload ViewModel/state, compression, repository, and template generation worker.
- `ui/theme`: colors, typography, and app theme.

When adding a feature, prefer a small model/repository/ViewModel/UI grouping inside the relevant feature package over placing feature behavior in `MainActivity` or `AppViewModel`.

## 3. Naming and File Style

- Use PascalCase for classes, composables, data classes, and enum names.
- Use camelCase for functions, properties, and local variables.
- Package names are all lowercase and match their folders (`viewmodel`, `repository`, `database`). Keep it that way for new packages.
- Name ViewModels as `<Feature>ViewModel`.
- Name immutable screen state as `<Feature>UiState`.
- Name repositories as `<Feature>Repository`.
- Keep one major public type per file when practical.
- Keep comments useful and specific. Avoid comments that only restate the function name.

## 4. Compose Guidelines

- Composables should be stateless when practical. Pass state and callbacks down from the route/screen level.
- Collect flows in UI with lifecycle-aware APIs such as `collectAsStateWithLifecycle`.
- Keep business logic out of composables. Put decisions in ViewModels, repositories, or small pure helper functions.
- Use Material 3 components and the app theme rather than one-off colors/styles.
- Represent loading, empty, success, and error states explicitly.
- Keep side effects in `LaunchedEffect`, ViewModels, or lifecycle-aware wrappers.
- Avoid creating long-lived players, camera resources, or MediaPipe helpers directly in recomposing UI without cleanup.

## 5. ViewModel Guidelines

- Expose immutable `StateFlow` from ViewModels:

```kotlin
private val _uiState = MutableStateFlow(MyUiState())
val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()
```

- Update state with `copy` on immutable data classes.
- Launch async work in `viewModelScope`.
- Convert repository `Result` values into user-facing UI state in the ViewModel.
- Keep Android `Context` out of ViewModels unless it is injected as `@ApplicationContext` or needed for APIs that require it.
- Do not expose mutable collections from state.

## 6. Repository Guidelines

- Repositories own external data access: Firebase Auth, Firestore, Storage, Room, and network-like APIs.
- Repositories should return `Result<T>`, suspend functions, or `Flow<T>` depending on the use case.
- Firestore listeners should be wrapped in `callbackFlow` and must call `awaitClose { registration.remove() }`.
- Validate required fields before writing data that must satisfy Firestore rules.
- Keep Firestore collection/path names centralized in the repository or a small constants object when reuse grows.
- Avoid putting UI strings in repositories except low-level exception messages.

## 7. Coroutine and Dispatcher Guidelines

- Use suspend functions for one-shot async operations.
- Use `Flow` for live Firestore or local database streams.
- Use `withContext(Dispatchers.IO)` for blocking or IO-heavy local work when no injected dispatcher is available.
- Prefer injected dispatchers for code that needs deterministic unit tests.
- Never block the main thread during video processing, compression, Firebase waits, Room writes, or MediaPipe processing.

## 8. Firebase Guidelines

- Keep Firestore writes compatible with `firestore.rules`.
- Use `SetOptions.merge()` for partial metadata updates where overwriting full documents would be risky.
- Store large pose templates in Storage, not inline Firestore documents.
- Preserve ownership fields such as `uid`, `educatorUid`, and `danceId` during updates.
- Do not trust client-only scores for future official ranking or monetized features without server-side verification.
- Keep `google-services.json` environment-specific and avoid committing secret backend credentials.

## 9. Local Persistence Guidelines

- Practice sessions should be inserted into Room before cloud upload.
- Preserve the `synced` flag semantics: `false` means retry is still needed.
- Add Room migrations when changing schema in production. The current database has `exportSchema = false`; change that before relying on long-term migrations.
- Keep DAO methods small and explicit.

## 10. Pose and Video Guidelines

- Use `PoseLandmarkerHelper` as the boundary around body-only MediaPipe setup and cleanup.
- Use `HolisticLandmarkerHelper` as the boundary around pose + hands MediaPipe setup and cleanup.
- Close all MediaPipe helpers when processing is complete.
- Keep pose templates serializable through `PoseTemplateJson`.
- Treat all landmark arrays as `x, y, z, visibility` groups.
- For hands, expect 21 landmarks per hand, flattened as `21 * 4` values.
- Do not store, render, score, or upload face landmarks for the pose + hands upgrade.
- Keep scoring changes covered by tests because small math changes can affect analytics, achievements, and leaderboards.
- Avoid storing raw learner practice video unless a future privacy review explicitly requires it.

## 11. Error Handling

- Use `runCatching` or `try/catch` at repository boundaries.
- Surface actionable messages in UI state.
- Do not swallow errors silently when they affect user progress.
- For retryable background work, use WorkManager retry and persist a status field users can understand.
- Keep logs useful but avoid logging sensitive tokens, emails, or private media URLs unnecessarily.

## 12. Testing Guidelines

- Put JVM unit tests under `app/src/test`.
- Put emulator/device tests under `app/src/androidTest`.
- Use `MainDispatcherRule` for ViewModel coroutine tests that touch `Dispatchers.Main`.
- Use MockK for Firebase/repository collaborators.
- Use Turbine for Flow assertions where timing matters.
- Add tests for:
  - ViewModel state transitions,
  - repository mapping/parsing,
  - pose template serialization,
  - scoring math,
  - Room DAO behavior,
  - critical Compose flows.

Run before merging:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug assembleRelease
```

On Windows:

```bash
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
gradlew.bat assembleDebug assembleRelease
```

## 13. Pull Request Checklist

- The feature follows existing package boundaries.
- UI state covers loading, success, empty, and error paths.
- Firebase writes satisfy Firestore and Storage rules.
- Long-running work does not block the main thread.
- Any new Room schema change includes a migration plan.
- Any new media, camera, or MediaPipe resource is cleaned up.
- Unit or instrumentation tests cover risky logic.
- The app builds locally or in CI.

## 14. Multi-Dancer AI Coding Guidelines

### Interfaces First

Add interfaces before concrete detector implementations so the project can switch between TensorFlow Lite, ONNX Runtime Mobile, or NCNN later without rewriting the pose pipeline.

Recommended boundaries:

```kotlin
interface PersonDetector : AutoCloseable
interface DancerTracker
interface FramePreprocessor
interface CropPoseEstimator
```

### Coordinate Rules

- Use normalized coordinates `0f..1f` for boxes and landmarks unless explicitly documented.
- Convert to pixels only at image crop boundaries.
- Clamp crop rectangles before creating bitmaps.
- Keep a helper for crop-to-full-frame landmark remapping and test it independently.

### Detection Rules

- Filter object detections to person class only.
- Apply confidence thresholds conservatively; high-recall mode may use lower thresholds but must rely on tracking/merging cleanup.
- Use non-maximum suppression or IoU-based duplicate removal before updating tracks.
- Keep threshold values centralized in a config data class rather than scattered literals.

### Tracking Rules

- Track IDs should be stable strings or ints, but template serialization should not depend on object identity.
- Keep lost tracks alive for short occlusions.
- Prefer matching recent lost tracks before creating a new dancer ID.
- Merge track fragments after full-video processing, not during UI rendering.

### Crop Landmark Rules

- Expand dancer boxes with padding to avoid cutting off hands, fingers, and feet.
- For hand-enabled uploaded-video high-recall analysis, use `person_detector_yolo.tflite` to locate/track dancers and `holistic_landmarker.task` on each padded dancer crop to extract pose + left/right hands.
- Use `pose_landmarker_full.task` only as the body-only fallback when Holistic initialization/crop inference fails or hand mode is disabled.
- Keep `pose_landmarker_lite.task` as the live camera body-only fallback.
- Remap both pose and hand landmarks from crop coordinates back to full-frame normalized coordinates.
- Always close `PoseLandmarkerHelper`, `HolisticLandmarkerHelper`, and detector resources.

### Low-Light Rules

- Keep brightness, contrast, and gamma preprocessing optional.
- Do not over-enhance frames by default.
- Report low-confidence or low-light warnings through analysis quality metadata.

### Testing Rules for the Upgrade

Add JVM tests for:

- IoU and center-distance duplicate removal,
- crop coordinate remapping,
- track gap and fragment merge decisions,
- missing-landmark interpolation limits,
- fallback behavior when detector assets are unavailable.

Use instrumentation tests only where Android bitmap/model runtime behavior is required.



## 15. Pose + Hands Holistic Coding Guidelines

This section is the active guidance for adding hand/finger awareness to live AR practice and hand-enabled uploaded-video template generation.

### Model and Helper Rules

- The holistic model asset path is `app/src/main/assets/holistic_landmarker.task`.
- Add `HolisticLandmarkerHelper` in `posedetection` rather than configuring MediaPipe directly inside Compose.
- The helper should expose app-owned result models instead of leaking MediaPipe result types deep into scoring/UI code.
- Set face-related optional outputs such as blendshapes or transformation matrices to disabled when the API supports it.
- Even if the Holistic result contains face landmarks, do not pass them outside the helper.
- Fall back to the existing pose-only path if holistic initialization fails.

### App-Owned Result Model

Recommended model:

```kotlin
data class HolisticFrameLandmarks(
    val pose: List<Float> = emptyList(),
    val leftHand: List<Float> = emptyList(),
    val rightHand: List<Float> = emptyList()
)
```

Rules:

- `pose` should remain compatible with existing `PoseSimilarityEvaluator` input.
- `leftHand` and `rightHand` should be empty when the hand is not detected.
- Do not include a `face` property in this model for this feature.

### Template Compatibility Rules

- Keep existing pose-only templates valid.
- Add hand template fields as optional/default-empty fields only.
- Do not change the existing flattened pose frame format.
- Do not make practice fail if `handTracks` is absent.
- Do not add face fields to `PoseTemplate` or `PoseTemplateJson`.

### Hand Scoring Rules

- Create a separate `HandSimilarityEvaluator`; do not overload `PoseSimilarityEvaluator` with hand-specific normalization.
- Normalize hand landmarks relative to the wrist before comparing finger shape.
- Score left and right hands independently when possible.
- Skip hand scoring when the reference hand is missing.
- Surface visibility feedback when the reference expects a hand but the learner hand is missing.
- Keep final score body-only unless reliable hand reference data exists.


### Hand-Enabled Upload Generation Rules

- Normal single-dancer hand-enabled generation may run `holistic_landmarker.task` directly on sampled frames.
- High-recall multi-dancer hand-enabled generation must use YOLO for dancer boxes/tracks and Holistic for landmarks on each padded crop.
- YOLO should answer only "where is each dancer/person?"; Holistic should answer "where are this crop's body joints and finger joints?"
- Discard all face landmarks from Holistic results.
- Remap both pose and hand landmarks from crop coordinates back to full-frame normalized coordinates before storing template data.
- If Holistic fails, fall back to `pose_landmarker_full.task` and generate a body-only template with a clear warning.

### Test Rules

Add tests for:

- Holistic result conversion discards face data.
- Missing hand landmarks return empty lists rather than crashing.
- Old pose-only template JSON still parses successfully.
- New template JSON with optional `handTracks` parses successfully.
- High-recall crop remapping works for both pose landmarks and left/right hand landmarks.
- Holistic upload generation falls back to body-only `pose_landmarker_full.task` when Holistic fails.
- Final score uses body-only scoring when no hand reference exists.
- Final score combines body and hand scores only when hand reference exists.
