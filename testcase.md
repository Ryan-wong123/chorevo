# Chorevo test cases

This document describes what every test in the repository does. It is organised by test suite:

1. [App unit tests](#1-app-unit-tests-jvm-appsrctest) (JVM, `app/src/test`)
2. [Pose detection unit tests](#2-pose-detection-unit-tests-appsrctestposedetection) (JVM, `app/src/test/.../posedetection`)
3. [Instrumented tests](#3-instrumented-tests-appsrcandroidtest) (device or emulator, `app/src/androidTest`)
4. [Cloud Functions tests](#4-cloud-functions-tests-functionstest) (Node, `functions/test`)
5. [Security rules tests](#5-security-rules-tests-rules-tests) (Firebase emulator, `rules-tests`)

How to run each suite:

| Suite | Command |
|---|---|
| App unit tests | `./gradlew :app:testDebugUnitTest` |
| Instrumented tests | `./gradlew :app:connectedDebugAndroidTest` (needs a device or emulator) |
| Cloud Functions tests | `cd functions && npm test` |
| Security rules tests | `cd rules-tests && npm test` (starts the Firestore and Storage emulators) |

Conventions used by the Kotlin tests:

- Frameworks are JUnit4, MockK and kotlinx-coroutines-test. Firestore, Storage and Auth are mocked with MockK, and `Tasks.forResult` / `Tasks.forException` stand in for Play-services Tasks.
- ViewModel tests install a test dispatcher on `Dispatchers.Main`, either through `MainDispatcherRule` or by calling `Dispatchers.setMain` directly. With a `StandardTestDispatcher`, coroutines only run when the test calls `advanceUntilIdle()`, `runCurrent()` or `advanceTimeBy()`.
- Repository tests that wrap Firestore snapshot listeners capture the listener in a MockK `slot` and then fire `listener.onEvent(snapshot, error)` by hand to simulate Firestore pushing data.
- A "pose" is a flat `List<Float>` with 4 values per landmark (x, y, z, visibility). A full body pose has 33 landmarks and a hand has 21. Timestamps are in milliseconds.

---

## 1. App unit tests (JVM, `app/src/test`)

Base path: `app/src/test/java/com/example/chorevo/`

### Test helper

**`testing/MainDispatcherRule.kt`** has no tests. It is a JUnit `TestWatcher` that calls `Dispatchers.setMain(dispatcher)` before each test and `Dispatchers.resetMain()` afterwards. The default dispatcher is an `UnconfinedTestDispatcher`, and many tests pass a `StandardTestDispatcher()` to get explicit control over scheduling. It lets ViewModels that use `viewModelScope` run on the JVM.

### analytics/

#### `AnalyticsDashboardViewModelTest` (4 tests)
Under test: `AnalyticsDashboardViewModel`, built on a mocked `AnalyticsRepository`.

| Test | What it does |
|---|---|
| `MY_DANCE - sessions flow comes from repo observeSessions` | Stubs `observeSessions` to return one summary and `observeDetail` to return null. Calls `setArgs(MY_DANCE, myUid="u1")`, collects `vm.sessions` and advances until idle. Verifies `observeSessions` was called exactly once, which confirms the VM sources its session list from the repository. |
| `setArgs resets selectedDetail to null until a session is selected` | After `setArgs`, asserts `selectedDetail` is null. Guards against stale detail from a previous dashboard leaking into a new one. |
| `selectSession triggers observeDetail with the selected sessionKey` | Collects `selectedDetail`, calls `selectSession("s1")`, and verifies `observeDetail(args, "s1")` was called. The detail flow is lazy, so the test collects it manually. |
| `OTHER_ANALYTICS - observeDetail accepts sessionKey with docPath` | In educator mode with a composite key `"sess123\|\|users/learner1/danceLearningSessions/sess123"`, verifies the key is passed to `observeDetail` unchanged so the repository can locate another user's session document. |

#### `AnalyticsRepositoryTest` (5 tests)
Under test: `AnalyticsRepository(db)` with a relaxed `FirebaseFirestore` mock and a statically mocked `Log`. Helper functions build `DocumentSnapshot` mocks for summaries and details. The detail analysis map deliberately holds timeline points and segments out of order.

| Test | What it does |
|---|---|
| `observeSessions_myDance_emitsMappedSummaries` | Mocks the chain `users/u1/danceLearningSessions` → `orderBy("tsStart", DESC)` → `limit(60)` → `addSnapshotListener`, then feeds two docs. Asserts two mapped summaries with correct `sessionId`, `activityName` and `tsStart`, in order. Verifies the registration's `remove()` is called once so the listener is cleaned up. |
| `observeDetail_otherAnalytics_blankPath_emitsNull` | Uses the session key `"session123\|\|"` (empty doc path) in educator mode. Asserts the emitted detail is null, so a malformed key neither crashes nor queries Firestore. |
| `observeSessions_otherAnalytics_usesDocDisplayNameWithoutExtraFetch` | Educator mode queries `collectionGroup("danceLearningSessions").whereEqualTo("analysis.educatorUid", ...)`. The doc already has `learnerDisplayName="Ava"`. Asserts the composite `sessionId` and the name `"Shuffle • Ava"`. Verifies `db.collection("users")` is never called (no extra profile fetch) and the listener is removed. |
| `observeDetail_otherAnalytics_fetchesFallbackName_andParsesAnalysis` | The detail doc has no learner name, so the repo falls back to `users/learner42` ("Jamie") and reads `danceLibrary/danceA` (exists, enabled, has a `videoUrl`). Asserts the name `"Footwork • Jamie"`, the analysis fields (`danceId`, `educatorUid`, `videoAvailable=true`, current `videoUrl`), parsed feedback messages, timeline points sorted by time, and performance segments sorted by start with labels mapped to the enum (`OK`, `BAD`). Verifies the user doc and dance doc are each fetched once. |
| `observeDetail_otherAnalytics_deletedLinkedDance_keepsAnalyticsButHidesVideo` | The linked dance doc does not exist. Asserts the analysis is still returned (timeline and segments) while `videoAvailable=false`, `videoUrl=null` and `videoUnavailableReason="This video has been deleted or removed."`. Verifies the profile is not fetched and the dance doc is fetched once. Deleting a video must not destroy the educator's analytics. |

#### `AnalyticsStatsTest` (7 tests)
Under test: the pure functions `bodyFocusFromFeedback`, `computeOverviewStats` and `formatPracticeTime`. A `session(...)` factory builds summaries; smoothness is `alignment/2`, timing is `alignment/3`, and tests use `ZoneOffset.UTC`.

| Test | What it does |
|---|---|
| `bodyFocus_countsCorrectionsPerArea_andIgnoresPraise` | Feeds 8 feedback strings (left-arm raise/lower, right-leg straighten, posture reset, hand-shape, "Left hand not detected", and two praise lines). Asserts `LEFT_ARM=2`, `RIGHT_LEG=1`, `POSTURE=1`, `HANDS=2` and `RIGHT_ARM` is null. Corrections are keyword-mapped to body areas, hand-detection failures count toward `HANDS`, and praise is ignored. |
| `overview_emptySessions_isAllZero` | An empty list gives `sessionCount=0`, `averageScore=0.0`, `totalPracticeMs=0`, null `scoreChangePoints`, empty trend and null `topFocusArea`. Protects against divide-by-zero and empty-state UI. |
| `overview_computesAverageBestTimeAndOrderedTrend` | Sessions arrive newest-first with alignments 0.9, 0.5 and 0.7. Asserts count 3, average 0.7, best 0.9, total 180,000 ms, and a chronological trend `[0.5, 0.7, 0.9]`. `scoreChangePoints` is null with fewer than 4 sessions. |
| `overview_scoreChange_comparesRecentHalfWithOlderHalf` | Four sessions (0.40, 0.50, 0.70, 0.80). The older half averages 0.45 and the recent half 0.75, so `scoreChangePoints == 30`. |
| `overview_trendKeepsOnlyMostRecentPoints` | 20 sessions. The trend size equals `AnalyticsOverviewStats.TREND_POINTS` and the last value is the newest (0.20). |
| `overview_groupsSessionsByDay_andSumsBodyFocus` | Sessions on 1970-01-01 (two) and 1970-01-02 (one). Asserts `sessionsPerDay` 2 and 1, body focus summed (`LEFT_ARM=3`), and `topFocusArea == LEFT_ARM`. |
| `formatPracticeTime_formatsHoursMinutesSeconds` | 45,000 ms → `"45s"`, 12 minutes → `"12m"`, 65 minutes → `"1h 05m"` (zero-padded minutes). |

### community/

#### `GroupModelsTest` (8 tests)
Pure tests of group helpers and models.

| Test | What it does |
|---|---|
| `normalizeInviteCode_uppercasesAndDropsLookalikesAndSeparators` | `"abcd-2345"` → `"ABCD2345"`, `" ab cd 23 45 extra"` → `"ABCD2345"`, and `"A0B1CIOL"` → `"ABC"`. Lowercase is uppercased, separators and spaces are dropped, and the look-alike characters 0, O, 1, I and L are removed. |
| `inviteAlphabet_hasNoLookalikes` | None of `0`, `O`, `1`, `I`, `L` appears in `INVITE_CODE_ALPHABET`, which keeps codes readable. |
| `isValidGroupName_matchesRulesLimits` | `"ab"` and `"   ab   "` are invalid (trimmed length 2), `"abc"` and a 60-character name are valid, and 61 characters is invalid. Mirrors the server rules (3 to 60 after trimming). |
| `composer_canPostNeedsContentAndNotPosting` | `canPost` is false for an empty or whitespace-only composer, true for `"hi"`, and false for `"hi"` while `isPosting=true`. |
| `uiState_derivesMembershipAndOpenPost` | With an owner membership, two posts and `openPostId="p2"`, asserts `isMember`, `isOwner` and `openPost?.id == "p2"`. A default `GroupsUiState().isMember` is false. |
| `inviteCodeFromText_readsInviteLinksAndBareCodes` | Extracts `ABCD2345` from the `web.app` link, the `firebaseapp.com` link (lowercase, trailing slash), the `chorevo://join/…` link, a link with `?utm=x`, and bare padded or hyphenated codes. QR scans and deep links rely on this. |
| `inviteCodeFromText_rejectsOtherCodes` | Returns null for null, empty, a foreign host, the wrong path (`/dance/…`), codes of length 4 or 9, look-alike characters and plain prose. Guards against untrusted hosts and junk input. |
| `postKind_fromKey_defaultsToText` | `"video"` maps to `VIDEO`, and null or an unknown key maps to `TEXT`. |

#### `GroupsViewModelTest` (13 tests)
Under test: `GroupsViewModel(repo, uploads, usernames)` with relaxed mocks and `MutableStateFlow`s for invitations and membership. The repo is stubbed so `observeMyGroups("me")` and `loadPublicGroups` return `[pub]`, group `g1` has post `p1`, and `uploads.loadVideos("me")` returns one public dance, one private dance and one whose template is still `processing`.

| Test | What it does |
|---|---|
| `bind_loadsMyGroupsAndDiscover` | After `bind("me","Me",null)`, `myGroups` and `discoverGroups` are both `["g1"]` and `isLoadingMyGroups` is false. |
| `openGroup_observesPostsAndOffersOnlyPublicReadyDances` | After `openGroup("g1")`: correct `openGroupId`, group name, posts `["p1"]`, and `myDances == ["public"]`. Private dances and dances still generating a template are excluded from what can be attached to a post. |
| `submitPost_requiresMembership_andPicksTheRightKind` | Without membership, `createTextPost` is never called. With membership, text posts call `createTextPost("g1", Author("me","Me",null), "hello")` and clear the composer. After `attachDance(dance)`, the next submit calls `createDancePost`. |
| `submitPost_failureKeepsTheDraftAndShowsAMessage` | `createTextPost` fails ("offline"). The draft text is kept, `isPosting` is false and a user-facing `message` is set. |
| `joinWithCode_rejectsShortCodesWithoutCallingTheRepository` | `joinWithCode("abc")` never reaches the repo and sets a `message`. Validation happens client-side. |
| `joinWithCode_normalisesAndOpensTheGroup` | `"abcd-2345"` is normalised to `"ABCD2345"`, passed to `repo.joinWithCode`, and the returned `"g1"` becomes `openGroupId`. |
| `bannedMembers_areLoadedOnlyForTheOwner_andUnbanningRemovesThem` | A plain member's `loadBannedMembers` and `unbanMember` make no repo calls. As owner, `["b1","b2"]` load, and unbanning `b1` leaves only `["b2"]` in state. |
| `onlyTheOwnerCanDeleteTheGroup` | A member's `deleteOpenGroup` does nothing. As owner, `deleteGroup("g1")` is called and `openGroupId` becomes null. |
| `invitations_areObserved_andAcceptingOpensTheGroup` | The invitations flow emits one invitation, which appears in state. `acceptInvitation(inv)` calls the repo, opens group `g1` and resets `answeringInvitationId` to null. |
| `declineInvitation_deletesIt` | Verifies `repo.declineInvitation(inv)` is called. |
| `inviteSearch_hidesMyself_andSendingMarksInvited` | Searching `"@An"` is normalised to `"an"`; results show only `ana` (self is filtered out). `sendInvitation(ana)` calls the repo and adds `ana` to `invitedUids`. |
| `sendInvitation_needsMembership` | Without membership, `sendInvitation` never calls the repo. |
| `openPost_thenCloseGroup_clearsEverything` | `openPost("p1")` sets `openPost`. `closeGroup()` then clears `openGroupId` and `openPostId` and empties `posts`. |

### core/

#### `AppUiStateTest` (11 tests)
Pure tests of the `AppUiState` data class, using a `makeProfile` helper.

| Test | What it does |
|---|---|
| `displayName_withValidProfileName_returnsProfileDisplayName` | `"Alice"` gives `"Alice"`. |
| `displayName_withNullProfile_returnsUser` | A null profile gives the fallback `"User"`. |
| `displayName_withNullDisplayName_returnsUser` | A null name gives `"User"`. |
| `displayName_withBlankDisplayName_returnsUser` | `"   "` gives `"User"`. |
| `displayName_withEmptyDisplayName_returnsUser` | `""` gives `"User"`. |
| `displayName_trailingAndLeadingSpaces_areTrimmed` | `"  Bob  "` gives `"Bob"`. |
| `isSessionReady_trueWhen_authCheckDone_andProfileSet` | True only when `didAuthCheck` is true and a profile exists. |
| `isSessionReady_falseWhen_authCheckNotDone` | False when the auth check is not done, even with a profile. |
| `isSessionReady_falseWhen_profileIsNull` | False with a null profile, even if the auth check is done. |
| `isSessionReady_falseWhen_bothAuthCheckAndProfileMissing` | False when both are missing. |
| `defaultState_hasAuthScreen_andNotReady` | The default state is `Screen.AUTH`, `didAuthCheck` false, `isSessionReady` false and `displayName == "User"`. |

#### `AppViewModelTest` (34 tests)
Under test: `AppViewModel`, the top-level navigation and session holder. Setup uses a `StandardTestDispatcher`, relaxed mocks (`Context`, `AlarmManager`, `FirebaseAuthRepository`, `AutoRetryUploaderService`, `NotificationPreferencesDataStore`, `DanceLibraryRepository`, `PushTokens`) and a real `ReminderScheduler`. By default there is no signed-in user and the settings flows are push=false, time 9:00, theme=null, onboarding=false.

*Init and auth*

| Test | What it does |
|---|---|
| `init_withNullProfile_setsDidAuthCheck_andScreenToAuth` | With no signed-in user: `didAuthCheck` true, screen `AUTH`, profile null. |
| `init_withProfile_setsScreenToHome_andStoresProfile` | With a stored profile, the VM ends on `HOME` with the profile stored (the auto-login path). |
| `onAuthed_setsScreenToHome_andStoresProfile` | `onAuthed(profile)` moves to `HOME` and stores the profile. |
| `onAuthed_overwritesPreviousProfile` | A second `onAuthed` replaces the profile. |

*Logout*

| Test | What it does |
|---|---|
| `logout_clearsProfile_andSetsScreenToAuth` | After `logout()`: screen `AUTH`, profile null. |
| `logout_clearsSelectedDanceItem` | Logout also clears `selectedDanceItem` and the profile. |
| `logout_fromAnyScreen_alwaysGoesToAuth` | From `LEADERBOARD`, logout lands on `AUTH`. |

*Dance and navigation*

| Test | What it does |
|---|---|
| `pickDance_setsScreenToDancePractice_andStoresItem` | Screen becomes `DANCE_PRACTICE` and the item is stored. |
| `pickDance_replacesAnyPreviouslySelectedItem` | Picking a second dance replaces the first. |
| `backToHome_setsScreenToHome_andClearsDanceItem` | `backToHome` goes to `HOME` and clears the item. |
| `backToHome_fromUpload_goesToHome` | From `UPLOAD`, `backToHome` goes to `HOME`. |
| `openUpload_setsScreenToUpload` | Screen becomes `UPLOAD`. |
| `openLeaderboard_setsScreenToLeaderboard` | Screen becomes `LEADERBOARD`. |
| `openProfile_setsScreenToProfile` | Screen becomes `PROFILE`. |
| `openSettings_setsScreenToSettings` | Screen becomes `SETTINGS`. |
| `authThenPickDanceThenBackToHome_restoresHomeWithNoItem` | The sequence auth → pick dance (`DANCE_PRACTICE`) → back ends on `HOME` with no item. |
| `multipleTabSwitches_lastOneWins` | After several tab switches, the last target (`LEADERBOARD`) wins. |
| `backToHome_doesNotAffectProfile` | The profile survives navigation. |

*Share links and deep links*

| Test | What it does |
|---|---|
| `openDanceById_whenSignedIn_showsSharedVideoInHomeFeed` | `getDanceById("shared1","uid1")` returns a dance. After `openDanceById("shared1")` the screen is `HOME`, `sharedDance` equals the dance and `selectedDanceItem` is null. A deep-linked video appears in the feed rather than opening practice mode. |
| `backToHome_clearsSharedDance` | After opening a shared dance, `backToHome()` sets `sharedDance` to null. |
| `openGroupInvite_whenSignedIn_opensGroupsWithTheCode` | When signed in, `openGroupInvite("ABCD2345")` moves to `COMMUNITY` with `pendingGroupInvite` set. `clearGroupInvite()` resets it. |
| `openGroupInvite_whileSignedOut_waitsForSignIn` | While signed out the screen stays `AUTH` and the invite is not applied. After `onAuthed`, the screen becomes `COMMUNITY` and the invite is applied. The invite is queued across login. |
| `openGroupInvitations_fromAPush_opensGroups_evenBeforeSignIn` | A push-notification deep link stays on `AUTH` while signed out, then goes to `COMMUNITY` after `onAuthed`. |
| `onUsernameSaved_updatesTheProfile` | `onUsernameSaved("ryan_w")` updates `profile.username`. |

*System back (`navigateBack`)*

| Test | What it does |
|---|---|
| `navigateBack_fromPractice_goesHome_andClearsDance` | Returns true, goes to `HOME` and clears the dance. |
| `navigateBack_fromTabs_goesHome` | For upload, community, leaderboard and profile, back returns true and goes to `HOME`. |
| `navigateBack_fromSettingsAndChallenges_goesToProfile` | Back from `SETTINGS` and from weekly challenges goes to `PROFILE`. |
| `navigateBack_onHomeOrAuth_returnsFalse_soSystemCanExit` | On `HOME` and `AUTH` it returns false and the screen is unchanged, so the system can exit the app. |

*Theme and onboarding*

| Test | What it does |
|---|---|
| `themeMode_defaultsToDark_whenNothingSaved` | A null saved value gives `DARK`. |
| `themeMode_loadsSavedChoice` | A saved `"light"` gives `ThemeMode.LIGHT`. |
| `setThemeMode_updatesState_andSaves` | `setThemeMode(SYSTEM)` updates state and calls `settings.setThemeMode("system")`. |
| `themeMode_fromKey_unknownFallsBackToDark` | `null` and `"purple"` map to `DARK`; `"light"` maps to `LIGHT`. |
| `onboarding_notSeen_isLoadedAsFalse` | `onboardingSeen` is false when the store says false. |
| `completeOnboarding_marksSeen_andSaves` | `completeOnboarding()` sets `onboardingSeen=true` and calls `settings.setOnboardingSeen()`. |

#### `TrustedMediaUrlTest` (2 tests)
Under test: `isTrustedMediaUrl`, the allow-list for media playback.

| Test | What it does |
|---|---|
| `acceptsThisAppsStorageDownloadUrls` | A `https://firebasestorage.googleapis.com/v0/b/chorevo-dea05.firebasestorage.app/o/…?alt=media&token=…` URL is accepted. |
| `rejectsOtherHostsAndLocalSchemes` | Rejects null, empty, plain `http`, foreign hosts, look-alike hosts (`firebasestorage.googleapis.com.evil.example`), `file://`, `content://` and non-URLs. Stops user-supplied URLs from pointing the player at local files or other servers. |

### danceactivity/

#### `SessionRewardsTest` (3 tests)
Under test: `sessionRewardsFrom(...)` and `AchievementViewModel(AchievementRepository, SessionRewardsRepository)`. The `getEarnedBadges("u1")` call returns `{FIRST_STEPS, DEDICATED}`.

| Test | What it does |
|---|---|
| `parsesTheServerSummary_skippingUnknownBadges` | A map with `sessionPoints` 83, `totalPointsAfter` 603, `newBadges` `["DEDICATED","NOT_A_BADGE"]` and one completed challenge. Asserts points, total, badges `[DEDICATED]` (the unknown badge is dropped) and the challenge reward 40. Keeps old clients from crashing on badges added later. |
| `showsWhatTheServerAwarded_andPassesItOn` | Calls `awaitSessionRewards("u1","s1")` with a not-yet-emitted flow. The dialog is hidden while waiting. After the flow emits (83 points, 480→563 total, badge `DEDICATED`), asserts `showAchievementDialog`, `sessionPoints`, `totalPoints`, `newlyUnlocked`, `leveledUp=true` and `rewardsPending=false`, and that the callback receives the rewards. It uses `runCurrent()` rather than `advanceUntilIdle()` to avoid skipping the timeout. |
| `withoutAResult_inTime_showsThePendingState` | The flow stays null and 21 s pass. The dialog shows with `rewardsPending=true` and `sessionPoints=0`, and the callback is not called. After the 20 s timeout the UI says rewards are still being computed rather than hanging. |

### dancelibrarylist/

#### `DanceLibraryDiscoveryViewModelTest` (5 tests)
Under test: `DanceLibraryListViewModel` (relaxed streak and leaderboard repos). `loadCategories()` returns one `hiphop` category and `forYouSeed` is fixed at 0.25.

| Test | What it does |
|---|---|
| `refresh_loadsChipsAndFirstPage` | After `refresh()`: visible ids `[a,b]`, categories `[hiphop]`, `endReached=true`, `isLoading=false`. |
| `loadMore_appendsNextPage_withoutDuplicates_andStopsAtEnd` | Page 1 is a,b with a cursor; page 2 is b,c with none. Result is `[a,b,c]` (duplicate dropped) with `endReached=true`. A further `loadMore` makes no request (exactly 2 `loadFeedPage` calls). |
| `emptyPagesAfterSearchFiltering_keepFetchingUntilResults` | The first page is empty but has a cursor and the second contains `z`. After `refresh()` the visible items are `["z"]`. The VM keeps paging when client-side filtering empties a page, so users don't see a false "no results". |
| `selectingTabOrChip_reloadsWithThatFilter` | `selectTab(TRENDING)` loads with `DiscoveryFilter(tab=TRENDING)`. `selectCategory("hiphop")` loads with the chip added. Selecting the active chip again clears `categoryKey`. |
| `search_isDebounced_soOnlyTheFinalTextIsQueried` | Typing `"s"`, `"sa"`, `"salsa"` quickly makes no calls after 100 ms. After idle there is exactly one call with `query="salsa"`. |

#### `DanceLibraryModelTest` (2 tests)

| Test | What it does |
|---|---|
| `defaultsToPublic_soOlderUploadsStayVisible` | A default model has `isPrivate=false`, and `isVisibleTo("anyone")` and `isVisibleTo(null)` are both true. |
| `privateVideo_isOnlyVisibleToItsUploader` | With `VISIBILITY_PRIVATE`, `isPrivate=true`. Visible to the owner (`educatorUid`) but not to another user or null. |

#### `DanceLibraryRepositoryTest` (13 tests)
Under test: `DanceLibraryRepository(firestore, storage, danceSessionDao)` with strict Firestore/Storage/AppDb mocks and a relaxed DAO. A `mockFeedQuery(vararg pages)` helper builds a self-returning Firestore query chain whose `get()` returns the pages in turn.

| Test | What it does |
|---|---|
| `uploadDanceSession_blankUid_throws` | A blank uid throws `IllegalStateException` mentioning "uid is null/blank", preventing writes to a bad path. |
| `loadTemplate_downloadsTemplateFromStorage` | Mocks `danceTemplates/d1.json` bytes. The result is a success with `fps` and `frames` round-tripped. |
| `uploadDanceSession_includesParsedAnalysis_andExtraAnalytics` | Writes to `users/uid123/danceLearningSessions/sess999`. The `analysisJson` string is parsed into a `Map` (not sent as a string), and `extraAnalytics` is included. |
| `saveSessionAndUpload_success_inserts_uploads_marksSynced` | Verifies `dao.insert` once, the Firestore `set` once and `dao.markSynced("sess1")` once. This is the local-first (Room) then cloud (Firestore) flow. |
| `uploadDanceSession_skipsSessionsAlreadyUploaded_andSendsTheTimeZone` | If the remote doc exists, no `set` happens (uploads are idempotent). Otherwise the payload carries `tzOffsetMinutes` as an `Int` so the server can bucket sessions by local day. |
| `getDanceById_privateVideo_isHiddenFromOtherUsers` | A private dance requested by a non-owner fails with "This video is private." |
| `getDanceById_privateVideo_isVisibleToOwner` | The same dance requested by its owner succeeds. |
| `loadFeedPage_newTab_queriesPublicFeedReadyByDate_andEndsOnShortPage` | A ready doc and a doc with `templateGenerationStatus="queued"` come back as a short page. Only the ready one is returned and `next` is null. Verifies the query uses `visibility=="public"`, `feedReady==true` and `orderBy("createdAt", DESC)`. |
| `loadFeedPage_categoryAndSearch_queryKeysAndMatchOtherWordsOnDevice` | Trending tab, category `hiphop`, query `"Groo two"`. Server queries `danceTypeKey=="hiphop"`, `whereArrayContains("searchTokens","groo")` and `orderBy("trendingScore", DESC)`; the remaining words are matched on device, leaving only `"two"`. |
| `loadFeedPage_forYou_startsAtSeed_thenWrapsAroundOnce` | Seed 0.5: page 1 uses `shuffleKey >= 0.5` and returns a cursor with `wrapped=true`; page 2 uses `shuffleKey < 0.5`, is empty and ends the feed. |
| `loadFeedPage_fullPage_returnsCursorAfterLastDoc` | A full page of 2 returns a cursor whose `lastDoc` is the last document. |
| `loadFeedPage_failure_returnsFailure` | When `get()` fails, the repo returns `Result.failure` rather than throwing. |
| `search_typoTolerant_findsMisspelledWords_andRanksExactMatchesFirst` | Query `"hiphp grove"` over "Hip Hop Grove", "Hip Hop Groove" and "Swan Lake". Result is `["exact","typo"]` with the unrelated dance excluded. Verifies the `searchTokens` and `searchTrigrams` (`hip`,`iph`,`php`) queries; scoring and filtering happen on device. |

#### `DiscoveryTest` (9 tests)
Pure functions of the discovery and search layer.

| Test | What it does |
|---|---|
| `danceTypeKey_groupsSpellings` | `"Hip Hop"`, `"hip-hop"` and `" HIPHOP "` all give `"hiphop"`; `"K-Pop"` gives `"kpop"`; blank gives null. |
| `searchToken_isFirstWord_lowercased_andCapped` | `"  Salsa basics"` → `"salsa"`, `"hip-hop"` → `"hip"`, a long word is cut to 15 characters, blank or punctuation-only gives null. |
| `search_matchesAllWordsAcrossFields_caseInsensitive` | For "Salsa Basics" / "Latin" / "Coach Maria": empty, `"salsa"`, `"BASICS maria"` and `"latin"` match; `"salsa ballet"` does not (every word must match). |
| `categoriesFromCounts_sortsByCount_titleCasesLabels_andDropsEmpty` | hiphop=3, kpop=5, ballet=0 gives keys `[kpop, hiphop]` (zero dropped) with labels `"K-Pop"` and `"Hip Hop"`. |
| `filter_isFilteredOnlyForChipOrSearch` | `isFiltered` is true with a query or category and false with only a non-default tab. |
| `editDistance_countsEditsAndSwaps` | Identical strings → 0, `"hiphp"`→`"hiphop"` → 1, adjacent swap `"slasa"`→`"salsa"` → 1 (Damerau-style), `""`→`"abc"` → 3. |
| `closestStandardStyle_fixesSpellingAndTypos` | `"hip-hop"`/`"Hiphp"` → `"Hip Hop"`, `"kpop"` → `"K-Pop"`, `"balet"` → `"Ballet"`; unknown styles and empty input give null. |
| `searchScore_prefersExact_toleratesTypos_requiresEveryWord` | Lower is better. `"sal mar"` (prefix matches across fields) scores 0, `"slasa"` scores 2, non-matching queries give null, empty scores 0. |
| `searchTrigramsOf_matchesServerPieces` | `"hiphp"` → `["hip","iph","php"]`, `"go"` → `["go"]`. Must match how the server indexes `searchTrigrams`. |

### firebase/

#### `AuthAnalyticsTest` (2 tests)
Under test: `AuthViewModel.completeGoogleSignIn` with a recording `Telemetry`.

| Test | What it does |
|---|---|
| `firstGoogleSignIn_isLoggedAsSignUp` | With `isNewAccount=true`, exactly one `SIGN_UP` event is recorded with method `"google"`. |
| `laterGoogleSignIns_areLoggedAsLogin` | With `isNewAccount=false`, exactly one `LOGIN` event with method `"google"`. Keeps funnel metrics accurate. |

#### `FirebaseAuthRepositoryTest` (9 tests)
Under test: `FirebaseAuthRepository` with strict Firebase mocks. A `googleSignInFixture` helper wires a Google sign-in with a stored Firestore profile.

| Test | What it does |
|---|---|
| `signUp_success_returnsUserProfile_andWritesFirestore` | Name `"  Mark  "` is trimmed to `"Mark"`; role is `USER`. Verifies one `users/uid123` merge `set` with `displayName` and `email` but no `role` key (role is server-controlled), and that `leaderboardStats` is never touched. |
| `signUp_failure_returnsFailureResult` | An auth exception ("bad email") gives `Result.failure` with the same message. |
| `signIn_success_readsDisplayName_updatesLastSeen_returnsProfile` | Reads the stored name, role `USER` and null photo. Verifies one sign-in, one doc read, and a merge write containing `lastSeen`. The leaderboard is untouched. |
| `signInWithGoogle_success_syncsFirebaseUserIntoFirestore` | Credential sign-in succeeds and the profile carries uid, email and name. Verifies no `leaderboardStats` access. |
| `signIn_keepsNameAndPhotoTheUserSetInTheApp` | The stored name and photo differ from the Google provider's. The profile and the Firestore write keep the stored values, so Google sign-in never overwrites in-app customisation. |
| `signIn_neverWritesNullNameOrPhoto` | When stored and provider values are all null, the profile name is null and the write contains neither `displayName` nor `photoUrl`, avoiding wiping fields with nulls. |
| `signIn_fillsMissingNameAndPhotoFromProvider` | With an empty stored profile, name and photo come from the provider. |
| `getCurrentUserProfile_nullWhenNoCurrentUser` | `auth.currentUser == null` gives a null profile. |
| `signOut_callsFirebaseAuthSignOut` | `repo.signOut()` calls `auth.signOut()` exactly once. |

#### `UsernameViewModelTest` (4 tests)
Under test: `UsernameViewModel(UsernameRepository)`. `suggest("Ryan Wong")` returns `ryan_wong`; `isAvailable("taken")` is false and everything else is true.

| Test | What it does |
|---|---|
| `start_withoutUsername_fillsInAFreeSuggestion` | `start(current=null, displayName="Ryan Wong")` sets `input == "ryan_wong"` and `canSave=true`. |
| `setInput_normalisesAndChecksAvailability` | Keeping the current name is saveable. `"ab"` sets a `problem` and `canSave=false`. `"@Taken"` is normalised to `"taken"`, reported unavailable and not saveable. |
| `save_claimsTheHandleAndReportsIt` | `save("uid1")` calls `repo.claim("uid1","new_name","old_name")` and the callback receives `"new_name"`. |
| `save_failureShowsTheError` | A failed claim ("@new_name is taken") sets `uiState.error` and leaves `isSaving=false`. |

#### `UsernamesTest` (4 tests)
Pure tests of the `Usernames` utility.

| Test | What it does |
|---|---|
| `normalize_lowercasesAndKeepsOnlyAllowedCharacters` | `"@Ryan Wong"` and `"ryan-wong"` → `"ryan_wong"`, `"ryan.w"` → `"ryan_w"`, `"Ana!#"` → `"ana"`, and 40 characters are capped at 20. |
| `isValid_andProblem_matchTheRules` | `"abc"` and `"dance_crew_2024"` are valid; `"ab"`, `"Abc"` and 21 characters are not. `problem("ab")` is non-null and `problem("abc")` is null. |
| `suggestionFor_usesDisplayName_orFallsBack` | `"Ryan  Wong"` → `"ryan_wong"`; non-Latin names and null fall back to `"dancer"`; long names still yield valid usernames. |
| `withNumber_staysWithinTheLimit` | `withNumber("ryan", 42)` → `"ryan42"`; with 20 `a`s and 1234 the result is exactly 20 characters ending in `1234`. |

### leaderboard/

#### `LeaderboardRepositoryTest` (16 tests)
Under test: `LeaderboardRepository(db)`. The query chain `leaderboardStats.orderBy(metric field, DESC).limit(100).addSnapshotListener` is mocked, and the listener is captured and fired by the test. A `mockStatDoc(...)` helper builds stat documents (the legacy `streakDays` field is set equal to `totalActiveDays`).

*Global scope*

| Test | What it does |
|---|---|
| `observeLeaderboard_global_emitsRankedEntries_orderedByScore` | Docs arrive out of order (5, 10, 7 sessions). Entries come back sorted descending with ranks 1, 2, 3, and the registration's `remove()` is called once so the listener doesn't leak. |
| `observeLeaderboard_global_setsIsMeFlag_forCurrentUser` | The current user's entry has `isMe=true`, others false. The UI uses this for highlighting and `myEntry`. |
| `observeLeaderboard_global_tiedEntries_shareSameRank` | Scores 10, 10, 5 give ranks 1, 1, 3 (standard competition ranking). |
| `observeLeaderboard_global_tiedEntries_sortedAlphabeticallyByDisplayName` | Zoe and Alice tie; Alice comes first, so the order is deterministic. |
| `observeLeaderboard_global_emptySnapshot_returnsEmptyList` | An empty snapshot emits an empty list. |
| `observeLeaderboard_global_firestoreError_emitsEmptyList` | `onEvent(null, error)` emits an empty list rather than throwing or hanging. |
| `observeLeaderboard_global_avgSimilarityMetric_usesCorrectField` | The chain is stubbed for the similarity metric's field, so the test fails if the wrong field is used. Entries are ordered 0.9 then 0.6. |
| `observeLeaderboard_global_activeDaysMetric_ordersCorrectly` | Active-days metric orders 20 before 3. |

*Friends scope*

| Test | What it does |
|---|---|
| `observeLeaderboard_friends_readsFriendsAndSelfById_evenOutsideTop100` | Stats are fetched by id with `whereIn` for `{uid1, uid2}`, so a friend who ranks far down globally still appears. Order is `[uid2, uid1]` with the self entry flagged. |
| `observeLeaderboard_friends_selfAlwaysIncluded` | With no friends, the requested ids are exactly `[uid1]` and the user sees themselves. |

*Friend ids*

| Test | What it does |
|---|---|
| `observeFriendIds_emitsSetOfFriendUids` | Friend docs `uid2` and `uid3` give `{uid2, uid3}`. |
| `observeFriendIds_emitsEmptySet_whenNoFriends` | No docs give an empty set. |
| `observeFriendIds_firestoreError_emitsEmptySet` | An error emits an empty set. |

*Document mapping*

| Test | What it does |
|---|---|
| `observeLeaderboard_usesStreakDaysAsFallback_whenTotalActiveDaysMissing` | A null `totalActiveDays` with legacy `streakDays=7` gives 7, so old documents keep working. |
| `observeLeaderboard_blankDisplayName_defaultsToUser` | A blank name becomes `"User"`. |

#### `LeaderboardViewModelTest` (25 tests)
Under test: `LeaderboardViewModel(repo)` and its `uiState`. Setup uses a `StandardTestDispatcher`, a relaxed repo (default flows are empty), and a collector started in `backgroundScope` for every test.

| Test | What it does |
|---|---|
| `initialState_hasGlobalScope_andSessionsMetric` | Defaults are scope `GLOBAL` and metric `TOTAL_SESSIONS`. |
| `initialState_withNoUidBound_isNotLoading_andEntriesEmpty` | Before any user is bound: not loading, no entries, null `myEntry`. No endless spinner. |
| `bindUser_triggersLeaderboardObservation` | Binding a uid starts the repo observation; one entry appears and loading ends. |
| `bindUser_blankUid_doesNotLoadData` | `bindUser("   ")` is ignored. |
| `bindUser_emptyUid_doesNotLoadData` | `bindUser("")` is ignored. |
| `bindUser_myEntry_isExtracted_fromEntriesWhereIsMe` | `myEntry` is the entry flagged `isMe`. |
| `bindUser_myEntry_isNull_whenNotInLeaderboard` | With no `isMe` entry, `myEntry` is null. |
| `setScope_friends_updatesStateScope` | `setScope(FRIENDS)` updates state. |
| `setScope_global_updatesStateScope` | Switching to friends and back to global updates state. |
| `setMetric_avgSimilarity_updatesStateMetric` | Metric becomes `AVG_SIMILARITY`. |
| `setMetric_totalActiveDays_updatesStateMetric` | Metric becomes `TOTAL_ACTIVE_DAYS`. |
| `bindUser_friendIds_propagatedToState` | `observeFriendIds` output appears in `state.friendIds`; the UI uses it to choose add or remove buttons. |
| `addFriend_callsRepoAddFriend_withCorrectArguments` | Calls `repo.addFriend(myUid, friendUid, displayName, photoUrl)` once with the correct values. |
| `addFriend_doesNothing_whenTargetIsCurrentUser` | Adding yourself never reaches the repo. |
| `addFriend_doesNothing_whenAlreadyAFriend` | Prevents duplicate friend writes. |
| `addFriend_doesNothing_whenNoUserBound` | No bound user, no call. |
| `addFriend_addingFriendUid_isNull_afterCompletion` | The in-flight uid (per-row spinner) is cleared after success. |
| `addFriend_addingFriendUid_isNull_evenOnRepoException` | The repo throws `RuntimeException`; the in-flight uid is still cleared. |
| `removeFriend_callsRepoRemoveFriend_withCorrectArguments` | Calls `repo.removeFriend(myUid, friendUid)` once. |
| `removeFriend_doesNothing_whenTargetIsCurrentUser` | Removing yourself is ignored. |
| `removeFriend_doesNothing_whenNoUserBound` | No bound user, no call. |
| `removeFriend_removingFriendUid_isNull_afterCompletion` | The in-flight uid is cleared after success. |
| `removeFriend_removingFriendUid_isNull_evenOnRepoException` | The in-flight uid is cleared even if the repo throws. |

### videouploads/

#### `VideoUploadTest` (class `VideoUploadsViewModelTest`, 19 tests)
Under test: `VideoUploadsViewModel(authRepository, videoUploadRepository, templateGenerator)`, plus `DancerReviewUiState` and `PoseTemplate.confirmedTemplate`. The auth and upload repos are strict mocks and the template generator is relaxed. The VM refreshes uploads on init, so each test advances until idle after constructing it.

*Refreshing uploads*

| Test | What it does |
|---|---|
| `refreshMyUploads_notLoggedIn_setsEmptyListAndStatusMessage` | No profile: `refreshing=false`, empty list, message `"Not logged in."`, and `loadVideos` is never called. |
| `refreshMyUploads_success_updatesMyUploads` | Two uploads load in order, `refreshing=false`, and `loadVideos` is called once. |
| `refreshMyUploads_failure_setsStatusMessage` | A failure sets a message starting with `"Failed to load uploads:"` and clears the refreshing flag. |

*Upload draft and upload*

| Test | What it does |
|---|---|
| `uploadDraft_actions_updateFormAndDialogState` | Sets title, type and description, opens the video source dialog, selects a gallery URI (label from the last path segment) and dismisses the dialog. Asserts the draft fields and `canUpload=true`. |
| `uploadDance_success_usesUploadDraftAndClearsForm` | `uploadDance(context, generateTemplate=true, fps=15)` calls the repo once with exact arguments (educator uid/name, public visibility by default, `confirmedTemplate=null`, `fps=15`, hand landmarks on, continue-without-template on failure). Status is `"Uploaded. Template generation continues in the background."` and the draft is cleared. |
| `uploadDance_withPrivateChoice_uploadsAsPrivate` | Choosing private visibility is passed to the repo. |
| `uploadDance_withChosenCoverFrame_passesCoverFrame` | The chosen cover time (4,500 ms) reaches the repo. |

*Dancer review and confirmed template*

| Test | What it does |
|---|---|
| `dancerReviewState_exposesDetectedDancerCountAndSafeWarningsWhenAbsent` | A default state has `detectedCount=0`, no warnings and `canConfirm=false`. |
| `dancerReviewState_mapsQualityWarningsToCreatorFriendlyMessages` | Four internal warning codes (duplicate tracks, low light, missing frames, low landmark confidence) become four creator-friendly messages. |
| `confirmedTemplate_preservesSelectedDefaultTrackForMultipleDancers` | With two tracks selected and `track_2` as the default, the default becomes `track_2`, `frames` equal its frames, and both tracks, the preview and both hand tracks are preserved. |
| `confirmedTemplate_filtersHandTracksToSelectedDancers` | Selecting only `track_2` drops the other dancer's hand data. |
| `confirmedTemplate_stillWorksWithOneDancer` | Single-dancer regression path. |

*Edit, delete and visibility*

| Test | What it does |
|---|---|
| `saveEdit_guardClause_invalidDraft_doesNotCallRepository` | A blank title blocks `updateDance`; `saving=false` and the dialog stays open. |
| `saveEdit_success_callsUpdate_thenClosesDraft_andRefreshes` | Calls `updateDance` with the new title, type and description, closes the draft, shows `"Updated"` and reloads the list. |
| `confirmDelete_success_callsDelete_thenClearsDialog_andRefreshes` | Calls `deleteDance("d1","uid123")`, clears the dialog, shows `"Deleted"` and refreshes. |
| `setVisibility_success_savesAndUpdatesListedUpload` | Making a video private updates the listed item locally without refetching and shows `"Video is now private"`. |
| `setVisibility_failure_keepsOriginalVisibility` | On failure the item stays public and the message is `"Could not change visibility: offline"`. |

### weeklychallenge/

#### `WeeklyChallengeViewModelTest` (7 tests)
Under test: `WeeklyChallengeViewModel(repo)`. The ViewModel shows what the server reported and reloads the week; it does not compute completion itself.

| Test | What it does |
|---|---|
| `loadForWeek_success_updatesStateWithChallengeSet` | The loaded set is in state, `loading=false`, no error. |
| `loadForWeek_failure_setsErrorMessage_andClearsLoading` | An exception becomes `errorMessage="boom"` and the spinner stops. |
| `onSessionRewards_showsCelebration_forChallengesTheServerCompleted` | When the server reports a completed challenge, the celebration dialog shows, `newlyCompleted` has that id with its type and reward resolved from the reloaded week, and the bonus is not flagged. |
| `onSessionRewards_withNothingCompleted_doesNotShowCelebration` | No completed challenges means no dialog. |
| `dismissCelebrationDialog_resetsCelebrationState` | Dismissing clears the dialog, `newlyCompleted` and `bonusNewlyUnlocked`. |
| `clearError_resetsErrorMessageToNull` | `clearError()` nulls the error. |

---

## 2. Pose detection unit tests (`app/src/test/.../posedetection`)

Pure JVM tests (only `PersonDetectorFactoryTest` uses MockK and coroutines-test).

### `ByteTrackDancerTrackerTest` (4 tests)
Under test: `ByteTrackDancerTracker`, a ByteTrack-style multi-frame tracker for dancer boxes.

| Test | What it does |
|---|---|
| `update_recoversSameTrackAfterShortDetectionGap` | Frame 0 has a box, frame 1 none, frame 2 a slightly shifted box. Frame 1 yields one `TemporarilyLost` entry and frame 2 yields `track_1` as `Recovered`. Only one track exists overall, so a dancer who briefly disappears keeps their identity. |
| `update_endsTrackAfterMaxMissedFrames` | With `maxMissedFrames=1`, two empty frames produce an `Ended` track. Stale tracks are retired. |
| `update_deduplicatesOverlappingBoxesForOneDancer` | Two heavily overlapping boxes collapse into one visible `track_1`. |
| `update_emptyDetectionsDoNotCreateVisibleBoxes` | Empty input creates no visible boxes or tracks. |

### `ConservativeFramePreprocessorTest` (4 tests)

| Test | What it does |
|---|---|
| `disabledConfig_keepsAdjustmentsConfigurable` | `disabled()` flips `enabled` to false while brightness, contrast and gamma keep their defaults. |
| `transformColor_appliesConservativeBrightnessWithoutChangingAlpha` | A dark pixel gets brighter in R, G and B; alpha stays 128. |
| `transformColor_clampsVeryBrightPixels` | A near-white pixel with aggressive settings clamps to exactly opaque white. |
| `averageLuminance_identifiesDarkFramesBelowDefaultWarningThreshold` | Dark pixels fall below the low-light warning threshold. |

### `CropPoseGeometryTest` (5 tests)

| Test | What it does |
|---|---|
| `expandBox_addsPaddingAndClampsToFrameBounds` | Padding 0.5 on `(0.05,0.10,0.25,0.60)` gives `(0.00,0.00,0.35,0.85)`: left and top clamp to 0. |
| `holisticHandPadding_expandsCropMoreThanBodyOnlyPadding` | Body crop is `(0.246,0.110,0.654,0.790)` and hand crop is `(0.204,0.040,0.696,0.860)`. The hand crop is larger so hands and arms aren't cut off, and the exact numbers pin the default constants. |
| `toPixelCropRect_convertsNormalizedBoxToBoundedPixelRect` | Box `(0.10,0.20,0.40,0.75)` on 200x100 gives left 20, top 20, width 60, height 55. |
| `remapCropLandmarksToFullFrame_mapsNormalizedCropCoordinates` | Crop-space landmarks map into full-frame x and y; z and visibility pass through unchanged. |
| `remapHolisticLandmarksToFullFrame_mapsPoseAndHandsWithoutFaceFields` | Pose and both hands get the same transform, z and visibility are preserved, and face data is not part of the result. |

### `HandSimilarityEvaluatorTest` (8 tests)
Under test: `HandSimilarityEvaluator` and `combineWithBodyScore`.

| Test | What it does |
|---|---|
| `noReferenceHandData_usesBodyOnlyFallback` | No reference hands: `hasReferenceHands=false`, `handScore=null`, and the combined score equals the body score (0.8). |
| `missingLearnerHand_returnsNoHandScoreAndVisibilityFeedback` | The template has a left hand but the learner supplies none: no hand score, feedback `"Left hand not detected."`, and the body score is unchanged. An undetected hand is guidance, not a penalty. |
| `missingReferenceHand_returnsNoHandScore` | Empty reference arrays count as no reference. |
| `leftHandOnly_scoresLeftHand` | A left hand translated by (0.2, 0.1) scores above 0.99: scoring is translation-invariant and only the available hand counts. |
| `rightHandOnly_scoresRightHand` | A right hand scaled 1.8x from the wrist scores above 0.99: scoring is scale-invariant. |
| `bothHands_averagesAvailableHandScores` | The hand score is the mean of left and right; a perturbed right hand lowers only its own score. |
| `weightedBodyAndHandScore_usesFifteenPercentHands` | Body 0.8 and hand 0.4 combine to 0.74 (85% body, 15% hands). |
| `malformedShortArrays_doNotCrashAndReturnNoScore` | Truncated arrays are treated as absent: no exception and no score. |

### `HandTrackAssignerTest` (5 tests)
Under test: `HandTrackAssigner.assign`, which attaches hand observations to template tracks by pose similarity.

| Test | What it does |
|---|---|
| `attachesHandsToTheDancerWithTheMatchingPose_evenWhenTrackerIdsDiffer` | Assignment is by pose proximity, not by id or order, so numbering differences between trackers don't matter. |
| `ignoresObservationsThatDoNotMatchAnyDancerPose` | A far-away pose (a bystander) is not attached to anyone. |
| `matchesOnlyFramesAtTheSameTimestamp_andSortsByTime` | Output frames are time-ordered and an observation with no matching frame is dropped. |
| `eachObservationIsUsedOnce_closestDancerWins` | One observation goes to the closest dancer only. |
| `skipsObservationsWithNoHands` | No empty hand tracks are created. |

### `HolisticFrameLandmarksTest` (4 tests)

| Test | What it does |
|---|---|
| `flattenPoseLandmarks_preservesPoseValues` | 33 landmarks flatten to 132 floats in (x, y, z, visibility) order. |
| `flattenHandLandmarks_preservesLeftAndRightHandValues` | Each hand flattens to 84 floats and left and right are not swapped. |
| `flattenLandmarks_missingOrShortHandsReturnEmptyLists` | Incomplete detections (empty, 20-landmark hand, 32-landmark pose) give empty lists. |
| `holisticFrameLandmarks_exposesPoseAndHandsOnlyWithoutFaceData` | Reflection confirms the class has `pose`, `leftHand` and `rightHand` fields and nothing containing "face". A privacy guard. |

### `HolisticFrameSelectorTest` (2 tests)

| Test | What it does |
|---|---|
| `primaryPose_ignoresFramesWithoutPose` | Empty frames are skipped when choosing the primary person. |
| `primaryPose_returnsNullWhenOnlyHandsArePresent` | A hands-only frame is not a person, so the result is null. |

### `MissingLandmarkInterpolatorTest` (3 tests)

| Test | What it does |
|---|---|
| `interpolate_fillsShortGapWithLowVisibilityLandmarks` | At 8 fps, frames at 0 and 250 ms gain a midpoint at 125 ms with averaged x and y, z of 0, and visibility at most 0.20 so it is treated as low confidence. |
| `interpolate_doesNotFillLongGap` | A 7-frame gap is left alone (no invented motion). |
| `interpolate_doesNotFillFromLowConfidenceHiddenEndpoints` | If one endpoint has visibility 0.05, nothing is interpolated. |

### `MultiPersonPoseTrackerTest` (8 tests)

| Test | What it does |
|---|---|
| `buildTemplate_tracksMultipleDancers_andCreatesSharedPreview` | Two dancers over two frames give 2 tracks labelled "Dancer 1" and "Dancer 2", `defaultTrackId=track_1`, and a selection preview with 2 candidates and the frame size. |
| `buildTemplate_filtersDuplicatePreviewCandidateForSameDancer` | A duplicate detection of one dancer does not create a third candidate. |
| `buildTemplate_dropsTinyFalsePositiveTrackFromSelector` | A tiny, short-lived box is filtered out. |
| `buildTemplate_keepsTracksThatAreNotInSharedPreviewFrame` | A dancer who appears only in later frames keeps a track (3 tracks) but isn't in the 2-candidate preview. |
| `availableTracks_createsLegacyTrackForSinglePoseTemplates` | A legacy template with only `frames` yields one track with `LEGACY_TRACK_ID`, "Dancer 1". |
| `poseBounds_keepsLowVisibilityLandmarksForUploadTemplates` | Visibility 0.18 poses still produce bounds. |
| `buildTemplate_highRecallSettingsKeepBriefWeakDancerTrack` | With high-recall settings, a small weak dancer with a 2-frame gap stays as one track. |
| `buildTemplate_mergesFragmentedTracksAfterTemporaryLoss` | Fragments of one dancer separated by empty frames merge into a single track. |

### `PersonDetectorFactoryTest` (5 tests)

| Test | What it does |
|---|---|
| `detectionBox_normalizedClampsCoordinatesAndConfidence` | Coordinates clamp to 0..1, top/bottom are reordered, and confidence clamps to 1. |
| `noOpPersonDetector_returnsNoDetections` | The fallback detector returns an empty list. |
| `create_disabledConfigReturnsNoOpWithoutWarning` | Disabled config returns the no-op detector silently. |
| `create_missingModelAssetReturnsNoOpWithUnavailableWarning` | A `FileNotFoundException` on the asset gives the no-op detector and `WARNING_PERSON_DETECTOR_UNAVAILABLE`. |
| `create_providerFailureFallsBackToNoOpWithWarning` | A throwing detector provider falls back safely with the same warning. |

### `PoseDetectionMergerTest` (2 tests)

| Test | What it does |
|---|---|
| `mergeDuplicateLandmarkDetections_keepsHigherPriorityOverlap` | Of two near-identical poses the higher-visibility one wins; a separate dancer is kept; output is priority-ordered. |
| `mergeDuplicateDetections_returnsEmptyForNoOpDetectorFallbackData` | Empty input gives empty output. |

### `PoseSimilarityEvaluatorTest` (1 test)

| Test | What it does |
|---|---|
| `snapshot_includesWordFeedbackForLargestPoseMismatch` | Raising the learner's left shoulder, elbow and wrist produces the cue `"Lift your left arm."`, which is also recorded in the feedback series. |

### `PoseTemplateJsonTest` (10 tests)

| Test | What it does |
|---|---|
| `roundTrip_preservesTemplateData` | Serialise then parse preserves fps, frames, tracks, default track, selection preview and warnings. |
| `fromJson_readsLegacyTemplateWithOnlyFpsAndFrames` | Old templates load, with empty tracks, warnings and hand tracks and a legacy track from `availableTracks()`. |
| `fromJson_defaultsMissingOptionalQualityMetadata` | Missing optional fields default sensibly. |
| `fromJson_ignoresUnknownFieldsAndMalformedOptionalMetadata` | Wrong-typed or unknown optional fields are ignored; only the valid warning survives. The parser is forward-compatible. |
| `fromJson_missingHandTracksDefaultsToEmptyList` | Pre-hand-feature templates stay valid. |
| `fromJson_readsHandEnabledTemplateWithOptionalHandTracks` | Hand tracks (84 floats each) parse correctly. |
| `roundTrip_preservesEmptyLeftAndRightHandArrays` | "No hand in this frame" survives a round trip. |
| `toJson_doesNotSerializeFaceFields` | The JSON never contains "face" (privacy guard). |
| `roundTrip_preservesMultiTrackTemplateAndFlattenedLandmarks` | Two tracks with awkward floats keep precision within 1e-6. |
| `jsonPath_preservesDanceTemplateStorageLocation` | Storage path helpers keep `danceTemplates/{uid}/{id}.json`, the legacy `danceTemplates/{id}.json`, and `ownerUidFromVideoPath` for `danceVideos/...` only. |

### `SamplingFpsTest` (5 tests)

| Test | What it does |
|---|---|
| `shortVideos_keepThePreferredRate` | 30 s and 120 s at 8 fps stay at 8; 120 s at 5 fps stays at 5. |
| `longerVideos_areSampledLessOften_withinTheFrameBudget` | 180, 240 and 300 s at 8 fps drop to 5, 4 and 3 to stay within about 960 frames. |
| `neverBelowThreeFramesPerSecond` | A 600 s video floors at 3 fps. |
| `unknownDuration_usesThePreferredRate` | Duration 0 uses the preferred rate. |
| `frameTimes_dontDrift_atAnyRate` | `frameTimeMs` is within 1 ms of the exact time at fps 3 to 8, and frame 960 at 7 fps is exactly 137,142 ms. Guards against cumulative rounding drift. |

### `TrackFragmentMergerTest` (3 tests)

| Test | What it does |
|---|---|
| `merge_combinesPlausibleSplitTrackFragments` | Two non-overlapping fragments that line up merge into one 4-frame track, with `replacedTrackIds = {track_2 -> track_1}`. |
| `merge_keepsOverlappingRealDancersSeparate` | Two simultaneous dancers on opposite sides stay as 2 tracks. |
| `merge_combinesSignificantOverlapOnlyForClearDuplicates` | Near-identical overlapping tracks merge and the higher-visibility one (`track_2`) survives. |

### `YoloPersonDetectionParserTest` (4 tests)

| Test | What it does |
|---|---|
| `parse_boxesFirstOutputReturnsOnlyConfidentPersonDetections` | Of three rows only the confident person detection is kept (non-person and low-confidence rows are ignored), with centre/size converted to corners. |
| `parse_yoloV5ObjectnessOutputMultipliesObjectnessAndClassScore` | Confidence is objectness times class score (0.9 x 0.8 = 0.72). |
| `parse_attributesFirstOutputTransposesYoloV8Shape` | The YOLOv8 `[attributes, candidates]` layout is transposed correctly. |
| `parse_overlappingBoxesKeepsHighestConfidenceDetection` | Non-maximum suppression keeps the 0.95 box, drops the overlapping 0.70 box, and keeps a separate person. |

---

## 3. Instrumented tests (`app/src/androidTest`)

Base path: `app/src/androidTest/java/com/example/chorevo/`. These need a device or emulator. Most use `createAndroidComposeRule<ComponentActivity>()`, MockK-mocked repositories with real ViewModels, and `waitUntil` polling helpers.

### Helper

**`dancelibrarylist/FeedTestStubs.kt`** has no tests. It defines `DanceLibraryRepository.stubFeed(items)`, which stubs `loadCategories()` (chips derived from the items' dance types) and `loadFeedPage(...)` (a single page filtered by category and search, like the server would). UI tests use it to exercise chips and search without Firebase.

### `firebase/AuthScreenTest` (5 tests)
Under test: `AuthScreen` with a real `AuthViewModel` over a relaxed `FirebaseAuthRepository` mock.

| Test | What it does |
|---|---|
| `loginMode_showsLoginUi` | Asserts the login layout: "Welcome", "Username / Email", "Enter your password", "Sign up here", "Continue with Google", "Sign in to keep dancing", and exactly one "Login" node (the button). |
| `toggleMode_showsSignupUi` | Clicking "Sign up here" shows "Display Name", "Enter your Email", "Create Account" and "Login here". |
| `signupWithoutDisplayName_showsValidationError` | Signup with a blank name shows "Please enter a display name." before any repo call. |
| `loginSuccess_callsOnAuthed` | A stubbed successful `signIn` leads to `onAuthed` receiving the profile with uid `u1`. |
| `loginFailure_showsErrorMessage` | A stubbed failure shows "Invalid credentials". |

### `firebase/OnboardingScreenTest` (2 tests)

| Test | What it does |
|---|---|
| `firstPage_showsTitle_andSkipFinishes` | Page 1 shows "Learn any routine" and "Next"; clicking Skip calls `onFinish` once. |
| `next_walksThroughPages_thenGetStartedFinishes` | Next goes to "Live AI feedback", then "Level up every week" with `onFinish` not yet called; "Get started" then finishes exactly once. |

### `danceactivity/DanceSessionDaoTest` (4 tests)
Under test: the Room `DanceSessionDao` on an in-memory test database.

| Test | What it does |
|---|---|
| `insert_and_latestFlow_returnsNewestByTsEnd` | The latest session is the one with the highest `tsEnd`. |
| `markSynced_updatesRow_and_unsynced_excludesIt` | After `markSynced`, the session leaves the `unsynced` list. |
| `unsyncedFor_returnsOnlyThatAccountsAndLegacySessions` | Returns the account's own sessions plus legacy blank-owner sessions, not other accounts'. |
| `sinceFlow_returnsSessionsWithTsEndAfterSinceTs` | Only sessions ending after the given time are returned. |

### `danceactivity/DanceARPracticeScreenTest` (4 tests)
Under test: `DanceARPracticeScreen` in test mode, with a real `PracticeViewModel` whose timer runs on a controllable `TestCoroutineScheduler`.

| Test | What it does |
|---|---|
| `practiceScreen_showsMetricsLabels_andButtons_andBackWorks` | Reset, Submit, Back, "Live Analytics" and the default feedback text show; Back calls `onBack` once. |
| `practiceScreen_showsThreeSecondCountdown_beforePracticeStarts` | Start Practice shows a "3" countdown overlay; advancing virtual time by 3.1 s removes it and the practice UI is shown. |
| `practiceScreen_referenceDancerSelector_scrollsToLaterDancers` | A 12-dancer template shows the selector card, and the 12th dancer chip can be scrolled into view. |
| `practiceScreen_withTemplate_showsStepByStepModeControls` | A template with 12 frames shows "Full Routine" and "Step-by-Step"; Step-by-Step shows "Step 1 of 2" and "Next". |

### `dancelibrarylist/DanceLibraryListViewTest` (7 tests)
Under test: `DanceLibraryListView`, a vertical pager feed with a discovery bar.

| Test | What it does |
|---|---|
| `emptyState_showsStatusText` | An empty feed shows "No dances available." and no Back button. |
| `listShowsFirstItemTitleEducatorAndPractice` | The first card shows the title, "by Coach A" and "Practice". |
| `swipeUp_movesToNextDancePage` | Swiping up on the pager shows the second dance. |
| `practiceButton_callsOnPickDance_withCorrectItem` | Clicking Practice calls `onPickDance` with the right item (`d1`, "Salsa Basics"). |
| `discoveryBar_showsTabsAndCategoryChips` | "For You", "Trending", "New" and the derived category chips show. |
| `categoryChip_filtersFeedToThatStyle` | Clicking the hip-hop chip shows only hip-hop dances. |
| `searchWithNoMatch_showsEmptyState_andClearFiltersRestoresFeed` | Searching "ballet" shows "No dances match"; clearing filters restores the feed. |

### `analytics/AnalyticsDashboardViewTest` (3 tests)

| Test | What it does |
|---|---|
| `profile_viewSessionResults_showsSessions_expand_andShowsDetailContent` | Expanding a session in learner mode shows "Template Video", "Performance Segments", "Alignment Timeline", rounded scores ("Alignment: 91", "Smooth: 82", "Timing: 73") and stat cards (pose error `0.123`, frames `99`). |
| `profile_otherDanceAnalytics_mode_rendersSessions_andDetail` | Educator mode with a composite session key and null analysis shows "No segment data found." and "No timeline data found." |
| `overview_showsTilesTrendHeatmapAndBodyFocus` | `AnalyticsOverview` shows "Avg score", "Best score", "90%", "8m" and "Focus on: left arm", and the trend card, activity heatmap and body diagram nodes exist. |

### `leaderboard/LeaderboardScreenTest` (21 tests)
Under test: `LeaderboardScreen` with a real `LeaderboardViewModel` over a relaxed `LeaderboardRepository` (default flows are empty).

| Test | What it does |
|---|---|
| `topBar_showsLeaderboardTitle` | "Leaderboard" is displayed. |
| `tabs_showsGlobalAndFriendsTabs` | "Global" and "Friends" tabs show. |
| `clickFriendsTab_showsNoFriendsMessage_whenEmpty` | Clicking Friends shows "No friends added yet." |
| `clickGlobalTab_showsNoRankingMessage_whenEmpty` | Friends then Global shows "No ranking data yet." |
| `filterChips_showsAllMetricOptions` | "Sessions", "Similarity" and "Active Days" chips show. |
| `emptyGlobal_showsNoRankingDataMessage` | The default global empty text shows. |
| `entriesList_showsDisplayNamesAndRanks` | Alice (rank 1, you) and Bob (rank 2): the podium and rank blocks show; "Alice (You)" appears twice (podium plus the pinned "Your position" card) and `#1` appears once, on the pinned card. |
| `entriesBeyondTopThree_areListedBelowPodium_withRank` | With 5 entries and a different user, ranks 4 and 5 are listed as `#4` and `#5` below the podium, and there is no pinned card. |
| `myEntry_showsYourPositionCard` | "Your position" shows for your own entry. |
| `myEntry_appendsYouSuffix_toDisplayName` | Your entry's name is shown with "(You)". |
| `sessionsMetric_displaysSessionCount` | Default metric shows "15 sessions". |
| `similarityMetric_displaysFormattedScore` | The Similarity chip shows "75% match". |
| `activeDaysMetric_displaysActiveDayCount` | The Active Days chip shows "12 days". |
| `globalScope_nonFriendEntry_showsAddFriendButton` | A non-friend entry has an "Add friend" button. |
| `globalScope_alreadyFriendEntry_doesNotShowAddFriendButton` | An existing friend has no Add button. |
| `friendsScope_friendEntry_showsRemoveFriendButton` | On the Friends tab a friend has a "Remove friend" button. |
| `myEntry_inGlobalScope_doesNotShowAddFriendButton` | You cannot add yourself. |
| `clickAddFriend_callsRepoAddFriend_withCorrectArgs` | Clicking Add friend calls `repo.addFriend` with `uid1` and `uid2`. |
| `clickRemoveFriend_callsRepoRemoveFriend_withCorrectArgs` | Clicking Remove friend calls `repo.removeFriend` with `uid1` and `uid2`. |
| `tiedEntries_showSameRankNumber` | Two entries with rank 1 both render as gold podium blocks (two `podium_rank_1` nodes). |

---

## 4. Cloud Functions tests (`functions/test`)

Pure Node unit tests with no emulator, using hand-made fakes for the bucket, file, db and collections.

### `discovery.test.js` (9 tests)
Under test: `../discovery`, which computes derived search and feed fields for `danceLibrary` documents.

| Test | What it does |
|---|---|
| danceTypeKey groups spellings | `"Hip Hop"`, `"hip-hop"` and `"K-Pop"` normalise to `hiphop`, `hiphop`, `kpop`; blank gives `null`. Category counts and filters treat spelling variants as one style. |
| searchTokens holds word prefixes from title, type and creator | For "Salsa Basics" / "Latin" / "Maria", every word prefix is present and a mid-word substring is not. Enables prefix search through `array-contains`. |
| searchTokens caps long words at 15 characters | A 34-character word yields `supercalifragil` and no token over `MAX_TOKEN_LENGTH`. Bounds the stored array. |
| feedReady needs enabled, public and a ready template | `{enabled:true}` is ready (missing status counts as ready); private, queued, disabled and `{}` are not. Gates the public feed. |
| trendingScore favours likes and decays with age | 10 likes beat 2 likes, and 5 likes today beat 5 likes 30 days ago. |
| derivedFieldUpdates fills missing fields once, then is stable | Produces `visibility`, `feedReady`, `danceTypeKey`, `shuffleKey` (stubbed 0.42), `likeCount` and `trendingScore`. Feeding the result back an hour later returns `{}`, which prevents an infinite trigger loop. |
| derivedFieldUpdates reacts to content changes | Making a dance private and "Ballet" flips `feedReady` and `danceTypeKey` and updates tokens, while `shuffleKey` stays set only once. |
| buildCategories counts feed dances and picks the most common spelling | Three hip-hop variants give `{hiphop:3}` labelled "hip hop"; private and blank-type dances are excluded. |
| searchTrigrams breaks words into 3-letter pieces for typo matching | `"Salsa Go"` and `"Hip Hop"` produce the expected trigrams, so a misspelling shares pieces with the real word. |

### `groups.test.js` (13 tests)
Under test: `../groups`.

| Test | What it does |
|---|---|
| collects group video and thumbnail paths, ignoring everything else | `storagePathsOfPosts` returns only `groupVideos/{uid}/…` and `groupThumbnails/{uid}/…` paths (de-duplicated); `danceVideos` paths, dance posts and text posts are ignored, so group deletion never deletes feed videos. |
| a post can't point the cleanup at someone else's files | A post whose `uid` is "attacker" pointing at the victim's folder, or using `../`, yields `[]`. |
| isInviteCode accepts only well-formed codes | `ABCD2345` passes; lowercase, `/`, ambiguous characters and `null` fail. |
| invitePushAllowance limits pushes per inviter per hour | Exactly `INVITE_PUSHES_PER_HOUR` are allowed, the next is denied, and an hour later it resets. Prevents invite spam. |
| invitationMessage names the inviter and group and routes to the invites channel | Body "Ana invited you to join Salsa Crew", `data` is `{type:"group_invitation", groupId}`, channel `group_invites`, and all data values are strings (an FCM requirement). |
| invitationMessage falls back when names are missing and trims long ones | Missing names give "Someone invited you to join a group"; a 200-character name keeps the body under 100 characters. |
| deadTokens keeps tokens that failed for transient reasons | Only `registration-token-not-registered` and `invalid-registration-token` mark a token dead; `internal-error` and `invalid-argument` do not. |
| media requests need a group id and 1-50 post ids | `parseGroupMediaRequest` de-duplicates ids and returns `null` for null, an empty list, a `/` in the group id, `../x`, or 51 ids. |
| public groups are visible to everyone signed in, private ones to members | `canSeeGroup` rules, including a null group being invisible even to a member. |
| only a video post's own files are signed | `groupPostMediaPaths` returns paths only for a valid video post and `{}` for `danceVideos/` paths, other users' folders, mismatched filenames or non-video posts. |
| group media paths are recognised | `isGroupMediaPath` is true for `groupVideos/…` and `groupThumbnails/…` and false for `danceVideos/…` and nested paths. |
| removing download tokens only touches files that have one | A file with a token gets its token cleared and returns `true`; no token or a missing file returns `false` with no writes. Group media is then only reachable through server-signed URLs. |
| the catch-up locks unlocked video posts once, and a failure leaves the post for the next run | Of three video posts (one unlocked, one already locked, one whose storage throws), only the first is updated (`videoUrl` and `thumbnailUrl` deleted, `mediaLocked:true`). Result `{videoPosts:3, attempted:2, filesLocked:1}`; the migration is idempotent and retryable. |

### `leaderboard.test.js` (3 tests)
Under test: `../leaderboard` and `../account`.

| Test | What it does |
|---|---|
| leaderboardEntry uses the profile and clamps forged values | Trims the name, keeps https photo URLs, and clamps forged data: a 500-character name is cut to 60, `javascript:` photo URLs become `null`, similarity 99 clamps to 1, and active days are capped at the session count. |
| leaderboardEntry handles users without sessions or names | An empty profile gives name "User" and zeros; null and undefined inputs are tolerated. |
| account deletion covers every per-user Storage folder | `userStoragePrefixes("u1")` includes `danceVideos`, `danceThumbnails`, `danceTemplates`, `groupVideos`, `groupThumbnails` and `profilePhotos`, so erasure leaves no orphaned files. |

### `privacy.test.js` (7 tests)
Under test: `../privacy`, which makes dance media private by rotating Storage download tokens.

| Test | What it does |
|---|---|
| only a public-to-private change counts | `becamePrivate` is true for public→private and for a missing field→private; false for private→private, private→public and nulls. |
| download URLs round-trip to their storage path | `downloadUrl` and `storagePathFromDownloadUrl` are inverses. |
| anything that isn't this bucket's download URL is ignored | Empty, null, non-URL, `http://`, foreign hosts and other buckets all give `null`, so attacker-supplied URLs are never acted on. |
| only the uploader's own dance video and cover are revoked | `revocableMedia` returns the video and thumbnail only when they live in the uploader's `danceVideos/` and `danceThumbnails/` folders; other users' files, other folders, `..` paths and a missing `educatorUid` give `[]`. |
| rotating gives each file a new token and returns the new URLs | The video and thumbnail get new tokens and the update contains the new URLs plus `linksRevokedAt`. Old shared links stop working. |
| a file that can't be updated keeps its URL | If the video fails, its URL is left out, the thumbnail still rotates, and `linksRevokedAt` is not set, so a retry can finish the job and a failure never leaves a broken URL or a false "done" marker. |
| the catch-up pass revokes only private dances not done yet | Queries `visibility == private`; a dance without `linksRevokedAt` is rotated and one that already has it is skipped. Result `{privateDances:2, revoked:1}`. |

### `render.test.js` (9 tests)
Under test: `renderDancePage` and `parseDanceId` in `../render`, the server-rendered link-preview page for `/dance/:id`.

| Test | What it does |
|---|---|
| parseDanceId extracts the id | `/dance/abc123` and `/dance/abc123/` give `abc123`; `/dance/`, `/other/abc` and `/dance/a/b` give `null`. |
| renders the dance's own title in the preview tags | A public dance renders `og:title`, `twitter:title`, `<title>`, `og:video` and a `<video>` tag, with `&` escaped and no unreplaced `{{placeholder}}`. |
| escapes user-provided text | A title containing `<script>` and an educator named `A&B` are HTML-escaped (an XSS guard). |
| falls back to generic text for missing, disabled or private dances | `og:title` becomes "Check out this dance on Chorevo", with no video and no leak of the private title or video URL. |
| uses the dance's cover thumbnail as the preview image | `og:image`, `twitter:image` and the video poster use the project thumbnail, and the card is `summary_large_image`. |
| falls back to the branded preview image without a thumbnail or for private dances | `DEFAULT_IMAGE` is used when there is no thumbnail or the dance is private, and a private thumbnail URL never appears. |
| parseDanceId rejects ids that could address other documents | Encoded slashes, `..` and a 200-character id give `null`; `Ab_c-9` is accepted. Prevents path injection into Firestore lookups. |
| only this project's dance media is rendered | Video and thumbnail URLs on a foreign host are not rendered. |
| dances with an unknown visibility are not shown | `visibility:"Private"` (capital P, not exactly `public`) is hidden. The page fails closed. |

### `rewards.test.js` (17 tests)
Under test: `../rewards` (points, badges, streaks and weekly challenges), ported from the former client-side `WeeklyChallengeEngineTest`. The fixed clock is Wednesday 14 October 2026 12:00 UTC.

| Test | What it does |
|---|---|
| session points use the app's formula and are capped at 100 | `(0.9,0.8,0.7)` gives 83; `(1,1,1)` gives 100; forged `(50,50,50)` clamps to 100; invalid input gives 0. |
| badges are awarded once, by the same thresholds as before | A strong first session awards `CENTURY`, `DEDICATED`, `FIRST_STEPS`, `HIGH_SCORER`, `IN_THE_GROOVE`, `PERFECT_POSE`, `SMOOTH_OPERATOR`, `STREAK_STARTER` and `WEEK_WARRIOR`, but not an already-earned `ELITE`; a weak later session awards none. |
| localDay uses the server clock in the user's time zone | UTC gives `2026-10-14`, week start `2026-10-12`, day index 2. UTC+8 turns Sunday 20:00 UTC into Monday. Offsets clamp to 840 minutes. A client can't forge the day. |
| the streak counts each day once and resets the mask each week | First tick sets the bit for Wednesday and total 1; a repeat on the same day changes nothing; Monday of the next week resets the mask and bumps the total. |
| no history: focus drill defaults to alignment at the minimum threshold | Focus `ALIGNMENT`, threshold 0.6, target 2. |
| focus drill targets the weakest axis, clamped to 0.60..0.95, from the last 10 sessions | The weakest axis is chosen, thresholds clamp to the range, and only the latest 10 sessions count (threshold 0.87 in the example). |
| style explorer picks two distinct styles, one if that's all, or auto-completes | Case-insensitive de-duplication; one style gives one required style; none auto-completes with target 0. |
| momentum goal: 3 by default, recent pace + 1, clamped to 2..7 | Default 3; `[3,4,5]` → 5; `[20,30]` → 7; `[0,0]` → 2. |
| weeklySessionCounts buckets past weeks, oldest first, excluding this week | Timestamps from this week and earlier weeks give `[1,2]`. |
| focus drill only progresses on qualifying scores and completes at its target | A 0.5 session gives no progress; two 0.7 sessions complete it and report it as newly completed once. |
| style explorer matches styles case-insensitively, once each; other styles don't count | `"hip-hop"` and `"HIPHOP"` count as one style; an unrelated style doesn't count; completion fires at the target. |
| the all-three bonus unlocks exactly once | Across four qualifying sessions `bonusNewlyUnlocked` fires once and `bonusClaimed` becomes true. |
| challenge sets survive the stored format round trip | `challengeSetFromDoc(challengeSetToDoc(set))` deep-equals the original. |
| applySession: points, first badge, streak, challenges and summary | A fresh user's session awards points, `FIRST_STEPS` and `PERFECT_POSE`, advances the streak, and the totals reconcile (before + session points + challenge points = after). |
| applySession continues existing totals and history | Starting from 480 points and 12 sessions, a new session yields 530 points and 13 rewarded sessions, awarding `DEDICATED` but not repeating `FIRST_STEPS`. |
| applySession stops rewarding after the daily cap | At `MAX_REWARDED_SESSIONS_PER_DAY` the session is `capped`, awards 0 points and leaves the total unchanged. Anti-farming limit. |

---

## 5. Security rules tests (`rules-tests`)

These run against the Firebase emulator with `npm test` (`firebase emulators:exec --only firestore,storage --project demo-chorevo "node --test --test-concurrency=1"`). Files run serially because they share one emulator. They use `assertSucceeds` and `assertFails`, and fixtures are seeded with `withSecurityRulesDisabled`. Each file uses its own project id; `dance-privacy` uses `demo-chorevo` so Storage rules can read Firestore. The rules under test are `firestore.rules` and `storage.rules`.

### `dance-privacy.test.js` (11 tests)
Fixtures: public dance `pub`, legacy dance without a visibility field, private dance `priv`, plus likes and comments.

| Test | What it does |
|---|---|
| public dances are readable by anyone signed in | A signed-in viewer can read `pub`; an unauthenticated read is denied. |
| a dance without a visibility field is owner-only until the backfill sets it | The viewer is denied and the owner allowed, so pre-migration data fails closed. |
| a private dance is readable only by its uploader | Viewer denied, owner allowed. |
| feed queries must filter to public dances | Rules can't filter results, so the query must prove it only returns public docs: `enabled==true` alone and a plain listing are denied; `visibility=="public"` with `feedReady==true`, and the owner's `educatorUid` query, are allowed. |
| likes and comments of a private dance are hidden from others | A viewer can't list `priv` likes or comments or create a like on `priv`; the owner can list likes; a viewer can list `pub` likes. |
| likes may carry the liker's uid and time, but not someone else's uid | `{liked, uid:"viewer", createdAt}` and `{liked}` are allowed; a different uid or an extra field is denied. |
| the liked-videos query only returns the caller's own likes | A collection-group query with `uid == viewer` is allowed; other uids and unfiltered queries are denied. |
| clients cannot set or change server-managed fields | Owner create/update of normal fields is allowed; setting or changing `likeCount`, `linksRevokedAt` or `feedReady` is denied. |
| meta documents are read-only for clients | A viewer can read `meta/danceCategories` but not write it. |
| the uploader can still delete their dance and its likes | The owner can delete a like on `pub` and delete `priv`. |
| pose templates of private dances are readable only by their creator | Storage reads: public templates are readable by signed-in viewers; private, no-visibility, legacy-path and orphaned templates are creator-only, and unauthenticated reads are denied. This exercises the cross-service Firestore lookup in the Storage rules. |

### `groups.test.js` (9 tests)

| Test | What it does |
|---|---|
| creating a group needs the owner's membership in the same batch | A batch of group plus owner membership succeeds; the group alone fails. |
| a private group is created with its invite document | The batch of private group, owner membership and `groupInvites/{code}` succeeds. |
| nobody can claim someone else's group or make themselves owner later | Creating a group for another owner and adding yourself as owner of an existing group are both denied. |
| anyone can see and join a public group | Reading, querying public groups and creating your own member doc succeed; creating one for someone else fails. |
| a private group is hidden until joined with the right invite code | Reading the group, listing posts, listing groups and listing invites are denied; reading an invite by code is allowed; joining without or with a wrong code is denied and with the right code allowed, after which the group is readable. |
| only members post and reply, as themselves | Non-members can't post; members can post text, share their own public dance and reply; impersonation, empty bodies and non-member replies are denied. |
| members leave; the owner can't leave but can remove members and delete posts | Members can leave; the owner can remove members and delete posts but not leave; other members can't delete someone else's post. |
| the owner edits name and description only, and can delete the group | Name, `nameLower` and description edits and group deletion succeed for the owner; changing visibility, setting `memberCount`, or edits and deletion by others are denied. |
| my groups: a user can list only their own memberships | A collection-group query with `uid == me` is allowed; another uid is denied. |

### `invitations.test.js` (9 tests)

| Test | What it does |
|---|---|
| a user claims a free username together with their profile | A claim batch succeeds and the username doc is readable. |
| usernames are unique and well-formed | Taken, uppercase and too-short names are denied, as are a username doc without the matching profile write or with a different uid. |
| a profile can't show a username it doesn't own | Setting another user's username on your profile is denied; unrelated profile updates are allowed. |
| changing username releases the old one; nobody else can delete it | Others can't delete a username and you can't delete it while it's on your profile; claiming a new one and deleting the old in one batch works, after which someone else can claim the freed name. |
| usernames can be searched by prefix, a page at a time | A prefix-range query with `limit(10)` is allowed and `limit(100)` is denied, which allows search while stopping bulk enumeration. |
| members invite other users to a group | The owner can create `groupInvitations/{group}_{user}`; wrong ids, wrong group names, inviting existing members or nonexistent users, and invitations by non-members or with a forged inviter are denied. |
| only the invitee (and inviter) read an invitation; the invitee joins with it | Only the invitee can query their invitations; others can't read them; joining needs an invitation or code; the invitee joins by creating a member doc and deleting the invitation in one batch. |
| the invitee can decline; others can't delete the invitation | The invitee's delete succeeds and a stranger's is denied. |
| users keep their own push tokens; nobody else can read or write them | A matching token doc can be created and deleted by its owner; mismatched ids, extra fields, cross-user reads and cross-user writes are denied. |

### `security.test.js` (18 tests)
Broad hardening checks across users, rewards, sessions, dances, comments, groups, bans, the retired forum and Storage.

| Test | What it does |
|---|---|
| profiles can be read one at a time but never listed | Reading `users/alice` works; listing `users` is denied. |
| profiles no longer take an email; existing emails can only be removed | Creating a profile with an email or setting a new email is denied; creating without an email or removing it with `deleteField` is allowed. |
| profiles can't grant roles or carry huge names | `role:"ADMIN"`, a 500-character name and a `javascript:` photo are denied; a valid name and https photo are allowed. |
| changing username must release the old one | Claiming a new name while releasing the old is allowed; claiming without releasing is denied. |
| nobody can write leaderboard stats from the app | Writes are denied; reading others' stats is allowed. |
| points, badges, streaks, challenges and session rewards are read-only for the app | Writes such as `totalPoints: 999999` are denied, owners can read their own documents, and others can't. |
| practice sessions need a real 0..1 score; analysis is optional but must be genuine | Valid sessions, a matching analysis and a sane `tzOffsetMinutes` are allowed; out-of-range scores, unknown fields, a forged `educatorUid`, a bad time-zone offset and overwriting an existing session are denied. The rewards engine trusts these documents. |
| new dances must use the uploader's own media and a current upload time | A well-formed dance is allowed; foreign video URLs, another user's folder, a far-future `createdAt`, a 500-character title, `visibility:"unlisted"` and a template path in another user's folder are denied. |
| upload time, media and moderation flag can't be changed later | Title, visibility and `templateGenerationStatus` edits are allowed; changing `createdAt`, `videoUrl` or `enabled` is denied. |
| comments carry a bounded name and the current time | A valid comment is allowed; a far-future `createdAt` and a 500-character name are denied. |
| group posts can't reference someone else's files or outside URLs | Posts with the poster's own storage paths are allowed; paths in another user's folder, missing media, foreign URLs, a stray path on a text post and sharing someone else's dance are denied. |
| removed members are banned and can't rejoin | The owner's batch of removing a member and creating `bans/{uid}` works; rejoining or self-creating a ban is denied. |
| only the owner can see and lift bans, after which the person can rejoin | The owner can list and delete bans and the banned user can then rejoin; others can't list or delete bans. |
| private groups must be created with their own invite code document | The batch without `groupInvites/{code}` is denied and the batch with it is allowed. |
| the retired forum takes no new posts | Creating `communityPosts/x` is denied. |
| nobody can list someone's videos; only the owner reads by path | The owner can upload and read their own video; others and unauthenticated users can't list or read it. Other users watch through download-token URLs. |
| owners can delete their own files, nobody else's | Only the owner can delete `danceThumbnails/alice/d1.jpg`. |
| uploads must be the right type, in the uploader's folder, with expected names | Writing into another user's folder, `text/html` as `.mp4` and `page.html` are denied; `groupVideos/bob/p1.mp4` as `video/mp4` is allowed. |

### Storage rules summary
`storage.rules` denies everything by default and always denies `list`. Covered folders are `danceVideos`, `danceThumbnails`, `groupVideos`, `groupThumbnails`, `profilePhotos`, `danceTemplates/{uid}/…` and the legacy `danceTemplates/{file}`. Reads of videos, thumbnails and group media are owner-only, `profilePhotos` is readable by any signed-in user, and templates are readable if the dance is public or the caller is its educator. Writes require ownership, a safe filename and a content type and size cap (video 200 MB, images 10 MB, template JSON under 20 MB).

---

## 6. Additional tests (added to close coverage gaps)

These tests were added after the first version of this document, to cover code that previously had no direct tests. **Verification status:**

| Suite | Status |
|---|---|
| Cloud Functions tests (`functions/test`) | Run: all 174 tests pass (`cd functions && npm test`). |
| New rules tests (`rules-tests/firestore-gaps.test.js`, `storage-profile-photos.test.js`) | Run on the Firebase emulator: all 18 pass. |
| New Kotlin unit tests (`app/src/test`) | **Not compiled or run.** The Android Gradle plugin cannot be downloaded in the authoring environment. |
| New Kotlin instrumented tests (`app/src/androidTest`) | **Not compiled or run**, for the same reason. They also need a device or emulator. |

Known issue found while running the rules suite: the existing test `pose templates of private dances are readable only by their creator` in `rules-tests/dance-privacy.test.js` fails in the authoring environment with an "Unexpected rules runtime error" from the emulator. It fails the same way when that file runs alone, so it is not caused by the new tests.

### 6.1 Practice flow (`app/src/test/.../danceactivity/`)

#### `PracticeStepsTest` (24 tests, pure logic)
Under test: `derivePracticeStepSegments`, `PracticeStepSegment` and `sliceTemplateForStep`.

| Test group | What it checks |
|---|---|
| `derive_nullTemplate`, `derive_noFrames`, `derive_singleFrame` | A null, empty or one-frame template gives no steps. |
| `derive_twoFrames_isOneStep…` | Two frames give one step spanning both frames (0 to 1000 ms). |
| `derive_exactMultipleOfStepSize_makesEqualSteps` | 8 frames at 1 fps give two equal steps with correct index and total. |
| `derive_trailingChunkAtMinimumSize_isKeptAsOwnStep` | 10 frames split 4/4/2, and the 2-frame tail stays its own step. |
| `derive_shortTrailingChunk_isMergedIntoPreviousStep` | 9 frames split 4/4/1, and the 1-frame tail merges into the previous step. |
| `derive_fewerFramesThanOneStep_isSingleStep` | 3 frames give one step. |
| `derive_higherFps_usesLargerSteps` | At 2 fps, 12 frames give 2 steps and 11 frames give 1. |
| `derive_zeroFps_behavesLikeFpsOne` | FPS 0 behaves like FPS 1. |
| `derive_unsortedFrames_areSortedByTime` | Shuffled frames are sorted before chunking. |
| `derive_identicalTimestamps_…` | Frames at the same time still give a step of at least 1 ms. |
| `segment_title_isOneBased`, `segment_duration_…` (2) | Titles read "Step 2 of 5", and duration is end minus start, never below 1. |
| `slice_nullTemplate_returnsNull` | A null template returns null. |
| `slice_keepsOnlyFramesInsideSegment_andRebasesTimes` | Only in-range frames are kept, with times rebased to 0. |
| `slice_includesFramesOnBothSegmentBoundaries` | Frames exactly on the start and end are included. |
| `slice_segmentWithNoFrames_returnsNull` | A segment with no frames returns null. |
| `slice_preservesLandmarksAndOtherTemplateFields` | `fps`, `defaultTrackId` and landmarks survive slicing. |
| `slice_rebasesTracks…`, `slice_rebasesHandTracks…` | Tracks and hand tracks are rebased, and those with no frames in range are dropped. |
| `deriveThenSlice_coversEveryFrameOfEachStep` | For a 9-frame template, the sliced steps hold 4 and 5 frames. |

#### `PracticeResultsTest` (22 tests)
Under test: `gradeFor`, `PracticeResult` and `PersonalBestStore` (against a mocked `Context` and an in-memory `SharedPreferences`).

| Test group | What it checks |
|---|---|
| `gradeFor_*` (5) | 0.90 and above is S, 0.80–0.8999 is A, 0.65–0.7999 is B, below 0.65 is C, and out-of-range scores still get a grade. |
| `practiceResult_copyKeepsOtherFields` | Copying with a changed personal-best flag keeps the other fields. |
| `store_firstScore_*` (2) | A first score, even 0.0, is always a personal best. |
| `store_higherScore_replacesBest` | A higher score returns true and is stored under `best_{uid}_{danceId}`. |
| `store_lowerScore…`, `store_equalScore…` (3) | A lower or equal score (including after float rounding) is not a best and keeps the previous value. |
| `store_bestsAreTrackedPerDance`, `…PerUser` | Bests are independent per dance and per user. |
| `store_notBestScore_doesNotWriteToPreferences` | A non-best score writes nothing. |
| `store_usesDedicatedPreferencesFile` | The store opens the `practice_personal_bests` file. |
| `store_newInstanceSeesBestPersistedByEarlierOne` | A new store instance sees the best saved by an earlier one. |

#### `PracticeViewModelTest` (45 tests)
Under test: `PracticeViewModel`, with a `StandardTestDispatcher` for the timer, a `SavedStateHandle`, a relaxed mocked `Telemetry` and a mocked `PersonalBestStore`.

| Test group | What it checks |
|---|---|
| Bind and restore (9) | A normal-mode bind starts idle with default scores; test mode starts active with canned scores. Re-binding the same dance keeps state, while a different test mode rebinds. A multi-step template derives segments but starts in full routine. Empty and short templates are handled. Saved step 99 clamps to the last step, and saved state for another dance is ignored. |
| Reference track and step navigation (8) | The longest track is picked by default and selecting another sets a status message; selecting the same or an unknown track is ignored. Step-by-step selection needs segments and persists to saved state. Next and previous stay within bounds. Full routine leaves step mode. In step mode the secondary action advances then restarts the last step, and finishing a step completes it without submitting or touching personal bests. |
| Countdown and timer (7) | The countdown shows 3, hides the reference selector, and names the step in step mode. It ticks 2, 1, then starts practice with "Practice in progress", and does not start before 3 s. `PRACTICE_STARTED` is logged with `dance_id` and `mode`. Calling it again restarts from 3. Test-mode timer keeps practice active. |
| Reset (3) | Reset cancels a countdown, stops practice and clears live data, and in test mode restores the canned scores. |
| Submit (9) | With no dance, returns null. Before practice starts, it is rejected with a message. A missing or null educator is rejected. A good score builds the session (every field, quality flag, duration), records the personal best and logs `PRACTICE_COMPLETED`. A non-best score is reported. A low score after a real countdown is flagged done. A submit during the countdown clears it. |
| Misc state (6) | Status set and clear, camera permission results (and test mode always having permission), frames ignored without a detector, and `release()` resets state and clears saved keys. |

### 6.2 Groups, username, profile and auth (`app/src/test/.../community/`, `.../firebase/`)

#### `GroupsRepositoryTest` (about 45 tests)
Firestore chains are strict MockK mocks; `Tasks.forResult` / `Tasks.forException` complete the tasks.

| Test group | What it checks |
|---|---|
| `createGroup_*` (6) | A private group writes the group, owner member doc and `groupInvites` doc in one batch (trimmed name, `nameLower`, role `owner`, 8-character code). A public group has no invite code and skips `groupInvites`. A blank display name becomes "User", descriptions are cut to 500 characters, names that are too short or too long fail without writing, and a failed commit is returned as a failure. |
| Joining and leaving (7) | An unknown code, or an invite without a group id, fails with "No group has that invite code." A new member gets a doc with role `member` and the `inviteCode`; an existing member just gets the group id back. A rejected write (for example a ban) is returned as a failure. `joinPublicGroup` writes a member doc without an invite code, `leaveGroup` deletes the user's own member doc, and `deleteGroup` deletes the group doc or surfaces the failure. |
| Invitations (7) | `sendInvitation` writes `groupInvitations/{group}_{invitee}`. `acceptInvitation` sets the member doc and deletes the invitation in one batch. If the user is already a member the invitation is just cleared, if the group is gone the failure is "This group no longer exists.", and other failures are returned unchanged. `declineInvitation` deletes the doc or surfaces the failure. |
| Ban and unban (4) | `removeMember` deletes the membership and writes `bans/{uid}` in one batch. `unbanMember` deletes the ban doc. `bannedMembers` joins profiles, sorts newest first, shows "Removed member" for unreadable profiles and returns list failures. |
| Posts and replies (10) | Text posts are trimmed and typed `text`, with bodies capped at 1500 characters and blank names defaulted. Dance posts carry the dance fields and null a blank thumbnail. Replies are trimmed, capped at 500, and a blank body fails without writing. Deleting a post as its author also deletes its replies and Storage files; as the group owner, Storage is left alone. A failed delete is returned. |
| `groupMediaUrls_*` (4) | Empty ids return an empty map without calling the function. A response is mapped to `PostMedia`, skipping entries without a video. An unexpected payload gives an empty map and a function failure is returned. |
| `loadPublicGroups_*` (3) | A blank search orders by member count (limit 50) and drops blank-named docs. A search is lowercased and queried as a `nameLower` prefix range. A failed query is returned. |
| Listener flows (4) | `observeMembership`, `observeGroup` and `observeInvitations` map data correctly, emit null on a missing doc or error, sort invitations newest first, and remove the listener on cancel. |

#### `UsernameRepositoryTest` (17 tests)

| Test group | What it checks |
|---|---|
| `isAvailable_*` (3) | A free handle is available, a taken one is not, and a read failure is returned as a failure. |
| `suggest_*` (4) | A free base is returned as is; a taken base gets a number appended; if everything is taken the base is returned; a blank display name gives "dancer". |
| `claim_*` (7) | Invalid handles fail with the rule's problem text and write nothing. Claiming your current handle succeeds without a batch. A new handle writes `usernames/{handle}` and merges `username` into `users/{uid}` in one batch, and a changed handle also releases the old one in the same batch. If the commit fails and the handle is now taken the error is "@ryan is taken", otherwise the original error is returned. |
| `search_*` (4) | A query that normalises to empty never hits Firestore. A prefix query is normalised (`@Ali` → `ali`), limited to 10 and joined with user profiles, with fallbacks for blank names and photos. A failed profile read still returns the handle, and a failed query is returned. |

#### `ProfileViewModelTest` (9 tests)

| Test | What it checks |
|---|---|
| `initialState_isEmptyAndIdle` | Default state. |
| `refresh_populatesStreakPointsAndLikedDances` | Streak, points and liked dances are loaded with no loading flags left set. |
| `refresh_showsLoadingUntilStreakAndPointsArrive` | `isLoading` stays true until points arrive, while likes load independently. |
| `refresh_streakFailure…`, `refresh_pointsFailure…` | A failure in one falls back to an empty value and keeps the other. |
| `refresh_likedDancesFailure_yieldsEmptyList…` | Failed likes give an empty list and stop loading. |
| `loadLikedDances_*` (3) | The loading flag is set then cleared, streak and points are untouched, and a reload replaces the earlier list. |

#### `AuthViewModelTest` (about 31 tests, email paths only)

| Test group | What it checks |
|---|---|
| State and setters (3) | Default login state, field setters and password-visibility toggling. |
| Mode (2) | Toggling and setting the mode clears the status. |
| Signup (6) | A blank display name or a password shorter than 8 characters is rejected with a message and no repo call; exactly 8 characters is accepted. Success trims inputs, emits `Authed` and logs `SIGN_UP` with method `email`. An email collision shows a generic message without revealing the account exists, and a weak password from the server shows the stronger-password message. |
| Login (5) | Success trims the email (not the password), emits `Authed` and logs `LOGIN`. Login does not enforce password length or display name. Wrong password and unknown user show the identical "Email or password is incorrect." message, and other errors show their own message. |
| In-flight handling (2) | A second submit while loading is ignored and loading state is set and reset; a retry clears the earlier error. |
| Password reset (3) | A blank email or one without `@` prompts for an email; a valid email is trimmed and sent, and a neutral "If an account exists…" message is shown. |
| Google and cancel (4) | `requestGoogleSignIn` sets loading and emits `LaunchGoogleSignIn` once and is ignored while loading; `onExternalAuthCancelled` resets state and sets or clears the message. |
| `authErrorMessage_*` (5) | Unknown user and bad credentials read the same, a collision does not reveal the account, a weak password mentions the minimum length, other errors use their message, and null gives null. |

### 6.3 Video uploads and pose pipeline (`app/src/test/.../videouploads/`, `.../posedetection/`)

#### `VideoUploadsRepositoryTest` (17 tests)

| Test group | What it checks |
|---|---|
| `updateDance_*` (3) | Title (120), type (40) and description (2000) are trimmed and truncated; the write uses `SetOptions.merge()` and omits `visibility` unless given; a Firestore failure is returned. |
| `setVisibility_*` (2) | Writes exactly `{visibility}` with merge to the right document, and returns failures. |
| `loadVideos_*` (2) | Filters by `educatorUid`, orders newest first and returns the models; a failed query is returned. |
| `deleteDance_*` (5) | A blank or null uid fails with "Missing educatorUid for delete" without touching Firebase. A delete removes the video, thumbnail and template files, then likes docs, then the dance doc. Storage and subcollection errors are ignored but a failed final doc delete is returned. |
| `uploadDance_*` (5) | Missing context fails before compressing, and a blank or null educator uid fails with "Missing educatorUid". A compression failure reports the "Compressing video" stage and progress and returns a failure without touching Firestore. `onTemplateFailure` is called only when `continueWithoutTemplateOnFailure` is false. A failure after compression deletes the compressed temp file. |

#### `VideoVisibilitySelectorTest` (6 tests, logic only)
`visibilityIcon` gives a lock for private and a globe for public, falls back to the globe for blank, unknown or wrong-case values, and the two icons differ. The constants are `"public"` and `"private"`, and `DanceLibraryModel.isPrivate` / `isVisibleTo` behave consistently for owner, stranger and null.

#### `DanceStylePickerTest` (9 tests, pure helpers)
The standard styles have unique, non-blank keys; each is recognised as standard with no suggestion; the standard check ignores case and punctuation; custom styles ("Voguing", "Tango Nuevo") are not standard; blank input has no suggestion; near misses ("hiphop", "Hiphp", "kpop", "balet") suggest the standard spelling; different casing returns the canonical style; unrelated custom styles get no suggestion; test tags derive from stable lowercase keys.

#### `PoseSimilarityEvaluatorExtraTest` (about 28 tests)

| Test group | What it checks |
|---|---|
| Baseline and ignored input (5) | An identical pose scores 1.0 with "Nice match" feedback; a snapshot before any frame has no scores; learner poses, reference frames or templates that are too short or empty are ignored. |
| Mirroring (3) | A flipped learner matches a mirrored reference (1.0); without mirroring the flipped learner is penalised, and with mirroring an unflipped learner is penalised. |
| Feedback wording (7) | Right-arm and leg up/down cues, left-arm left/right cues, the area with the largest mismatch wins, and small errors below the joint threshold give "Reset your posture and match the reference shape." Feedback is recorded once per distinct message. Two of these tests pin the current "Lower"/"Lift" wording, so they need updating if that wording changes. |
| Thresholds and visibility (3) | `goodThreshold` flips between "Nice match" and a correction; joints with low visibility are excluded from the error; with no visible joints the error is 1.0 and the feedback is the reset message. |
| Series and segments (8) | The timeline is downsampled to the sample interval; without `includeSeries` the series stay empty; segments split when the label changes (GOOD then BAD) and an in-between score is labelled OK; steady performance gives full consistency and smoothness, alternating good and bad frames gives zero; the live score reacts slower than the overall average; unsorted template frames are sorted. |

#### `PoseTrackingTest` (about 26 tests)
Under test: `MultiPersonPoseTracker`, `PoseBounds`, `PoseTrackingConfig` and the pose helper functions.

| Test group | What it checks |
|---|---|
| Template building (8) | No frames or only empty detections returns null. Detections with no visible landmarks or an empty list are ignored. A track shorter than `minTrackFrames`, or with low average visibility, tiny area or low coverage, is dropped. A single dancer fills all template fields including a one-candidate preview. The dancer seen in most frames becomes "Dancer 1" even if detected second. A missing frame size gives a zero-size preview and zero fps does not divide by zero. |
| Warnings (2) | Low confidence adds `lowConfidenceTracks`, sparse tracks add `manyMissingFrames`. |
| Config presets (3) | `standard` keeps a strong track and rejects a weak one, `highRecall` keeps it, and the preset values (including clamps) are as expected. |
| `PoseBounds` and helpers (13) | Derived width, height, centre, area and `contains`; inverted edges give zero size; IoU for identical, disjoint and partial overlap (1, 0, 1/3) and zero-area boxes; padded boxes clamp to the unit square; zero padding gives a tight box over visible landmarks only; null for nothing visible or too short input; `averageVisibility` averages every fourth value. |

#### `CropPoseEstimatorTest` (about 29 tests)
Uses `mockkStatic(Bitmap::class)` and mocked MediaPipe helpers.

| Test group | What it checks |
|---|---|
| Body-only estimator (9) | No dancers returns empty without detecting; lost and ended dancers are skipped; tiny boxes (one or many) produce a `cropPoseMissing` warning without detecting; a custom minimum size rejects a normal box; a bitmap creation failure, no landmarks and a detector exception are each reported as missing, with the crop recycled where one was created; `close()` closes the helper. |
| Holistic estimator (9) | Empty input never calls the fallback; inactive dancers are skipped; tiny boxes, empty body poses, detector exceptions and bitmap failures fall back to the body-only estimator with a warning; success remaps pose and hands to full-frame coordinates with confidence `min(pose, hand)`; a pose without hands is kept with a "no hand landmarks" warning; `close()` closes both helpers even if one throws. |
| Geometry and config (9) | Negative padding is treated as zero; metadata survives expansion; an invalid frame size gives an invalid rect; a full-frame box covers the whole frame; a degenerate box still has one pixel; remapping clamps visibility and keeps z; empty input gives empty output; config defaults match constants; the no-op estimator returns an empty result. |

#### `VideoPoseTemplateGeneratorTest` (11 tests)
Adds to `SamplingFpsTest`: negative duration uses the preferred rate; exactly at the frame budget keeps 8 fps while 120,001 ms drops to 7; a preferred rate below the minimum is not raised; very long videos clamp to 3 fps; duration times fps never exceeds 960 for 130–300 s; the result never exceeds the preferred rate; `frameTimeMs` is zero for index 0, treats non-positive fps as 1 and is strictly increasing; the max-duration and warning-code constants; and the STANDARD and HIGH_RECALL profiles exist.

### 6.4 Rewards, notifications, sharing and QR (`app/src/test/...`)

| Test file | Tests | What it checks |
|---|---|---|
| `danceactivity/repository/PointsRepositoryTest` | 6 | Reads `totalPoints` and `lastSessionPoints` from `users/{uid}/points/current`; a missing doc or missing fields default to zero; the default snapshot is zero. |
| `danceactivity/repository/AchievementRepositoryTest` | 11 | Earned badge ids come from `users/{uid}/achievements/current`; a missing doc, missing field or non-list gives an empty set; unknown, wrong-case and blank names are dropped; duplicates collapse; badge definitions are returned only for earned badges, in `ALL_BADGES` order, as full definition objects. |
| `danceactivity/repository/FirebaseStreakRepositoryTest` | 8 | A missing doc gives an empty streak for this Monday; a current-week streak passes through unchanged; an ended or future week shows an empty week (`daysMask=0`) but keeps totals and the last activity date; missing fields use defaults; the auto-reset flag does not stop an ended week resetting; path check; data class defaults. |
| `weeklychallenge/repository/WeeklyChallengeRepositoryTest` | 13 | An existing week is a plain read with no server call; all challenge fields are parsed (non-string style entries filtered); a challenge with only a type gets defaults; unknown or missing types are skipped; an unknown focus metric becomes null; doubles are truncated to ints; missing bonus fields default to 50 points, not completed, not claimed; a missing doc or one without `weekStart` calls the server with the phone's time-zone offset and reads the week it returns; a server response without or with a non-string `weekStart` falls back to the local week; a week still missing afterwards returns an empty set. |
| `weeklychallenge/WeeklyChallengeModelsTest` | 12 | `progressFraction` for partial, zero, reached, over target (capped at 1), negative progress (floored at 0) and zero or negative targets (1); model defaults; metric labels; enum round-trips; the three challenge slots. |
| `danceactivity/AchievementModelsTest` | 14 | `levelForPoints` boundaries at 0, 199/200, 499/500, 999/1000 and 1999/2000 with a fallback for negatives; `nextLevelForPoints` (and null at the top level); levels are numbered 1–5 with increasing thresholds; every `BadgeId` appears once in `ALL_BADGES` with unique, non-blank names and descriptions; badge descriptions contain the server thresholds (500, 1000, 100). |
| `leaderboard/LeaderboardMyEntryTest` | 14 | `observeMyGlobalEntry`: rank is 1 plus the number of users with a strictly higher score (1 and 138 in the examples) and `isMe` is set; stat fields are copied; a blank name becomes "User"; a stored field value wins; the similarity and active-days metrics query the correct fields; active days fall back to `streakDays`; a missing doc, null doc or listener error emits null; a failed count query emits nothing; a changed doc re-emits (ranks 6 then 2); the listener is removed on cancel. |
| `notification/ReminderSchedulerTest` | about 25 | The next daily alarm is the next occurrence of the wall-clock time (rolling to tomorrow when passed), always in the future within 24 h, with zero seconds; it follows the phone time zone (Singapore, New York, Auckland, Kolkata); it uses an exact wake-up alarm with request code 2001, update-current and immutable flags and extras for re-arming; `rescheduleFromPreferences` cancels when push is off and schedules at the stored time when on; `cancelDaily`, `cancelAll` and the repeating test alarms (5 and 60 minutes, request codes 2005/2060/3015) schedule and cancel the right alarms. |
| `notification/PushTokensTest` | 9 | Save writes `token`, platform `android` and `updatedAt` under `users/{uid}/fcmTokens`; failures and Firestore exceptions are logged or swallowed; register saves a delivered token and logs when unavailable or when the fetch fails; unregister deletes the token and swallows errors. |
| `notification/BootReceiverTest` | 7 | Boot-completed and package-replaced reschedule the daily alarm at the stored time when push is on; nothing is scheduled when off but the pending result is still finished; unrelated actions, null intents and intents without an action are ignored. |
| `notification/ChorevoMessagingServiceTest` | 3 | `onNewToken` saves the token for the signed-in user and does nothing when signed out; the group invitation type constant matches the server payload. |
| `notification/NotificationPreferencesDataStoreTest` | 9 | Against a real DataStore in a temporary folder: push enabled and notification time (hour, minute and pair flows) are read back and overwrite correctly without affecting each other; theme mode and onboarding-seen are stored; a new instance sees values written by another. |
| `core/share/ShareLinksTest` | about 24 | `danceLink` and `groupInviteLink` build HTTPS links on the hosting domain and encode reserved characters; `parseInviteCode` and `parseDanceId` accept hosting, `firebaseapp.com`, the legacy domain, plain HTTP and the `chorevo://` scheme, and reject unknown hosts or schemes, wrong first path segment, missing, blank or extra path segments, and wrong-length codes; links the app builds round-trip. |
| `community/InviteQrTest` | 8 | The QR bitmap is square at the requested size (default 720), contains only black and white pixels with both dark modules and a light background, has a white quiet zone on all edges, and is deterministic for the same text and different for different text. |

### 6.5 Cloud Functions handler tests (`functions/test/`)

These run against `functions/test/support/harness.js`, a test harness that stubs `firebase-functions` and `firebase-admin` so the real handlers in `index.js` can be called directly. It provides an in-memory Firestore (documents, collection groups, queries, transactions, batches, bulk writer, recursive delete and field-value sentinels), a fake Storage bucket and a fake Messaging service, with failure injection. The fake transaction applies buffered writes at commit but not atomically.

| Test file | Tests | What it checks |
|---|---|---|
| `account.test.js` | 3 | `userStoragePrefixes` lists all 6 per-user folders with trailing slashes, the trailing slash stops `u1` matching `u10`, `u1x` or `xu1`, and different users get distinct prefixes. |
| `index.share.test.js` | 6 | `danceShare`: a public dance returns 200 with title, byline, share URL and security and cache headers; a missing dance serves the generic fallback; private or disabled dances never show the title; malformed paths cause no Firestore read; titles are HTML-escaped; a Firestore error is logged and the fallback is still served. |
| `index.discovery.test.js` | 21 | `danceDerivedFields` (new dance gets feed fields and is counted; no-op when already derived; a type change moves the count; delete only decrements; private dances are not counted; failures are logged or rejected), `revokeLinksWhenPrivate` (tokens rotate with new URLs and `linksRevokedAt`; no-op for other changes; foreign files untouched; partial failure leaves the marker unset; save failure rejects so it retries), `danceLikeCount` (exact recount; replay-safe; unlike; rewritten or empty events and deleted dances do nothing) and `refreshDiscovery` (full run; one-off migrations not repeated; a failing catch-up is logged while later steps still run; core failure rejects). |
| `index.groups.test.js` | 34 | `groupMediaUrls` (unauthenticated, invalid input, permission checks, one-hour signed links, skipped posts, duplicate ids, signing failure), `lockGroupPostMedia` (lock, skip, idempotence, missing file, file failure leaves the post for retry), `groupMemberCount` (exact, replay-safe, deleted group), `notifyGroupInvitation` (push to all devices with server-side names; ignored incomplete invitations; per-invitation dedupe; hourly rate limit; group gone or inviter not a member; no tokens; inviter name fallbacks; 20-device cap; only dead tokens deleted; FCM error rejects) and `groupCleanup` (full cleanup of subcollections, own media, invite doc and invitations while keeping other groups and feed videos; foreign invite codes and media untouched; tolerates sparse input and file failures; repeat-safe; recursive-delete failure rejects). |
| `index.rewards.test.js` | 24 | `rewardsFromSession` (first session writes points, badge, streak, challenge and leaderboard docs; replay-safe; accumulates onto existing state with legacy history seeded once; invalid or deleted input writes nothing; daily cap; time-zone clamping; challenge progress using the dance's style; missing dance tolerated; commit failure rejects), `weeklyChallenges` (unauthenticated; first request personalises a set; existing set returned without writing; time-zone handling; concurrent creation not overwritten; write failure rejects) and `leaderboardFromProfile` (skips deletes and unchanged name or photo; ignores users not on the leaderboard; refreshes name, photo and `updatedAt`; rebuilds from reward state when present; sanitises names and photos; write failure rejects). |
| `index.deleteUserData.test.js` | 7 | Account deletion removes the profile and subcollections, leaderboard entry, `pushRate`, username, own dances, likes, comments, posts, replies, invitations in both directions and owned groups while keeping other users' data and look-alike `u10` data; clears all 6 Storage folders; deletes owned groups but only the membership in others'; logs exact counts; works for a user with no data and is repeatable; a Storage failure is logged while other folders are still cleared; a Firestore failure rejects before Storage is reached. |
| `maintenance.test.js` | 21 | `countLikes`, `refreshAllDances` (fills fields, like counts and scores and rewrites categories; writes only changed dances; recount fixes drift; trending scores age; empty input; batches of 400; logging and failure propagation), `backfillLikeUids` and `backfillLikeUidsOnce` (adds uid and `createdAt` only where missing; idempotent; batched; marker handling; failure leaves the marker unset), `updateCategoryCounts` (enter, leave, chip change, no-ops and spelling merging) and `removeProfileEmails` (strips emails from `users/*` only; idempotent; batched). |

### 6.6 Rules tests (`rules-tests/`, run on the emulator)

#### `storage-profile-photos.test.js` (9 tests)

| Test | What it checks |
|---|---|
| profile photos: read | Any signed-in user can read one by path; anonymous cannot. |
| profile photos: list | Nobody can list a `profilePhotos` folder. |
| profile photos: write | Only the owner can write, overwrite or delete in their folder. |
| profile photos: type and size | Must be an image under 10 MB (PNG allowed; HTML and video denied; 10 MB − 1 allowed, exactly 10 MB denied). |
| cover frames | `danceThumbnails` and `groupThumbnails` need `.jpg` names, an image type, under 10 MB, owner-only writes, no list and no reads by others. |
| group videos | Owner-only `.mp4` video uploads, no list, no reads by others. |
| pose templates | Owner uploads typed JSON only, in their own folder, under 20 MB; delete is owner-only. |
| legacy templates | Unscoped legacy template paths can no longer be written and the folder cannot be listed. |
| unruled paths | Any path without a rule is closed to reads, writes and list. |

#### `firestore-gaps.test.js` (9 tests)

| Test | What it checks |
|---|---|
| weekly challenges: read | Only the owner can read them (single doc and list); others and anonymous are denied. |
| weekly challenges: write | Nobody can create, update or delete them, not even the owner. |
| legacy `users/{uid}/streak` | Owner-read and never writable. |
| friends | The owner has full control of their own list; others and anonymous get nothing. |
| likes: create | Only `liked: true` with allowed fields, keyed by the caller's own uid; no extra keys, bad `createdAt` or someone else's uid. |
| likes: delete | Removable by the liker or the dance owner, not by a bystander. |
| comments | No edits; deletable by the author or dance owner; impersonation, empty text and text over 1000 characters are refused. |
| retired forum | Readable when signed in; authors can delete their own posts and replies; no edits or new replies. |
| unknown paths | Unknown collections and unknown user subcollections are denied. |

### 6.7 New instrumented tests (`app/src/androidTest/...`, not compiled or run)

These need a device or emulator and mirror the style of the existing Compose tests (`createAndroidComposeRule<ComponentActivity>()`, MockK mocks, real ViewModels). `VideoUploadActivityScreenTest` is a new Compose test that replaces the misplaced ViewModel test of the same name that was removed earlier.

| Test file | Tests | What it checks |
|---|---|---|
| `weeklychallenge/WeeklyChallengesScreenTest` | 14 | Top bar title and Back callback; the screen loads for the given uid; the week label is built from `weekStart` (or falls back to "This week"); header shows "1 of 3 complete" and the bonus hint; challenge cards show title, description, progress and reward; the last card is reachable by scrolling; the check mark appears only on completed cards; when all are complete the bonus-unlocked text shows; loading shows the shimmer list; an empty week shows the empty state; a load failure shows "Couldn't load challenges" with the message or a default. |
| `firebase/UserProfileScreenTest` | 14 | Header name, email, Settings and Logout (blank name becomes "User", missing email becomes "-"); button callbacks; launch refreshes streak, points, likes and challenges; stats show points, streak and achievement level; stat labels; "Not logged in." status; the weekly summary card (unlock hint when empty, rows and counts when populated, "View all" callback); all tabs present; the Liked tab empty state. |
| `notification/SettingsScreenTest` | 23 | Title and Back; header name, email and initials or picture; the username prompt versus "@alice · Change"; appearance options save `light` or `system`; notification card and switch; with push off the time row asks to enable notifications; with push on the time shows (7:05 AM, 3:30 PM); turning the switch off saves and reschedules; the time picker opens, cancels and saves 9:00; Sign Out callback; the delete-account dialog (open, cancel, confirm calls `deleteAccount`, failure keeps the dialog open and shows the error). Tests needing push on are skipped when system notifications are blocked. |
| `community/GroupsScreenTest` (with `GroupsTestFixture`) | 26 | My groups (shimmer, empty state and its buttons, list content, lock icon for private groups, open a group); invitations (headings, tab badge, hidden when none, Accept and Decline call the repo); discover (list, empty state, search, open); create dialog (disabled until valid, public and private creation calls, cancel, failure snackbar); join dialog (needs a full code, normalises input and calls `joinWithCode`, error snackbar, a pending invite code prefills the dialog). |
| `community/GroupDetailScreenTest` | 38 | Header (name, visibility, member count, description, invite code card and QR dialog); back; membership (join banner for non-members, composer for members); posts (shimmer, empty text, text and dance posts, open dance, delete button visibility, delete confirm and cancel); composer (Post disabled until text, text post call, failure snackbar, share-dance button disabled without dances, attach and remove a dance chip); replies sheet (open, list, send, no field for non-members); menu items by role with leave and delete confirmation; members dialog (owner label, Remove and Unban call the repo); invite by username (search, invite, no match, "Member" label). |
| `videouploads/UploadWizardTest` | 16 | Upload step ordering and bounds and the 10-minute limit constant; step indicator text for each step; publish summary (real values and placeholders); cover-frame picker without a slider when duration is unknown; visibility selector texts, click reports `private` and disabled state; style picker (label, placeholder, picking a style, "Other…" switches to free text, "Did you mean Hip Hop?" suggestion). |
| `videouploads/VideoUploadActivityScreenTest` | 22 | Step 1 (title, indicator, prompt; Next disabled and no Back without a video; `showPickButton=false` hides the picker; an initial video shows "Video selected"); source dialog and cancel; moving between steps; details fields update the draft and Next needs every field; dancer review panel and the analysis checkbox gating Next; publish summary, choosing Private, and Publish calls `uploadDance` with the exact arguments then returns to step 1 with the "Uploaded…" status; "Publishing..." state with Back disabled; failure status card and Dismiss; load failure status card. |
| `core/routes/AppRouterTest` | 30 | Bottom navigation: five destinations, each tab switches screen and calls only its callback, the bar is hidden on AUTH and DANCE_PRACTICE and visible on SETTINGS and WEEKLY_CHALLENGES, the feed can tuck it away, the groups badge shows the count with a "9+" cap and none at 0, content padding is correct and HOME is full-bleed. `Screen.backTarget` hierarchy. `AppViewModel` start screen, `onAuthed`, every `open*` function, the `navigateBack` chain ending with false at the root, logout, and group invite link and push handling (including a link parked until sign-in). `AppRouter` signed-out only: spinners while the auth check or onboarding preference is pending, and onboarding shown on first launch without the bottom bar. |

### 6.8 Still not covered

Paths that need the real Android runtime or hardware were left out: `ReminderReceiver.onReceive` and `ChorevoMessagingService.onMessageReceived` (build real notifications), `VideoCompressor` and `VideoTemplateGenerationWorker` (Media3, WorkManager, Hilt), the successful `uploadDance` path, `InviteQr.scan` (Google code scanner), the real camera frame scoring in `PracticeViewModel`, and the Hilt-backed paths of `AppRouter` (signed-in routing), onboarding Skip, the username dialog and the deep-link snackbar. A 200 MB Storage video-size-cap rules test was also skipped as too heavy for the emulator.
