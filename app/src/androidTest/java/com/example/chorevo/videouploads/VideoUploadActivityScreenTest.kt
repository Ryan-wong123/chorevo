package com.example.chorevo.videouploads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import com.example.chorevo.posedetection.VideoPoseTemplateGenerator
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import com.example.chorevo.videouploads.viewmodel.VideoUploadsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoUploadActivityScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var authRepo: FirebaseAuthRepository
    private lateinit var uploadsRepo: VideoUploadsRepository
    private lateinit var vm: VideoUploadsViewModel

    private val profile = UserProfile(uid = "u1", email = "alice@example.com", displayName = "Alice Tan")

    /** A video that cannot be read: frames stay a shimmer and the length stays unknown. */
    private val video: Uri = Uri.parse("content://com.example.chorevo.test/video/123")

    @Before
    fun setUp() {
        authRepo = mockk(relaxed = true)
        uploadsRepo = mockk(relaxed = true)
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        coEvery { uploadsRepo.loadVideos(any()) } returns Result.success(emptyList())
        buildViewModel()
    }

    private fun buildViewModel() {
        vm = VideoUploadsViewModel(
            authRepository = authRepo,
            videoUploadRepository = uploadsRepo,
            templateGenerator = mockk<VideoPoseTemplateGenerator>(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTextGone(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun setScreen(
        initialVideoUri: Uri? = null,
        showPickButton: Boolean = true,
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            VideoUploadActivityScreen(
                onBack = onBack,
                initialVideoUri = initialVideoUri,
                showPickButton = showPickButton,
                videoUploadVm = vm
            )
        }
    }

    private fun clickNext() {
        composeRule.onNodeWithTag("uploadNextButton").performClick()
    }

    private fun clickBack() {
        composeRule.onNodeWithTag("uploadBackButton").performClick()
    }

    private fun waitForStep(number: Int, title: String) = waitForTextExists("Step $number of 5 · $title")

    /** With a video selected: Video -> Cover -> Details. */
    private fun goToDetails() {
        setScreen(initialVideoUri = video)
        waitForTextExists("Video selected")
        clickNext()
        waitForStep(2, "Cover")
        clickNext()
        waitForStep(3, "Details")
    }

    /** Fills the details form through the real fields. */
    private fun fillDetails(title: String = "My Routine", style: String = "Hip Hop", description: String = "Fun moves") {
        composeRule.onNodeWithTag("titleField").performTextInput(title)
        composeRule.onNodeWithTag("danceTypeField").performClick()
        composeRule.onNodeWithText(style).performClick()
        composeRule.onNodeWithTag("descriptionField").performTextInput(description)
    }

    /** Video -> ... -> Publish, skipping dancer review (background analysis). */
    private fun goToPublish() {
        goToDetails()
        fillDetails()
        clickNext()
        waitForStep(4, "Dancers")
        composeRule.onNodeWithTag("analyzeInBackgroundCheckbox").performScrollTo().performClick()
        clickNext()
        waitForStep(5, "Publish")
    }

    // ── Step 1: video ────────────────────────────────────────────────────────

    @Test
    fun videoStep_showsTitleIndicatorAndPickerPrompt() {
        setScreen()

        composeRule.onNodeWithText("Upload Dance").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadStepIndicator").assertIsDisplayed()
        composeRule.onNodeWithText("Step 1 of 5 · Video").assertIsDisplayed()
        composeRule.onNodeWithText("Choose your dance video").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a video source").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to choose from gallery or record live").assertIsDisplayed()
    }

    @Test
    fun videoStep_withoutVideo_nextIsDisabled_andThereIsNoBackButton() {
        setScreen()

        composeRule.onNodeWithTag("uploadNextButton").assertIsNotEnabled()
        composeRule.onAllNodesWithTag("uploadBackButton").assertCountEquals(0)
        composeRule.onAllNodesWithTag("uploadButton").assertCountEquals(0)
    }

    @Test
    fun videoStep_showPickButtonFalse_hidesThePicker() {
        setScreen(showPickButton = false)

        composeRule.onNodeWithText("Choose your dance video").assertIsDisplayed()
        composeRule.onAllNodesWithText("Choose a video source").assertCountEquals(0)
    }

    @Test
    fun videoStep_withInitialVideo_showsSelectedAndEnablesNext() {
        setScreen(initialVideoUri = video)

        waitForTextExists("Video selected")
        composeRule.onNodeWithText("Video selected").assertIsDisplayed()
        // The label is the last path segment of the video's Uri.
        composeRule.onNodeWithText("123").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadNextButton").assertIsEnabled()
        assertEquals(video, vm.uiState.value.uploadDraft.selectedVideoUri)
    }

    @Test
    fun clickPicker_opensSourceDialog_withBothOptions() {
        setScreen()

        composeRule.onNodeWithText("Choose a video source").performClick()

        waitForTextExists("Add your dance video")
        composeRule.onNodeWithText("Choose from gallery").assertIsDisplayed()
        composeRule.onNodeWithText("Pick an existing video saved on the device.").assertIsDisplayed()
        composeRule.onNodeWithText("Record live video").assertIsDisplayed()
        composeRule.onNodeWithText("Open the camera, record now, and upload that clip.").assertIsDisplayed()
    }

    @Test
    fun sourceDialog_cancel_dismissesIt() {
        setScreen()

        composeRule.onNodeWithText("Choose a video source").performClick()
        waitForTextExists("Add your dance video")
        composeRule.onNodeWithText("Cancel").performClick()

        waitForTextGone("Add your dance video")
        assertTrue(!vm.uiState.value.showVideoSourceDialog)
    }

    // ── Step navigation ──────────────────────────────────────────────────────

    @Test
    fun next_movesToCoverStep_andBackReturnsToVideoStep() {
        setScreen(initialVideoUri = video)
        waitForTextExists("Video selected")

        clickNext()

        waitForStep(2, "Cover")
        composeRule.onNodeWithText("Choose a cover").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadBackButton").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadNextButton").assertIsEnabled()

        clickBack()

        waitForStep(1, "Video")
        composeRule.onNodeWithText("Choose your dance video").assertIsDisplayed()
    }

    // ── Step 3: details ──────────────────────────────────────────────────────

    @Test
    fun detailsStep_showsEmptyFields_andNextIsDisabled() {
        goToDetails()

        composeRule.onNodeWithText("Describe your dance").assertIsDisplayed()
        composeRule.onNodeWithTag("titleField").assertIsDisplayed()
        composeRule.onNodeWithTag("danceTypeField").assertIsDisplayed()
        composeRule.onNodeWithTag("descriptionField").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadNextButton").assertIsNotEnabled()
    }

    @Test
    fun detailsStep_typingUpdatesTheDraft_andNextNeedsEveryField() {
        goToDetails()

        composeRule.onNodeWithTag("titleField").performTextInput("My Routine")
        composeRule.onNodeWithTag("uploadNextButton").assertIsNotEnabled()

        composeRule.onNodeWithTag("danceTypeField").performClick()
        composeRule.onNodeWithText("Hip Hop").performClick()
        composeRule.onNodeWithTag("uploadNextButton").assertIsNotEnabled()

        composeRule.onNodeWithTag("descriptionField").performTextInput("Fun moves")
        composeRule.onNodeWithTag("uploadNextButton").assertIsEnabled()

        val draft = vm.uiState.value.uploadDraft
        assertEquals("My Routine", draft.title)
        assertEquals("Hip Hop", draft.danceType)
        assertEquals("Fun moves", draft.description)
    }

    // ── Step 4: dancers ──────────────────────────────────────────────────────

    @Test
    fun dancersStep_showsReviewPanel_andNextNeedsReviewOrBackgroundAnalysis() {
        goToDetails()
        fillDetails()
        clickNext()

        waitForStep(4, "Dancers")
        composeRule.onNodeWithText("Confirm the dancers").assertIsDisplayed()
        composeRule.onNodeWithText("Dancer Review").assertIsDisplayed()
        composeRule.onNodeWithText("Analyze the video before upload.").assertIsDisplayed()
        composeRule.onNodeWithText("Analyze Dancers").assertIsDisplayed()
        composeRule.onNodeWithText("Skip review and analyze in the background after upload").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadNextButton").assertIsNotEnabled()

        composeRule.onNodeWithTag("analyzeInBackgroundCheckbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("uploadNextButton").assertIsEnabled()

        composeRule.onNodeWithTag("analyzeInBackgroundCheckbox").performScrollTo().performClick()
        composeRule.onNodeWithTag("uploadNextButton").assertIsNotEnabled()
    }

    // ── Step 5: publish ──────────────────────────────────────────────────────

    @Test
    fun publishStep_showsSummaryVisibilityAndPublishButton() {
        goToPublish()

        composeRule.onNodeWithText("Ready to publish").assertIsDisplayed()
        composeRule.onNodeWithText("My Routine").assertIsDisplayed()
        composeRule.onNodeWithText("Hip Hop").assertIsDisplayed()
        composeRule.onNodeWithText("Analyzed in the background").assertIsDisplayed()
        composeRule.onNodeWithText("Who can see this video?").assertIsDisplayed()
        composeRule.onNodeWithTag("uploadButton").assertIsEnabled()
        composeRule.onAllNodesWithTag("uploadNextButton").assertCountEquals(0)
    }

    @Test
    fun publishStep_choosingPrivate_updatesTheDraftVisibility() {
        goToPublish()

        composeRule.onNodeWithTag("visibility_private").performScrollTo().performClick()

        waitForTextExists("Only you can see it, in your profile.")
        assertEquals(DanceLibraryModel.VISIBILITY_PRIVATE, vm.uiState.value.uploadDraft.visibility)
    }

    @Test
    fun publish_withBackgroundAnalysis_uploadsTheDraftAndReturnsToStepOne() {
        coEvery {
            uploadsRepo.uploadDance(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.success("dance-1")
        goToPublish()

        composeRule.onNodeWithTag("uploadButton").performClick()

        coVerify(timeout = 5_000) {
            uploadsRepo.uploadDance(
                context = any(),
                educatorUid = "u1",
                educatorName = "Alice Tan",
                title = "My Routine",
                danceType = "Hip Hop",
                description = "Fun moves",
                videoUri = video,
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                coverFrameMs = any(),
                generateTemplate = true,
                confirmedTemplate = null,
                fps = 8,
                collectHandLandmarks = any(),
                onProgress = any(),
                onStageChanged = any(),
                onTemplateFailure = any(),
                continueWithoutTemplateOnFailure = true
            )
        }
        // Success clears the draft, which starts the next upload from step 1.
        waitForTextExists("Uploaded. Template generation continues in the background.")
        waitForStep(1, "Video")
        composeRule.onNodeWithText("Choose a video source").assertIsDisplayed()
    }

    @Test
    fun publish_whileUploading_showsPublishingAndDisablesNavigation() {
        val gate = CompletableDeferred<Result<String>>()
        coEvery {
            uploadsRepo.uploadDance(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers { gate.await() }
        goToPublish()

        composeRule.onNodeWithTag("uploadButton").performClick()

        waitForTextExists("Publishing...")
        composeRule.onNodeWithTag("uploadButton").assertIsNotEnabled()
        composeRule.onNodeWithTag("uploadBackButton").assertIsNotEnabled()

        gate.complete(Result.success("dance-1"))
        waitForTextGone("Publishing...")
    }

    @Test
    fun publish_failure_showsStatusCard_andDismissClearsIt() {
        coEvery {
            uploadsRepo.uploadDance(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns Result.failure(RuntimeException("storage quota"))
        goToPublish()

        composeRule.onNodeWithTag("uploadButton").performClick()

        waitForTextExists("Upload failed: storage quota")
        composeRule.onNodeWithText("Upload failed: storage quota").assertIsDisplayed()
        // The draft is kept so the user can retry from the same step.
        composeRule.onNodeWithText("Step 5 of 5 · Publish").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()

        waitForTextGone("Upload failed: storage quota")
    }

    // ── Status from loading uploads ──────────────────────────────────────────

    @Test
    fun loadFailure_showsStatusCardWithDismiss() {
        coEvery { uploadsRepo.loadVideos(any()) } returns Result.failure(RuntimeException("offline"))
        buildViewModel()
        setScreen()

        waitForTextExists("Failed to load uploads: offline")
        composeRule.onNodeWithText("Failed to load uploads: offline").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()

        waitForTextGone("Failed to load uploads: offline")
    }
}
