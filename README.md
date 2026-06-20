# Chorevo

Chorevo is an Android dance-learning app built with Kotlin and Jetpack Compose. It combines a TikTok-style dance library, live AR practice with pose detection, analytics dashboards, educator uploads, reminders, streaks, achievements, and leaderboards.

> Package: `com.example.chorevo`  
> Module: `:app`  
> Min SDK: `26`  
> Compile / Target SDK: `36`

## What the App Does

Chorevo supports two main user journeys:

- Learners can sign in, browse dance videos, start guided practice sessions, review their analytics, track streaks and achievements, and appear on leaderboards.
- Educators can upload dance demo videos, generate pose templates from the uploaded video, and review how other users perform on their uploaded dances.

## Main Features

### Authentication and profile

- Email/password sign up and login
- Firebase-backed user profiles
- Profile screen with:
  - activity history
  - uploaded videos
  - analytics for other users dancing to the user’s uploads
- Profile photo upload from gallery
- Settings screen with sign out

### Dance library

- Vertical swipe feed built with a pager
- Video playback using Media3 / ExoPlayer
- Dance metadata including title, educator, type, and description
- One-tap transition from library to AR practice

### AR practice

- CameraX live camera preview
- MediaPipe Pose Landmarker inference on camera frames
- Optional MediaPipe Holistic Landmarker inference for body pose + left/right hand/finger landmarks
- Overlay of learner pose and reference/template pose
- Live metrics for:
  - alignment
  - timing consistency
  - smoothness
- Session submission with analytics payload generation
- Achievement and level-up dialog after completed sessions

### Analytics

- Expandable session list
- Detailed analytics view per session
- Template video playback
- Timeline chart
- Performance segment visualizations
- Session metrics such as duration, pose error, and frame count

### Uploads

- Dance video upload flow for educators
- Video compression before upload
- Upload progress display
- Firebase Storage upload
- Firestore metadata creation in `danceLibrary`
- Pose template generation from uploaded videos
- My uploads management tab for educator-owned content

### Leaderboard and progress

- Global leaderboard
- Friends-only leaderboard
- Metrics for:
  - total sessions
  - average similarity
  - total active days
- Weekly streak tracking
- Points and achievement levels

### Notifications and offline support

- Reminder scheduling with a chosen time
- Notification permission flow
- Exact alarm support for reminders
- Local session persistence with Room
- Retry support for unsynced session uploads

## Project Structure

```text
.
├── .github/
│   └── workflows/
│       └── android.yml
├── app/
│   ├── build.gradle.kts
│   ├── google-services.json
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   ├── pose_landmarker_lite.task
│       │   │   ├── pose_landmarker_full.task
│       │   │   └── holistic_landmarker.task
│       │   ├── java/com/example/chorevo/
│       │   └── res/
│       ├── test/
│       └── androidTest/
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

## Code Organization

The main source package is `app/src/main/java/com/example/chorevo/`.

### Entry points

- `Chorevo.kt`
  - Application class
  - Initializes Firebase
  - Installs Firebase App Check debug provider in debug builds
- `MainActivity.kt`
  - Main activity
  - Hosts the Compose UI
  - Starts the top-level app router

### Shared app infrastructure

- `core/`
  - Navigation routes
  - app-level UI state
  - camera binding and preview helpers
  - shared video player utilities
  - Room database setup
  - Hilt dependency modules

### Feature packages

- `analytics/`
  - analytics models, dashboard UI, charts, and analytics view model
- `danceactivity/`
  - AR practice flow, session entities, streaks, achievements, and repositories
- `dancelibrarylist/`
  - dance feed UI, repository, models, and view model
- `firebase/`
  - auth UI, user profile UI, auth/profile repositories, and retry upload service
- `leaderboard/`
  - leaderboard UI, metrics, models, repository, and view model
- `notification/`
  - reminder scheduling, receivers, notification channels, and settings UI
- `posedetection/`
  - pose landmarking, overlay rendering, pose comparison, and template generation
- `ui/theme/`
  - Compose colors, typography, and theme
- `videouploads/`
  - upload screen, upload repository, compressor, state, and view model

## Important Files

- `build.gradle.kts`
  - root Gradle plugin declarations
- `settings.gradle.kts`
  - project name and included modules
- `app/build.gradle.kts`
  - Android config, SDK versions, dependencies, build types, and test settings
- `gradle/libs.versions.toml`
  - dependency and plugin version catalog
- `app/src/main/AndroidManifest.xml`
  - permissions, receivers, application class, and launcher activity
- `.github/workflows/android.yml`
  - CI workflow for tests and build assembly
- `app/src/main/assets/pose_landmarker_lite.task`
  - MediaPipe model used by real-time pose detection
- `app/src/main/assets/pose_landmarker_full.task`
  - Optional higher-accuracy body-only MediaPipe fallback for uploaded video template generation when holistic hand extraction is unavailable or disabled.
- `app/src/main/assets/holistic_landmarker.task`
  - MediaPipe Holistic model for pose + hands. Live practice uses this for body pose plus left/right hand/finger landmarks. Hand-enabled upload generation also uses it after dancer crops are produced by the high-recall YOLO pipeline. Face output is intentionally ignored.
- `app/google-services.json`
  - Firebase configuration required at runtime

## Build Requirements

To build and run the project, you need:

- Android Studio
- Android SDK
- JDK 17 for CI-compatible local builds
- a Firebase project with Auth, Firestore, and Storage enabled
- `app/google-services.json` configured for `com.example.chorevo`

The project currently uses:

- Java source/target compatibility: `11`
- Kotlin JVM target: `11`
- CI Java version: `17`

## How to Build

### Android Studio

1. Open the repository root in Android Studio.
2. Sync Gradle.
3. Ensure `app/google-services.json` is present.
4. Run the `app` configuration on an emulator or Android device.

### Command line

#### Windows

```bash
gradlew.bat assembleDebug
```

#### macOS / Linux

```bash
./gradlew assembleDebug
```

To assemble both debug and release builds:

#### Windows

```bash
gradlew.bat assembleDebug assembleRelease
```

#### macOS / Linux

```bash
./gradlew assembleDebug assembleRelease
```

## How to Test

### Unit tests

#### Windows

```bash
gradlew.bat testDebugUnitTest
```

#### macOS / Linux

```bash
./gradlew testDebugUnitTest
```

### Instrumentation tests

#### Windows

```bash
gradlew.bat connectedDebugAndroidTest
```

#### macOS / Linux

```bash
./gradlew connectedDebugAndroidTest
```

Instrumentation tests require an emulator or physical device.

## CI

GitHub Actions workflow: `.github/workflows/android.yml`

The workflow runs on:

- every push to any branch
- every pull request to any branch

### CI jobs

1. `unit-tests`
   - runs `testDebugUnitTest`
2. `integration-tests`
   - runs `connectedDebugAndroidTest` on an Android emulator
   - depends on `unit-tests`
3. `build`
   - runs `assembleDebug assembleRelease`
   - depends on both test jobs

### CI environment

- runner: `ubuntu-latest`
- JDK: `temurin 17`
- emulator API level: `29`
- emulator architecture: `x86_64`

## Build Artifacts

### Local outputs

Generated APKs are placed under:

```text
app/build/outputs/apk/
```

Example:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### CI-uploaded artifacts

The current workflow uploads instrumented test artifacts only:

- `app/build/outputs/androidTest-results/`
- `app/build/reports/androidTests/`

Artifact name:

- `instrumented-test-results`

Retention:

- `7 days`

### Important note

The workflow assembles debug and release builds, but it does not currently upload APK or AAB files as downloadable CI artifacts.

## Firebase Setup

1. Create a Firebase project.
2. Register an Android app with package name `com.example.chorevo`.
3. Download `google-services.json`.
4. Place it at `app/google-services.json`.
5. Enable:
   - Authentication
   - Firestore Database
   - Storage

### Google Sign-In fingerprint setup

Firebase Authentication with Google sign-in requires your Android app certificate fingerprint to be registered in Firebase. Without the correct SHA values, Google sign-in commonly fails with OAuth or client ID related errors.

#### Where to add the fingerprint in Firebase

1. Open Firebase Console.
2. Go to your project.
3. Click `Project settings`.
4. Under `Your apps`, select the Android app `com.example.chorevo`.
5. Click `Add fingerprint`.
6. Paste the `SHA-1`.
7. Paste the `SHA-256`.
8. Save.
9. Re-download `google-services.json`.
10. Replace `app/google-services.json`.

#### How to get the fingerprints

In Android Studio:

1. Open the `Gradle` panel.
2. Go to `Tasks`.
3. Open `android`.
4. Run `signingReport`.

Or from the project root in a terminal:

```powershell
.\gradlew.bat signingReport
```

You will see output similar to:

```text
Variant: debug
SHA1: ...
SHA-256: ...
```

#### Which fingerprints to use

- For local testing, add the `debug` fingerprint values.
- For release builds, also add the `release` fingerprint values.
- If you use Google Play App Signing, also add the Play signing certificate fingerprint from Play Console.

#### Important

- After adding fingerprints in Firebase, always download a fresh `google-services.json` again.
- Make sure the new file replaces the existing file at `app/google-services.json`.
- If the fingerprint is missing or wrong, Google sign-in may fail even if the app builds correctly.

### Firestore indexes

The app includes deployable Firestore index configuration:

- `firebase.json`
- `firestore.indexes.json`

Deploy the indexes after creating or switching Firebase projects:

```bash
firebase login
firebase deploy --only firestore:indexes --project YOUR_FIREBASE_PROJECT_ID
```

Replace `YOUR_FIREBASE_PROJECT_ID` with the Firebase project ID shown in Firebase Console.

The required indexes are:

#### `danceLibrary` collection index

Used by the educator uploads list:

```kotlin
db.collection("danceLibrary")
    .whereEqualTo("educatorUid", educatorUid)
    .orderBy("createdAt", Query.Direction.DESCENDING)
```

Create this composite index:

```text
Collection ID: danceLibrary
Query scope: Collection
Fields:
- educatorUid Ascending
- createdAt Descending
```

#### `danceLearningSessions` collection-group single-field index

Used by educator analytics across all learner session subcollections:

```kotlin
db.collectionGroup("danceLearningSessions")
    .whereEqualTo("analysis.educatorUid", uploaderUid)
```

Create this single-field index:

```text
Collection ID: danceLearningSessions
Query scope: Collection group
Field:
- analysis.educatorUid Ascending
```

#### `danceLearningSessions` collection-group composite index

Used when educator analytics are filtered to one uploaded dance:

```kotlin
db.collectionGroup("danceLearningSessions")
    .whereEqualTo("analysis.educatorUid", uploaderUid)
    .whereEqualTo("analysis.danceId", uploadedDanceId)
```

Create this composite index:

```text
Collection ID: danceLearningSessions
Query scope: Collection group
Fields:
- analysis.educatorUid Ascending
- analysis.danceId Ascending
```

You can also create these manually in Firebase Console:

1. Open Firebase Console.
2. Go to Firestore Database.
3. Open the Indexes tab.
4. Click Add Index.
5. Enter each index above.
6. Wait for each index status to become Enabled.

Most other app queries are covered by Firestore's automatic collection-scope single-field indexes.

### Firestore security rules

Add these Firestore rules in Firebase Console under Firestore Database -> Rules, or save them in a local `firestore.rules` file and deploy with Firebase CLI.

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function signedIn() {
      return request.auth != null;
    }

    function isOwner(uid) {
      return signedIn() && request.auth.uid == uid;
    }

    function hasValidAnalysis(data) {
      return data.analysis is map
        && data.analysis.danceId is string
        && data.analysis.educatorUid is string;
    }

    function linkedDanceExists(data) {
      return hasValidAnalysis(data)
        && exists(/databases/$(database)/documents/danceLibrary/$(data.analysis.danceId));
    }

    function linkedDanceOwner(data) {
      return get(/databases/$(database)/documents/danceLibrary/$(data.analysis.danceId)).data.educatorUid;
    }

    function validSessionWrite(data) {
      return linkedDanceExists(data)
        && linkedDanceOwner(data) == data.analysis.educatorUid;
    }

    match /users/{uid} {
      allow read: if isOwner(uid) || (signedIn() && request.auth.uid != uid);
      allow create, update: if isOwner(uid);
      allow delete: if false;

      match /points/{docId} {
        allow read, write: if isOwner(uid);
      }

      match /achievements/{docId} {
        allow read, write: if isOwner(uid);
      }

      match /streak/{docId} {
        allow read, write: if isOwner(uid);
      }

      match /streaks/{docId} {
        allow read, write: if isOwner(uid);
      }

      match /friends/{friendUid} {
        allow read, write: if isOwner(uid);
      }

      match /danceLearningSessions/{sessionId} {
        allow read: if isOwner(uid) || (
          signedIn()
          && resource.data.analysis is map
          && resource.data.analysis.educatorUid is string
          && resource.data.analysis.educatorUid == request.auth.uid
        );

        allow create: if isOwner(uid)
          && validSessionWrite(request.resource.data);

        allow update: if isOwner(uid)
          && validSessionWrite(request.resource.data)
          && resource.data.analysis is map
          && request.resource.data.analysis is map
          && request.resource.data.analysis.danceId == resource.data.analysis.danceId
          && request.resource.data.analysis.educatorUid == resource.data.analysis.educatorUid;

        allow delete: if false;
      }
    }

    match /{path=**}/danceLearningSessions/{sessionId} {
      allow read: if signedIn()
        && resource.data.analysis is map
        && resource.data.analysis.educatorUid is string
        && resource.data.analysis.educatorUid == request.auth.uid;
    }

    match /danceLibrary/{danceId} {
      allow read: if signedIn();

      allow create: if signedIn()
        && request.resource.data.educatorUid == request.auth.uid
        && request.resource.data.danceId == danceId;

      allow update: if signedIn()
        && resource.data.educatorUid == request.auth.uid
        && request.resource.data.educatorUid == resource.data.educatorUid
        && request.resource.data.danceId == resource.data.danceId;

      allow delete: if signedIn()
        && resource.data.educatorUid == request.auth.uid;
    }

    match /leaderboardStats/{uid} {
      allow read: if signedIn();
      allow create, update: if signedIn() && request.auth.uid == uid;
      allow delete: if false;
    }
  }
}
```

If using Firebase CLI, deploy rules with:

```bash
firebase deploy --only firestore:rules --project YOUR_FIREBASE_PROJECT_ID
```

### Storage security rules

The app also includes deployable Firebase Storage rules:

- `storage.rules`

Add these rules in Firebase Console under Storage -> Rules, or deploy them with Firebase CLI.

```text
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {

    function isSignedIn() {
      return request.auth != null;
    }

    function isOwner(uid) {
      return isSignedIn() && request.auth.uid == uid;
    }

    match /danceVideos/{userId}/{fileName} {
      allow read: if true;

      allow write: if isOwner(userId)
        && request.resource.size < 200 * 1024 * 1024
        && request.resource.contentType.matches('video/.*');
    }

    match /danceThumbnails/{userId}/{fileName} {
      allow read: if true;

      allow write: if isOwner(userId)
        && request.resource.size < 10 * 1024 * 1024
        && request.resource.contentType.matches('image/.*');
    }

    match /profilePhotos/{userId}/{fileName} {
      allow read: if isSignedIn();

      allow write: if isOwner(userId)
        && request.resource.size < 10 * 1024 * 1024
        && request.resource.contentType.matches('image/.*');
    }

    match /danceTemplates/{fileName} {
      allow read: if isSignedIn();

      allow create, update: if isSignedIn()
        && request.resource.size < 20 * 1024 * 1024
        && request.resource.contentType == 'application/json';

      allow delete: if isSignedIn();
    }

    match /{allPaths=**} {
      allow read, write: if false;
    }
  }
}
```

Deploy Storage rules with:

```bash
firebase deploy --only storage --project YOUR_FIREBASE_PROJECT_ID
```

## Pose Landmark Models

The app may use three MediaPipe model assets:

```text
app/src/main/assets/pose_landmarker_lite.task
app/src/main/assets/pose_landmarker_full.task
app/src/main/assets/holistic_landmarker.task
```

Recommended usage:

- `pose_landmarker_lite.task`: fast body-only fallback for real-time AR practice.
- `pose_landmarker_full.task`: higher-accuracy body-only fallback for uploaded video template generation when holistic hand extraction is unavailable, disabled, or fails.
- `holistic_landmarker.task`: primary model for pose + hands in live practice and hand-enabled uploaded-video template generation.

The `holistic_landmarker.task` asset is already expected to exist at:

```text
app/src/main/assets/holistic_landmarker.task
```

Implementation rules for this model:

- Use pose output for the existing body score.
- Use left/right hand output for optional finger/hand feedback.
- Ignore all face output. Do not store, score, render, or upload face landmarks.
- Keep old pose-only templates working. If a template has no hand data, the final score must remain body-only.

Uploaded dance video analysis uses two paths:

```text
Normal single-dancer hand-enabled generation:
holistic_landmarker.task
→ extract pose + leftHand + rightHand
→ discard face
→ save optional handTracks with the pose template

High-recall multi-dancer hand-enabled generation:
person_detector_yolo.tflite
→ detect dancer boxes
→ track dancer IDs
→ crop each dancer with padding
→ holistic_landmarker.task on each crop
→ extract pose + leftHand + rightHand
→ discard face
→ remap pose and hand landmarks back to full-frame coordinates
→ save pose + optional hand template per dancer track

Fallback upload generation:
person_detector_yolo.tflite + pose_landmarker_full.task
→ body-only templates when Holistic fails or hand mode is disabled
```

`pose_landmarker_full.task` should no longer be treated as the preferred high-recall model when the goal is multi-dancer + hands/fingers. It is the fallback body-only path.

## Permissions Used

The app declares support for:

- camera access
- internet access
- media read access
- notifications
- exact alarm scheduling
- boot completed handling

Camera-heavy features work best on a real device.

## Existing Test Coverage

The repository currently contains tests around:

- analytics view model and repository behavior
- dance library repository logic
- Firebase authentication repository logic
- video upload flows
- Compose UI and instrumentation flows
- dance session DAO and AR practice screens

## Notes

- The app is implemented as a single Android module.
- Hilt is used for dependency injection.
- Firebase App Check debug provider is enabled in debug builds.
- Release minification is currently disabled.
