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
│       │   │   └── pose_landmarker_lite.task
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
  - MediaPipe model used by pose detection
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
