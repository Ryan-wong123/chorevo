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
