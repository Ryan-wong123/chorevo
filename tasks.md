# Chorevo Multi-Dancer AI Upgrade Tasks

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

- [ ] Choose exactly one first implementation target: TensorFlow Lite, ONNX Runtime Mobile, or NCNN.
- [ ] Add the required Gradle dependency only after confirming compatibility with the current Android SDK/min SDK.
- [ ] Add model asset loading from `app/src/main/assets`.
- [ ] Implement YOLO input preprocessing: resize, normalize, and tensor conversion.
- [ ] Implement YOLO output parsing for person class boxes.
- [ ] Implement confidence filtering.
- [ ] Implement non-maximum suppression / IoU duplicate removal.
- [ ] Add detector unit tests for output parsing using fake model outputs.
- [ ] Add safe logging without exposing private media URLs or file paths.

## Phase 5: Dancer Tracking

- [ ] Add `TrackedDancerBox` model with track ID, frame index, timestamp, bbox, state, and confidence.
- [ ] Add `TrackState` enum: `Visible`, `TemporarilyLost`, `Recovered`, `Ended`.
- [ ] Add `DancerTracker` interface.
- [ ] Implement ByteTrack-style tracking or an adapter around an existing tracker implementation.
- [ ] Match active tracks first, then recent lost tracks, before creating new IDs.
- [ ] Keep lost tracks alive for short occlusions.
- [ ] Add tests for ID stability when detections disappear and reappear.
- [ ] Add tests for duplicate prevention when one dancer is detected by overlapping boxes.

## Phase 6: Per-Dancer Crop MediaPipe Estimation

- [ ] Add `CropPoseEstimator` that receives original frame + tracked boxes.
- [ ] Expand boxes with padding before cropping.
- [ ] Clamp crops to frame boundaries.
- [ ] Run MediaPipe Pose Landmarker on each crop.
- [ ] Remap crop landmarks back to original normalized frame coordinates.
- [ ] Mark failed crop estimations as missing/low-confidence rather than crashing.
- [ ] Add unit tests for crop-to-full coordinate remapping.
- [ ] Ensure all MediaPipe helpers and bitmaps/resources are closed or recycled where applicable.

## Phase 7: Track Cleanup and Interpolation

- [ ] Add `TrackFragmentMerger` for post-processing split tracks.
- [ ] Merge tracks only when gap, position, size, and movement direction are plausible.
- [ ] Prevent merging tracks with significant time overlap unless they are clear duplicates.
- [ ] Add `MissingLandmarkInterpolator` for short gaps only.
- [ ] Mark interpolated landmarks as inferred or low-confidence in metadata if the template format supports it.
- [ ] Do not fabricate high-confidence landmarks for fully hidden dancers.
- [ ] Add tests for merge and non-merge cases.

## Phase 8: Template and Storage Compatibility

- [ ] Preserve existing `PoseTemplate` fields: `fps`, legacy `frames`, `tracks`, `defaultTrackId`, and `selectionPreview`.
- [ ] Preserve flattened landmark format: repeating `x, y, z, visibility`.
- [ ] Add optional quality metadata only in a backward-compatible way.
- [ ] Ensure `PoseTemplateJson` can read older templates.
- [ ] Store generated templates under `danceTemplates/{danceId}.json` as before.
- [ ] Keep Firestore dance metadata fields compatible with existing UI and rules.

## Phase 9: Upload UI and Review Flow

- [ ] Update upload UI state to include detected dancer count.
- [ ] Show warnings for possible duplicates, low light, missing frames, and low-confidence tracks.
- [ ] Let the creator select or confirm the default dancer track when multiple dancers exist.
- [ ] Keep edit/delete upload flows unchanged unless directly affected.
- [ ] Avoid exposing confusing internal detector details in the UI.

## Phase 10: Testing, Build, and Handoff

- [x] Run JVM unit tests: `./gradlew testDebugUnitTest` or `gradlew.bat testDebugUnitTest`.
- [ ] Run instrumentation tests if Android bitmap/model behavior or UI was changed.
- [x] Run `assembleDebug` at minimum.
- [ ] Run `assembleRelease` before final handoff if Gradle dependencies/assets changed.
- [ ] Report files changed, behavior changed, tests run, tests not run, and remaining risks.

## Future Enhancements

- [ ] Add DeepSORT or person Re-ID for dancer identity recovery after longer occlusion.
- [ ] Add segmentation to better isolate overlapping dancer silhouettes.
- [ ] Add server-side high-quality processing for very slow or complex models.
- [ ] Add a benchmark screen or developer-only diagnostics for template generation quality.
- [ ] Add sample low-light and overlapping-dancer test videos for regression checks.
