# Chorevo Product Requirements Document

## 1. Product Summary

Chorevo is a native Android dance-learning app that helps learners practise dance routines with AI-assisted body pose and optional hand/finger feedback. The product combines a vertical dance video library, creator uploads, live camera practice, MediaPipe pose detection, post-session analytics, streaks, achievements, reminders, profiles, and leaderboards.

The current implementation is a single-module Kotlin Android app using Jetpack Compose, Hilt, Firebase Authentication, Firestore, Firebase Storage, CameraX, Media3, Room, WorkManager, DataStore, and MediaPipe Tasks Vision.

## 2. Product Goals

- Let learners discover dance routines and start practice quickly.
- Let creators upload reference dance videos and manage their own uploads.
- Generate pose templates from uploaded reference videos.
- Compare learner pose landmarks against reference templates during practice.
- Save completed practice sessions locally first, then sync them to Firebase.
- Present understandable analytics that help users improve.
- Motivate repeated practice through streaks, achievements, reminders, and leaderboards.
- Keep user-owned data protected with Firebase Auth, Firestore rules, and Storage rules.

## 3. Target Users

### Learners

Learners browse dances, select a routine, practise with camera guidance, submit results, review analytics, maintain streaks, and compare progress on leaderboards.

### Creators / Educators

Creators upload dance demo videos, confirm detected dancer tracks when available, manage their own uploaded dances, and view learner analytics for sessions linked to their uploads.

## 4. Current Feature Scope

### Authentication and Profile

- Email/password sign up and sign in through Firebase Auth.
- Firestore-backed user profile records under `users/{uid}`.
- Profile photo upload to Firebase Storage under `profilePhotos/{uid}/profile.jpg`.
- Sign out from settings/profile flows.
- Current user restore on app startup before showing authenticated routes.

### Dance Library

- Firestore-backed library from the `danceLibrary` collection.
- Only enabled dances with ready pose templates are shown in the learner feed.
- Vertical video-first browsing experience.
- Dance metadata includes title, description, dance type, educator UID/name, video URL, template status, dancer count, and review status.
- Practice route prepares the selected dance by loading its pose template from Firebase Storage.

### Creator Uploads

- Upload flow supports gallery or recorded videos.
- Videos are compressed before upload when compression reduces file size.
- Videos are stored under `danceVideos/{uid}/{danceId}.mp4`.
- Dance metadata is written to `danceLibrary/{danceId}`.
- Uploads may include a confirmed pose template or enqueue background template generation.
- Users can list, preview, edit, and delete their own uploads.

### Pose Template Generation

- MediaPipe extracts landmarks from video frames.
- Standard generation samples uploaded videos for one or more tracks.
- Hand-enabled generation should use `holistic_landmarker.task` to collect body pose plus left/right hand landmarks while discarding face output.
- High-recall multi-dancer analysis should use `person_detector_yolo.tflite` to find dancers, track/crop each dancer, then run `holistic_landmarker.task` on each padded crop to generate pose + hand templates.
- `pose_landmarker_full.task` remains the body-only fallback when holistic initialization or crop holistic inference fails, or when hand-enabled generation is disabled.
- Generated templates are serialized to JSON and stored under `danceTemplates/{danceId}.json`.
- Template generation runs through WorkManager after upload when not confirmed inline.

### AR Practice and Scoring

- CameraX provides live camera preview.
- MediaPipe extracts learner pose landmarks on device. The upgraded live practice path should use Holistic Landmarker to extract body pose plus left/right hand/finger landmarks.
- Face landmarks are out of scope for this implementation and must not be stored, rendered, or scored.
- `PoseSimilarityEvaluator` compares stable body joints against a reference track.
- A new optional hand/finger evaluator may compare left/right hand landmarks when the selected template includes hand data.
- Scores include alignment, timing consistency, smoothness, average pose error, timeline points, performance segments, and optional hand/finger feedback.
- Practice submission writes a Room `DanceSessionEntity`, then attempts Firestore sync.

### Analytics

- Learners can review their own sessions from `users/{uid}/danceLearningSessions`.
- Creators can view collection-group analytics for sessions where `analysis.educatorUid` matches their UID.
- Analytics detail includes summary metrics, reference video data, timeline points, and performance segments.

### Progress and Engagement

- Weekly streak data is stored in Firestore.
- Achievement badges are awarded and stored under `users/{uid}/achievements/current`.
- Points and level-up concepts are represented in the dance activity package.
- Leaderboard stats are stored in `leaderboardStats/{uid}` and support global and friends-only scopes.
- Reminder settings are stored in DataStore and scheduled with Android alarms/notifications.

### Offline and Retry Behavior

- Dance sessions are inserted into Room before upload.
- Unsynced sessions are identified through the `synced` flag.
- Startup invokes retry logic for signed-in users.
- Failed submissions preserve local session data and report upload failure to the UI.

## 5. Out of Scope for the Current App

- Web or iOS clients.
- Payments, subscriptions, or creator monetization.
- Admin/moderator dashboards.
- Server-side trusted score recomputation.
- Social posting or public comments.
- Full choreography music beat alignment.
- Multi-camera capture.
- Raw learner video upload for practice sessions.
- Face tracking, face mesh scoring, facial expression scoring, and face landmark storage.

## 6. Functional Requirements

### Account

- Users must be able to sign up, sign in, sign out, and restore an existing authenticated session.
- All protected data writes must be associated with the authenticated Firebase UID.
- Profile updates must affect only the signed-in user's document.

### Dance Discovery

- The app must fetch enabled library dances from Firestore.
- Dances without a ready template must not be presented as practice-ready.
- Selecting a dance must load its pose template before entering practice.
- Template loading failures must surface a clear user-facing message.

### Upload Management

- A signed-in creator must be able to upload a video with title, dance type, and description.
- The app must write upload metadata and media storage paths consistently.
- The app must track template generation states: queued, processing, ready, failed, skipped, or saving.
- A creator must be able to update title, dance type, and description for their own uploads.
- A creator must be able to delete their own upload metadata, video object, and template object.

### Practice Session

- A learner must be able to practise against a selected dance using the camera.
- The app must compute score snapshots during practice.
- The app should support a pose + hands live practice mode using `app/src/main/assets/holistic_landmarker.task`.
- The app must continue to support existing pose-only templates and body-only scoring.
- The app must not penalize hand score when the reference template has no hand/finger data.
- On submission, the app must create a session entity with timestamps, duration, scores, quality flag, and analysis JSON.
- The app must save locally before attempting cloud upload.
- Successful cloud upload must mark the Room session as synced.

### Analytics

- A learner must be able to see recent own sessions and session details.
- A creator must be able to see learner sessions linked to their uploaded dances.
- Analytics must parse missing or partially populated analysis data safely.
- Timeline and segment data must be sorted before display.

### Engagement

- Completing a practice session should update streak and leaderboard stats.
- Users must be able to view leaderboard rankings by metric.
- Friends leaderboard must include the current user plus saved friend IDs.
- Users must be able to enable/disable reminders and set reminder time.

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
- Firestore rules must enforce owner-only writes for profiles, sessions, friends, streaks, achievements, and uploads.
- Learner session reads must be limited to the owner or the educator linked in the analysis payload.
- Storage rules must enforce owner-only writes and content-type/size limits.
- App Check debug provider is installed only for debug builds.

### Maintainability

- Feature code should remain grouped by package: `analytics`, `danceactivity`, `dancelibrarylist`, `firebase`, `leaderboard`, `notification`, `posedetection`, `videouploads`, `core`, and `ui/theme`.
- UI state should be represented with immutable data classes and exposed through read-only `StateFlow`.
- Firebase, Room, and Storage access should stay inside repositories or focused infrastructure classes.
- Long-running or IO-heavy work should run in coroutines, WorkManager, or injected dispatchers.

### Testability

- Repository and ViewModel logic should be unit tested with MockK, coroutine test utilities, and Turbine where useful.
- Room DAO behavior and key Compose flows should be covered by instrumentation tests.
- CI should run JVM unit tests, Android instrumentation tests, and assemble debug/release builds.

## 8. Key Data Objects

### Firestore Collections

- `users/{uid}`: profile, role, email, display name, photo URL, last seen.
- `users/{uid}/danceLearningSessions/{sessionId}`: practice session summaries and nested analysis payload.
- `users/{uid}/friends/{friendUid}`: saved friend references for friends leaderboard.
- `users/{uid}/achievements/current`: earned badge IDs.
- `users/{uid}/streaks/...` and `users/{uid}/streak/...`: streak data used by progress UI.
- `danceLibrary/{danceId}`: creator-uploaded dance metadata and template generation state.
- `leaderboardStats/{uid}`: aggregate stats used for ranking.

### Storage Paths

- `danceVideos/{uid}/{danceId}.mp4`: uploaded reference videos.
- `danceThumbnails/{uid}/{fileName}`: future or optional thumbnails.
- `danceTemplates/{danceId}.json`: generated pose templates.
- `profilePhotos/{uid}/profile.jpg`: profile pictures.

### Room

- `dance_sessions`: local practice session records with sync state.

## 9. Success Metrics

- Sign-up and sign-in success rate.
- Number of uploaded dances with ready templates.
- Template generation success/failure rate.
- Library-to-practice conversion rate.
- Practice completion and submission rate.
- Percentage of sessions successfully synced.
- Analytics view rate after session completion.
- Weekly active learners and streak retention.
- Leaderboard participation.

## 10. Release Acceptance Criteria

- `testDebugUnitTest` passes.
- `connectedDebugAndroidTest` passes on a configured emulator.
- `assembleDebug` and `assembleRelease` complete in CI.
- Firebase rules and indexes are deployed for the target project.
- A signed-in user can upload a dance, generate or save a template, see it in the library, practise it, submit results, and view analytics.
- A failed session upload leaves a local unsynced record that can be retried.
- Unauthorized users cannot write or delete data owned by another UID.

## 11. Roadmap Candidates

- Better search, filtering, and favorites in the dance library.
- More explicit role handling for learners, creators, and future admins.
- Server-side validation or recomputation for official leaderboard scores.
- Moderation/reporting workflows for public content.
- Richer body-part feedback and coaching messages.
- Better migration strategy for Room schema changes.
- Upload thumbnail generation and display.
- More robust offline sync queue visibility in the UI.

## 12. Pose + Hands Holistic Practice Upgrade Requirements

This active upgrade adds hand/finger awareness to live dance practice while keeping the existing body-pose scoring and template compatibility.

### 13.1 Problem Statement

The current live practice path can evaluate major body joints such as shoulders, elbows, wrists, hips, knees, and ankles. It cannot tell whether a learner's fingers are open, pointing, curled, or matching a required hand gesture. For dance routines where hand shape matters, wrist-only tracking can give a high score even when the finger gesture is wrong.

### 12.2 Product Goals

- Keep the existing body-pose score as the main score.
- Add optional hand/finger landmarks for left and right hands.
- Use the already-downloaded model asset at `app/src/main/assets/holistic_landmarker.task`.
- Ignore face output completely for this feature.
- Keep old pose-only templates valid and practice-ready.
- Add hand feedback only when the reference template contains hand data.

### 12.3 Functional Requirements

- The live camera practice path should initialize a `HolisticLandmarkerHelper` when `holistic_landmarker.task` is available.
- The helper must return a small app-owned model containing only `pose`, `leftHand`, and `rightHand` flattened landmarks.
- The helper must ignore `faceLandmarks`, face blendshapes, and facial transformation matrices.
- The body pose data must remain compatible with `PoseSimilarityEvaluator`.
- New or regenerated reference templates may store optional hand tracks in a backward-compatible JSON field such as `handTracks`.
- A new `HandSimilarityEvaluator` should compute a hand/finger score only when both learner and reference hand landmarks are available.
- The final practice score should remain body-only when no reference hand data exists.
- Hand/finger feedback should be understandable, for example: `Keep your right hand visible`, `Open your fingers more`, or `Match the hand shape`.

### 12.4 Non-Goals

- Face mesh scoring.
- Facial expression coaching.
- Storing face landmarks in local or cloud templates.
- Replacing `PoseSimilarityEvaluator`.
- Making YOLO or multi-dancer upload detection part of live camera practice.

### 12.5 Acceptance Criteria

- App builds with `holistic_landmarker.task` in `app/src/main/assets`.
- Live practice still works with old pose-only templates.
- Existing body alignment, timing consistency, and smoothness scores still update.
- When a template has hand tracks, the UI can show optional hand/finger feedback.
- When a template has no hand tracks, no hand penalty is applied.
- No face landmarks are written to template JSON, Room, Firestore, logs, or analytics payloads.

## 13. Multi-Dancer AI Upgrade Requirements

This section extends the current MediaPipe-first template generation with a stronger multi-person detection and tracking pipeline. The goal is to improve uploaded dance-video analysis when videos contain several dancers, low lighting, partial occlusion, temporary out-of-frame movement, or overlapping dancers.

### 13.1 Problem Statement

Current high-recall MediaPipe detection can find more dancers than the standard path, but group videos may still fail in these ways:

- A dancer is missed because they are small, dark, near the edge of the frame, or partially blocked.
- The same dancer is detected more than once because full-frame and crop-based passes overlap.
- The same dancer becomes multiple tracks after occlusion or a temporary confidence drop.
- Weak tracks are removed too aggressively even though they represent real dancers.
- Low-light frames reduce person detection and landmark confidence.

### 13.2 Product Goals for the Upgrade

- Detect as many visible dancers as practical from uploaded reference videos.
- Keep a stable identity for each dancer across frames.
- Preserve the existing MediaPipe 33-landmark template format where possible.
- Support user review/confirmation when automatic dancer selection is uncertain.
- Mark hidden or missing landmarks explicitly rather than inventing false data.
- Keep live practice fast by using the lighter existing real-time path.

### 13.3 Recommended AI Stack

For uploaded video/template generation:

- YOLO person detector exported for Android through TensorFlow Lite, ONNX Runtime Mobile, or NCNN.
- ByteTrack as the default multi-object tracker.
- BoT-SORT or DeepSORT/Re-ID as future alternatives when dancer crossing and re-identification become common issues.
- MediaPipe Holistic Landmarker for per-dancer crop landmark generation when pose + hand/finger templates are required. Use only pose, left hand, and right hand outputs; discard face outputs.
- MediaPipe Pose Landmarker `full` as a body-only fallback if holistic inference fails or hand-enabled generation is disabled.
- OpenCV Android or custom bitmap processing for brightness, contrast, gamma, denoise, and sharpening.

For live camera practice:

- Prefer Holistic Landmarker when the pose + hands model is available so live practice can read body pose plus left/right hand landmarks.
- Keep MediaPipe Pose Landmarker `lite` as the body-only fallback for responsiveness if Holistic fails.
- Consider MoveNet Lightning/Thunder only as a later alternative if live performance requires it.

### 13.4 Functional Requirements

- The upload analysis path must support a high-recall mode for multi-dancer videos.
- The high-recall path must optionally run YOLO person detection before MediaPipe landmark extraction.
- For hand-enabled high-recall templates, the pipeline must use `person_detector_yolo.tflite` to detect visible dancer boxes, track each dancer, crop each dancer with padding, and run `holistic_landmarker.task` on each crop.
- The detector must return visible person bounding boxes for each processed frame.
- The tracker must assign stable dancer IDs across frames.
- The crop landmark extractor must remap both pose landmarks and left/right hand landmarks from crop coordinates back to original full-frame coordinates.
- The crop landmark extractor must discard face landmarks and face-related outputs.
- If Holistic initialization or crop Holistic inference fails, the pipeline must fall back to `pose_landmarker_full.task` for body-only template generation rather than failing the whole upload.
- The pipeline must remove duplicate same-frame detections before updating long-term tracks.
- The pipeline must merge fragmented tracks that likely belong to the same dancer.
- The pipeline must tolerate short gaps caused by occlusion, low confidence, or temporary out-of-frame movement.
- The template format must remain backward compatible with existing `PoseTemplate` and `PoseTemplateJson` consumers.
- The creator upload UI should expose review data such as detected dancer count, selected default dancer, preview frames, and low-confidence warnings.

### 13.5 Low-Light Requirements

- High-recall uploaded video analysis should optionally apply brightness/contrast/gamma preprocessing before detection.
- Preprocessing must be configurable and easy to disable if it makes a video worse.
- The app must not claim that low-light preprocessing can recover dancers that are not visually present.
- The analysis result should flag low-confidence frames or tracks so the user can review them.

### 13.6 Occlusion and Overlap Requirements

- If dancers partially overlap, the detector/tracker should keep separate dancer IDs when visible evidence is sufficient.
- If a dancer is fully hidden, the system should keep the track alive for a bounded number of frames and mark landmarks as missing or low-confidence.
- The system may interpolate short missing sections, but interpolation must be limited and marked as inferred.
- The system must not fabricate high-confidence landmarks for fully hidden dancers.

### 13.7 Non-Goals for This Upgrade

- Perfect detection of fully hidden dancers.
- Guaranteed identity recovery after long disappearances without re-identification support.
- Replacing all existing MediaPipe scoring and analytics code.
- Uploading learner practice videos to the cloud.
- Adding a server-side AI backend in the first implementation pass.

### 13.8 Success Metrics

- Higher average detected dancer count on known group videos.
- Lower duplicate-track count after post-processing.
- Fewer fragmented tracks for the same dancer.
- Higher template generation success rate for low-light uploads.
- New hand-enabled multi-dancer templates contain useful left/right hand references when hands are visible.
- Creator review screen shows useful warnings instead of silently failing.
- No regression to live camera practice responsiveness.

