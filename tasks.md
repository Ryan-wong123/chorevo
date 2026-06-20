# Chorevo AI Upgrade Tasks

This task list should be used together with `prd.md`, `agent.md`, `architecture.md`, and `coding_guidelines.md`.

Legend:

- `[ ]` not started
- `[!]` in progress / needs attention
- `[x]` completed

## Active Task: Pose + Hands Holistic Live Practice Upgrade

Goal: add hand/finger awareness to live dance practice using `app/src/main/assets/holistic_landmarker.task`, while keeping existing body-pose scoring and all old pose-only templates working. Face landmarks are not needed and must not be stored, rendered, scored, or uploaded.

### Phase A: Baseline Review

- [x] Run `git status --short` and avoid overwriting unrelated user changes.
- [x] Confirm the model exists at `app/src/main/assets/holistic_landmarker.task`.
- [x] Inspect current live practice code with `rg "DanceARPracticeScreen|PoseLandmarkerHelper|PoseSimilarityEvaluator|PoseTemplate|PoseTemplateJson" app/src/main/java`.
- [x] Inspect current template generation code with `rg "VideoPoseTemplateGenerator|PoseTemplate|PoseTemplateJson|MultiPersonPoseTracker" app/src/main/java`.
- [x] Confirm the existing MediaPipe Tasks Vision dependency already supports `HolisticLandmarker`; only change Gradle if the current version does not compile.

### Phase B: Add Holistic Pose + Hands Helper

- [x] Add `HolisticLandmarkerHelper` under `posedetection`.
- [x] Configure it to load `holistic_landmarker.task` from assets.
- [x] Support the same running modes needed by the app, at minimum `IMAGE` for current analyzer usage and `VIDEO` if used for template generation.
- [x] Disable optional face blendshape/transformation outputs when available.
- [x] Convert MediaPipe output into an app-owned `HolisticFrameLandmarks` model containing only `pose`, `leftHand`, and `rightHand`.
- [x] Do not expose face landmarks from the helper.
- [x] Add a safe fallback to the existing `PoseLandmarkerHelper` path if holistic setup fails.
- [x] Ensure helper resources are closed through `close()` and Compose `DisposableEffect` or equivalent lifecycle cleanup.

### Phase C: Add Hand Landmark Data Models

- [x] Add `HolisticFrameLandmarks` with `pose`, `leftHand`, and `rightHand` flattened landmark arrays.
- [x] Add conversion helpers that flatten landmarks as repeating `x, y, z, visibility` values.
- [x] Ensure hand arrays contain `21 * 4` values when detected and are empty when missing.
- [x] Add optional template models such as `HandLandmarkFrame` and `HandTrack`.
- [x] Add an optional/default-empty `handTracks` field to `PoseTemplate` without breaking existing JSON.
- [x] Update `PoseTemplateJson` serialization/deserialization to read old templates and new hand-enabled templates.
- [x] Do not add face fields to template models or JSON.

### Phase D: Update Live Practice Pipeline

- [x] Update `DanceARPracticeScreen` to prefer `HolisticLandmarkerHelper` for live camera practice when the model exists.
- [x] Pass `HolisticFrameLandmarks.pose` into the existing `PoseSimilarityEvaluator` unchanged.
- [x] Store the latest learner left/right hand landmarks in UI state only where needed for optional overlay or feedback.
- [x] Keep existing body overlay and reference pose overlay working.
- [x] Do not block camera analysis if hands are missing from a frame.
- [x] Do not display face tracking or face feedback.

### Phase E: Optional Hand/Finger Scoring

- [x] Add a separate `HandSimilarityEvaluator`.
- [x] Normalize each hand relative to the wrist landmark before comparing finger shape.
- [x] Compare only hands that exist in both learner and reference frames.
- [x] Score left and right hands independently, then average available hand scores.
- [x] Keep final score body-only when the selected template has no hand data.
- [x] Use a combined score only when hand reference data exists, for example `finalScore = bodyScore * 0.85 + handScore * 0.15`.
- [x] Add user-facing hand feedback such as keeping hands visible or matching hand shape.
- [x] Avoid making hand score affect achievements/leaderboards until tests confirm the new scoring behavior is stable, or clearly document the intended change.

### Phase F: Template Generation for Hand References

- [x] Add an option for new or regenerated templates to collect hands with Holistic Landmarker.
- [x] Store only pose + left/right hand landmarks from Holistic results.
- [x] For normal single-dancer hand-enabled generation, run `holistic_landmarker.task` and save pose + optional left/right hand references.
- [x] For high-recall multi-dancer hand-enabled generation, run `person_detector_yolo.tflite` first to detect dancer boxes, track dancer IDs, crop each dancer with padding, then run `holistic_landmarker.task` on each crop.
- [x] Remap both pose landmarks and left/right hand landmarks from crop coordinates back to full-frame normalized coordinates.
- [x] Discard all face landmarks and face-related outputs from Holistic results.
- [x] Keep `pose_landmarker_full.task` as the fallback body-only crop estimator when Holistic initialization or crop Holistic inference fails, or when hand mode is disabled.
- [x] Mark hand extraction failures as warnings; do not fail the entire template if body pose generation succeeds.

### Phase G: Tests and Verification

- [ ] Add unit tests for holistic result conversion and face-data discard behavior.
- [ ] Add unit tests for empty/missing hand handling.
- [ ] Add JSON tests for old pose-only templates and new optional hand-enabled templates.
- [ ] Add scoring tests for body-only fallback and body+hand weighted scoring.
- [ ] Run `gradlew.bat testDebugUnitTest` or `./gradlew testDebugUnitTest`.
- [ ] Run `gradlew.bat assembleDebug` or `./gradlew assembleDebug`.
- [ ] Run instrumentation tests if CameraX, Compose UI, or Android model runtime behavior changed and an emulator/device is available.
- [ ] Report files changed, behavior changed, tests run, tests not run, and remaining risks.


### Phase F2: Upgrade High-Recall Multi-Dancer Templates to YOLO + Holistic

- [x] Update the high-recall crop estimator so hand-enabled multi-dancer generation uses `person_detector_yolo.tflite` + `holistic_landmarker.task`, not `pose_landmarker_full.task` as the primary path.
- [x] Keep YOLO responsible only for locating/tracking dancers; keep Holistic responsible for extracting pose + left/right hand landmarks from each dancer crop.
- [x] Expand each dancer crop enough to avoid cutting off hands and fingers before running Holistic.
- [x] Remap both pose and hand landmarks from crop coordinates back to full-frame normalized coordinates.
- [x] Save pose + optional hand references per dancer track in backward-compatible `PoseTemplateJson`.
- [x] Discard all face landmarks and face-related outputs.
- [x] Fall back to `pose_landmarker_full.task` for body-only templates if Holistic setup or crop inference fails.
- [x] Add tests for crop-to-full remapping of both pose and hand landmarks.

---

## Existing Completed Multi-Dancer AI Upgrade Tasks

This task list extends the current Chorevo Android/Kotlin codebase. It should be used together with `prd.md`, `agent.md`, `architecture.md`, and `coding_guidelines.md`.

Legend:

- `[ ]` not started
- `[!]` in progress / needs attention
- `[x]` completed

## Phase 0: Baseline Review

- [x] Run `git status --short` and avoid overwriting unrelated user changes.
- [x] Inspect current pose code with `rg "VideoPoseTemplateGenerator|MultiPersonPoseTracker|PoseTemplate|PoseTemplateJson|PoseLandmarkerHelper"`.
- [x] Inspect upload/background code with `rg "VideoUploadsViewModel|VideoUploadsRepository|WorkManager|templateStatus"`.
- [x] Confirm current model assets under `app/src/main/assets`.
- [x] Confirm current Gradle dependencies before adding any AI runtime dependency.

## Phase 1: Stabilize Existing MediaPipe High-Recall Pipeline

- [x] Increase uploaded-video sampling default to around 5 fps and high-recall sampling to around 8 fps where device performance allows.
- [x] Use `pose_landmarker_full.task` for uploaded video high-recall analysis when present.
- [x] Keep `pose_landmarker_lite.task` for live camera practice.
- [x] Make high-recall tracker settings configurable through a small config data class.
- [x] Set `maxMissedSteps` to roughly `fps * 3` for high-recall dance analysis.
- [x] Add same-frame duplicate removal before tracker updates.
- [x] Add post-processing track-fragment merge after the full video is processed.
- [x] Keep weak but plausible dancer tracks instead of dropping them too early.
- [x] Add warnings when many frames are missing, low-confidence, or inferred.

## Phase 2: Low-Light Preprocessing

- [x] Add `FramePreprocessor` interface in `posedetection`.
- [x] Implement conservative brightness/contrast/gamma preprocessing for high-recall upload analysis.
- [x] Keep preprocessing optional and configurable.
- [x] Apply preprocessing before person detection and crop pose estimation.
- [x] Add tests for preprocessing config behavior where possible.
- [x] Add user-facing or creator-facing warnings when low-light quality may reduce accuracy.

## Phase 3: Person Detection Interface

- [x] Add `DetectionBox` model with normalized coordinates, confidence, label, class ID, and source.
- [x] Add `PersonDetector : AutoCloseable` interface.
- [x] Add a `NoOpPersonDetector` or fallback implementation for tests and missing-model cases.
- [x] Add model asset availability checks before initializing the real detector.
- [x] Fail gracefully and fall back to MediaPipe-only high-recall logic if detector initialization fails.
- [x] Keep detector runtime dependency isolated from live practice code.
- [x] Add focused JVM unit tests for config/model availability/fallback behavior where possible.

## Phase 4: YOLO Person Detection

- [x] Choose exactly one first implementation target: TensorFlow Lite, ONNX Runtime Mobile, or NCNN.
- [x] Add the required Gradle dependency only after confirming compatibility with the current Android SDK/min SDK.
- [x] Add model asset loading from `app/src/main/assets`.
- [x] Implement YOLO input preprocessing: resize, normalize, and tensor conversion.
- [x] Implement YOLO output parsing for person class boxes.
- [x] Implement confidence filtering.
- [x] Implement non-maximum suppression / IoU duplicate removal.
- [x] Add detector unit tests for output parsing using fake model outputs.
- [x] Add safe logging without exposing private media URLs or file paths.

## Phase 5: Dancer Tracking

- [x] Add `TrackedDancerBox` model with track ID, frame index, timestamp, bbox, state, and confidence.
- [x] Add `TrackState` enum: `Visible`, `TemporarilyLost`, `Recovered`, `Ended`.
- [x] Add `DancerTracker` interface.
- [x] Implement ByteTrack-style tracking or an adapter around an existing tracker implementation.
- [x] Match active tracks first, then recent lost tracks, before creating new IDs.
- [x] Keep lost tracks alive for short occlusions.
- [x] Add tests for ID stability when detections disappear and reappear.
- [x] Add tests for duplicate prevention when one dancer is detected by overlapping boxes.

## Phase 6: Per-Dancer Crop MediaPipe Estimation

- [x] Add `CropPoseEstimator` that receives original frame + tracked boxes.
- [x] Expand boxes with padding before cropping.
- [x] Clamp crops to frame boundaries.
- [x] Run MediaPipe Pose Landmarker on each crop for the original body-only high-recall path.
- [x] Remap crop pose landmarks back to original normalized frame coordinates.
- [x] Mark failed crop estimations as missing/low-confidence rather than crashing.
- [x] Add unit tests for crop-to-full coordinate remapping.
- [x] Ensure all MediaPipe helpers and bitmaps/resources are closed or recycled where applicable.

## Phase 7: Track Cleanup and Interpolation

- [x] Add `TrackFragmentMerger` for post-processing split tracks.
- [x] Merge tracks only when gap, position, size, and movement direction are plausible.
- [x] Prevent merging tracks with significant time overlap unless they are clear duplicates.
- [x] Add `MissingLandmarkInterpolator` for short gaps only.
- [x] Mark interpolated landmarks as inferred or low-confidence in metadata if the template format supports it.
- [x] Do not fabricate high-confidence landmarks for fully hidden dancers.
- [x] Add tests for merge and non-merge cases.

## Phase 8: Template and Storage Compatibility

- [x] Preserve existing `PoseTemplate` fields: `fps`, legacy `frames`, `tracks`, `defaultTrackId`, and `selectionPreview`.
- [x] Preserve flattened landmark format: repeating `x, y, z, visibility`.
- [x] Add optional quality metadata only in a backward-compatible way.
- [x] Ensure `PoseTemplateJson` can read older templates.
- [x] Store generated templates under `danceTemplates/{danceId}.json` as before.
- [x] Keep Firestore dance metadata fields compatible with existing UI and rules.

## Phase 9: Upload UI and Review Flow

- [x] Update upload UI state to include detected dancer count.
- [x] Show warnings for possible duplicates, low light, missing frames, and low-confidence tracks.
- [x] Let the creator select or confirm the default dancer track when multiple dancers exist.
- [x] Keep edit/delete upload flows unchanged unless directly affected.
- [x] Avoid exposing confusing internal detector details in the UI.

## Phase 10: Testing, Build, and Handoff

- [x] Run JVM unit tests: `./gradlew testDebugUnitTest` or `gradlew.bat testDebugUnitTest`.
- [!] Run instrumentation tests if Android bitmap/model behavior or UI was changed. Not run locally because `adb devices` showed no attached emulator/device.
- [x] Run `assembleDebug` at minimum.
- [x] Run `assembleRelease` before final handoff if Gradle dependencies/assets changed. Not required for this Phase 10 verification because no Gradle dependencies or model/assets were changed.
- [x] Report files changed, behavior changed, tests run, tests not run, and remaining risks.

## Future Enhancements

- [ ] Add DeepSORT or person Re-ID for dancer identity recovery after longer occlusion.
- [ ] Add segmentation to better isolate overlapping dancer silhouettes.
- [ ] Add server-side high-quality processing for very slow or complex models.
- [ ] Add a benchmark screen or developer-only diagnostics for template generation quality.
- [ ] Add sample low-light and overlapping-dancer test videos for regression checks.
