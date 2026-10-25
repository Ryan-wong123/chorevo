# Deploying and releasing Chorevo

Everything needed to deploy the Firebase backend and to release the Android
app on Google Play, in order. Tick items off as you go.

## Release checklist

**Code (in this repo)**
- [ ] Final application ID set (see [Application ID](#1-application-id))
- [ ] Contact line filled in on `public/delete-account.html`
- [ ] Privacy policy written and published

**Keys and signing**
- [ ] Upload key created and stored safely
- [ ] Upload key added to GitHub secrets
- [ ] Play App Signing turned on

**Firebase Console**
- [ ] New Android app registered with the final ID, `google-services.json` replaced
- [ ] SHA-1 and SHA-256 of the upload key **and** the Play app signing key added
- [ ] Password policy: at least 8 characters; email enumeration protection on
- [ ] App Check: Play Integrity registered, metrics checked, then enforced

**Google Cloud Console**
- [ ] Functions allowed to sign group video links ([one-time setup](#one-time-google-cloud-permissions))
- [ ] Storage rules allowed to read Firestore ([one-time setup](#one-time-google-cloud-permissions))
- [ ] Android API key restricted to the package name and SHA-1
- [ ] Budget alerts, and alerts on function errors

**GitHub**
- [ ] `main` protected
- [ ] Required reviewers on the `production` environment
- [ ] Secret scanning on
- [ ] Billing working, so CI and automatic deploys run

**Play Console**
- [ ] Privacy policy URL
- [ ] Data safety form
- [ ] Account deletion URL
- [ ] Exact alarm declaration
- [ ] Foreground service (`dataSync`) declaration

**Testing and rollout**
- [ ] Signed release build tested on real phones
- [ ] 16 KB page size check
- [ ] Internal testing track, then production

---

## Deploying the Firebase backend

### Automatically (normal case)

`.github/workflows/firebase.yml` runs the Cloud Function tests and the
Firestore/Storage rules tests on every push and pull request. On pushes to
`main` it then deploys indexes, Storage rules, functions and Hosting, and the
Firestore rules last. Setting up its service account is described in the
README under **Automatic Firebase deploys**.

If GitHub Actions can't run (for example a billing problem), deploy by hand.

### One-time Google Cloud permissions

Two features need permissions that a deploy doesn't set up by itself. Do
both **before** deploying the functions and Storage rules that use them, or
group videos and other people's dances stop loading.

1. **Signing group video links** (`groupMediaUrls`). Group videos are played
   through links that expire after an hour; the functions sign them with
   their service account.
   - Google Cloud Console → APIs & Services → enable **IAM Service Account
     Credentials API**.
   - IAM & Admin → Service Accounts → the functions' runtime account (by
     default **Default compute service account**,
     `<project-number>-compute@developer.gserviceaccount.com`) →
     Permissions → Grant access → principal: that same account, role:
     **Service Account Token Creator**.
2. **Storage rules reading Firestore** (only a private dance's creator can
   read its pose template). The first deploy of these Storage rules asks
   to grant Cloud Storage for Firebase access to Firestore; answer **yes**.
   If you deploy from the console or non-interactively, open Firebase
   Console → Storage → Rules once and accept the prompt, or grant the
   **Firebase Rules Firestore Service Agent** role to
   `service-<project-number>@gcp-sa-firebasestorage.iam.gserviceaccount.com`
   in IAM.

Check afterwards: a group video plays from another account, and practising
someone else's public dance works.

### Release order for private group videos

App versions from before short-lived group video links read the permanent
links stored on each post. Once `lockGroupPostMedia` and the nightly
`refreshDiscovery` catch-up run, those links stop working, so **older apps
can no longer play group videos**. Release the updated app first, give users
time to update, then deploy the functions.

### Room database schemas (once, then with every schema change)

The app exports its Room schema to `app/schemas/` so future versions can
ship tested migrations instead of wiping local data. After pulling this
change, build once (`./gradlew assembleDebug`) and commit the generated
`app/schemas/` folder. Whenever `AppDb`'s `version` goes up, add a
`Migration` and commit the new schema file with it.

### By hand

From the repository root, on an up-to-date `main`, with the Firebase CLI
signed in to an account with access to `chorevo-dea05`:

```
cd functions && npm ci && npm test && cd ..
cd rules-tests && npm ci && npm test && cd ..

# 1. Everything except the Firestore rules
firebase deploy --project chorevo-dea05 \
  --only firestore:indexes,storage,functions,hosting --non-interactive --force

# 2. Firestore rules last, so they never see documents without the fields they need
firebase deploy --project chorevo-dea05 --only firestore:rules --non-interactive
```

Deploy only what changed if you prefer, e.g. `--only functions` or
`--only hosting`.

---

## Building the release

### Upload key (once)

Release builds are signed with your **upload key** when it is configured;
otherwise the release APK is unsigned and can't be installed. The key is never
committed (`*.jks`, `*.keystore` and `keystore.properties` are gitignored).

1. Create the key and keep it, and its passwords, somewhere safe (a password
   manager). If you lose it, you have to ask Google to reset the upload key.
   ```
   keytool -genkeypair -v -keystore chorevo-upload-key.jks -alias upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Local builds:** copy `keystore.properties.example` to
   `keystore.properties` and fill it in. `./gradlew assembleRelease` then
   produces a signed, installable APK, and `./gradlew bundleRelease` produces
   the App Bundle for Play.
3. **CI:** add these repository secrets (GitHub → Settings → Secrets and
   variables → Actions):
   - `RELEASE_KEYSTORE_BASE64`: the output of `base64 -w0 chorevo-upload-key.jks`
     (on macOS, `base64 -i chorevo-upload-key.jks`)
   - `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`upload`) and
     `RELEASE_KEY_PASSWORD`

   With these set, the Android CI build job signs the release APK and also
   uploads a signed `chorevo-release-aab` for Play. Secrets aren't available
   to pull requests from forks, so those builds stay unsigned.
4. Get the key's fingerprints (for Firebase and `assetlinks.json`):
   `keytool -list -v -keystore chorevo-upload-key.jks -alias upload`.

Signed release builds also upload their R8 mapping file to Crashlytics, so
crash reports from users are readable. Unsigned builds skip that upload.

---

## Before the first release

### 1. Application ID

`com.example.chorevo` is rejected by Play.

1. Choose the final ID (for example `com.yourname.chorevo`) and set
   `applicationId` in `app/build.gradle.kts`.
2. Update `public/.well-known/assetlinks.json` and `ANDROID_PACKAGE` in
   `public/js/*.js`, then deploy Hosting.
3. In Firebase Console → Project settings → Your apps, add an Android app with
   the new ID and replace `app/google-services.json` with the downloaded file.

### 2. Play App Signing and fingerprints

1. In Play Console, create the app and turn on **Play App Signing**. Google
   re-signs the app for users with its app signing key; yours stays the upload
   key.
2. Copy the SHA-1 and SHA-256 of **both** keys: yours (see above) and the app
   signing key (Play Console → Test and release → App integrity).
3. Add all four to the Firebase Android app (Project settings → Your apps →
   Add fingerprint). Google sign-in and App Check need them.
4. Add both SHA-256 values to `public/.well-known/assetlinks.json` (App
   Links), then deploy Hosting.

### 3. Firebase Authentication

Firebase Console → Authentication → Settings:
- Password policy: at least 8 characters (the app already asks for 8).
- Keep **email enumeration protection** on.

### 4. App Check

1. Firebase Console → App Check: register the Android app with **Play
   Integrity** (this needs Play Console linked to the Firebase project).
2. Register your debug token for development builds (it appears in Logcat on
   first launch of a debug build).
3. Watch the App Check metrics for a few days after release. When almost all
   requests are verified, **enforce** App Check on Firestore, Storage and
   Authentication.

### 5. Google Cloud Console

- **API key:** APIs & Services → Credentials → the Android key: restrict it to
  Android apps with your package name and SHA-1, and to only the Firebase APIs
  the app uses.
- **Budget alerts:** Billing → Budgets & alerts: add a monthly budget with
  email alerts (e.g. at 50%, 90% and 100%).
- **Function alerts:** Monitoring → Alerting: alert on Cloud Functions error
  rate and unusual invocation counts.

### 6. GitHub

Repository → Settings:
- **Branches:** protect `main` (require pull requests and passing checks).
- **Environments → production:** add required reviewers, so deploys need an
  approval.
- **Code security:** turn on secret scanning (and push protection).
- **Billing:** make sure Actions can run; otherwise CI and automatic deploys
  are skipped.
- Prefer Workload Identity Federation over a JSON key for
  `FIREBASE_SERVICE_ACCOUNT` when you have time.

### 7. Play Console declarations

Play Console → your app → Policy and programs → App content.

**Privacy policy:** publish it at a public URL (for example a page on
`chorevo-dea05.web.app`) and enter the URL. It must match the Data safety
answers below.

**Account deletion:** enter
`https://chorevo-dea05.web.app/delete-account.html`. Fill in the page's
contact line first (it is still a placeholder) and deploy Hosting.

**Exact alarms:** declare that the app uses `SCHEDULE_EXACT_ALARM` for the
user's daily practice reminder at the time they choose.

**Foreground service:** declare the `dataSync` foreground service type. Use:
"After a user uploads a dance video, the app downloads it and analyses the
dancers' movements in the background, showing an 'Analysing your dance'
notification, so the dance becomes available for practice. It starts only
from a user's upload."

**Data safety:**

| Question | Answer |
|---|---|
| Does the app collect or share required user data types? | Yes |
| Is all user data encrypted in transit? | Yes (HTTPS to Firebase) |
| Account creation methods | Username and password; OAuth (Google) |
| Can users request deletion? | Yes: Settings → Delete account, and the deletion URL above |

Tick only these data types. For every one: **Shared: No** (Firebase processes
data on your behalf, which is not sharing) and **Processed ephemerally: No**.

| Category → type | Required or optional | Purposes |
|---|---|---|
| Personal info → Name | Required | App functionality, Account management |
| Personal info → Email address | Required | App functionality, Account management |
| Personal info → User IDs (account ID, username) | Required | App functionality, Account management, Analytics |
| Photos and videos → Photos (profile photo) | Optional | App functionality |
| Photos and videos → Videos (uploaded dances, group videos) | Optional | App functionality |
| Health and fitness → Fitness info (practice sessions, scores, streaks) | Required | App functionality |
| App activity → App interactions (analytics events) | Required | Analytics |
| App activity → Other user-generated content (titles, descriptions, comments, group posts and replies) | Optional | App functionality |
| App activity → Other actions (likes, group joins) | Optional | App functionality |
| App info and performance → Crash logs | Required | Analytics |
| App info and performance → Diagnostics | Required | Analytics |
| Device or other IDs → Device or other IDs (push token, Firebase installation and analytics IDs) | Required | App functionality, Analytics |

Not collected: the camera feed (practice pose detection runs on the phone and
frames never leave it), location, contacts, and financial info. Firebase's
"Data disclosure for Google Play" pages list what each SDK collects, if you
want to double-check.

Update this table whenever the app starts collecting something new.

---

## Testing and rollout

1. **Release build on real phones:** install a signed `assembleRelease` and
   check sign-in (email and Google), the feed, practice, uploads (including a
   3–5 minute video), groups, invites, push notifications and reminders. Unit
   tests don't run R8, so this is the only check that shrinking didn't break
   anything.
2. **16 KB pages:** Play requires native libraries to support 16 KB page
   sizes for Android 15+ targets. Check TensorFlow Lite, MediaPipe and
   Filament with the APK Analyzer or `zipalign -c -P 16 -v 4 app-release.apk`.
3. **Internal testing:** upload the App Bundle (`chorevo-release-aab` from CI,
   or `./gradlew bundleRelease`) to the internal testing track and have a few
   people use it for a few days.
4. **Production:** promote the build, ideally as a staged rollout (e.g. 10%,
   then 50%, then 100%).

## After release

- **Crashlytics** (Firebase Console → Crashlytics): new crashes and handled
  errors, readable thanks to the uploaded mapping files.
- **Analytics** (Firebase Console → Analytics → Events): sign-ups, uploads,
  practice sessions and group activity. The event list is in
  `architecture.md` → Observability.
- **App Check** metrics, until enforcement is on.
- **Billing** and function alerts.
