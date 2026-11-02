package com.example.chorevo.videouploads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadWizardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A video that cannot be read: frames stay a shimmer and the duration stays unknown. */
    private val unreadableVideo: Uri = Uri.parse("content://com.example.chorevo.test/video/1")

    // ── UploadStep ───────────────────────────────────────────────────────────

    @Test
    fun uploadStep_hasFiveOrderedStepsWithOneBasedNumbers() {
        assertEquals(
            listOf("Video", "Cover", "Details", "Dancers", "Publish"),
            UploadStep.entries.map { it.title }
        )
        assertEquals(listOf(1, 2, 3, 4, 5), UploadStep.entries.map { it.number })
    }

    @Test
    fun uploadStep_nextAndPrevious_stopAtTheEnds() {
        assertEquals(UploadStep.COVER, UploadStep.VIDEO.next())
        assertEquals(UploadStep.PUBLISH, UploadStep.DANCERS.next())
        assertEquals(UploadStep.PUBLISH, UploadStep.PUBLISH.next())
        assertEquals(UploadStep.DETAILS, UploadStep.DANCERS.previous())
        assertEquals(UploadStep.VIDEO, UploadStep.VIDEO.previous())
    }

    @Test
    fun maxUploadVideoLength_isTenMinutes() {
        assertEquals(10 * 60_000L, MAX_UPLOAD_VIDEO_MS)
    }

    // ── UploadStepIndicator ──────────────────────────────────────────────────

    @Test
    fun stepIndicator_showsStepNumberAndTitleForEveryStep() {
        var current by mutableStateOf(UploadStep.VIDEO)
        composeRule.setContent { UploadStepIndicator(current = current) }

        UploadStep.entries.forEach { step ->
            composeRule.runOnUiThread { current = step }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Step ${step.number} of 5 · ${step.title}").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("uploadStepIndicator").assertIsDisplayed()
    }

    // ── PublishSummary ───────────────────────────────────────────────────────

    @Test
    fun publishSummary_showsTitleTypeDancersAndPublicVisibility() {
        composeRule.setContent {
            PublishSummary(
                videoUri = unreadableVideo,
                coverFrameMs = 1_000L,
                title = "Beginner Hip Hop",
                danceType = "Hip Hop",
                dancersLabel = "2 confirmed",
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC
            )
        }

        composeRule.onNodeWithText("Beginner Hip Hop").assertIsDisplayed()
        composeRule.onNodeWithText("Dance type").assertIsDisplayed()
        composeRule.onNodeWithText("Hip Hop").assertIsDisplayed()
        composeRule.onNodeWithText("Dancers").assertIsDisplayed()
        composeRule.onNodeWithText("2 confirmed").assertIsDisplayed()
        composeRule.onNodeWithText("Visibility").assertIsDisplayed()
        composeRule.onNodeWithText("Public").assertIsDisplayed()
    }

    @Test
    fun publishSummary_blankTitleAndType_showPlaceholders_andPrivateVisibility() {
        composeRule.setContent {
            PublishSummary(
                videoUri = unreadableVideo,
                coverFrameMs = 0L,
                title = "",
                danceType = "",
                dancersLabel = "Analyzed in the background",
                visibility = DanceLibraryModel.VISIBILITY_PRIVATE
            )
        }

        composeRule.onNodeWithText("Untitled").assertIsDisplayed()
        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("Analyzed in the background").assertIsDisplayed()
        composeRule.onNodeWithText("Private").assertIsDisplayed()
    }

    // ── CoverFramePicker ─────────────────────────────────────────────────────

    @Test
    fun coverFramePicker_showsHeadingCaptionAndPreview_withoutSliderWhenDurationUnknown() {
        composeRule.setContent {
            CoverFramePicker(
                videoUri = unreadableVideo,
                coverFrameMs = 1_000L,
                onCoverFrameChange = {}
            )
        }

        composeRule.onNodeWithText("Choose a cover").assertIsDisplayed()
        composeRule.onNodeWithText("This frame is shown on your video's thumbnail in profiles and grids.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("coverPreview").assertIsDisplayed()
        // The scrubber needs the video's length; an unreadable video has none.
        composeRule.onAllNodesWithTag("coverSlider").assertCountEquals(0)
    }

    // ── VideoVisibilitySelector ──────────────────────────────────────────────

    @Test
    fun visibilitySelector_public_showsPublicExplanation() {
        composeRule.setContent {
            VideoVisibilitySelector(
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                onVisibilityChange = {},
                accentColor = Color.Red
            )
        }

        composeRule.onNodeWithText("Who can see this video?").assertIsDisplayed()
        composeRule.onNodeWithText("Public").assertIsDisplayed()
        composeRule.onNodeWithText("Private").assertIsDisplayed()
        composeRule.onNodeWithText("Everyone can see it in the feed and via share links.").assertIsDisplayed()
    }

    @Test
    fun visibilitySelector_clickPrivate_reportsPrivateKey() {
        val received = mutableListOf<String>()
        composeRule.setContent {
            VideoVisibilitySelector(
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                onVisibilityChange = { received += it },
                accentColor = Color.Red
            )
        }

        composeRule.onNodeWithTag("visibility_private").performClick()

        assertEquals(listOf(DanceLibraryModel.VISIBILITY_PRIVATE), received)
    }

    @Test
    fun visibilitySelector_private_showsPrivateExplanation() {
        composeRule.setContent {
            VideoVisibilitySelector(
                visibility = DanceLibraryModel.VISIBILITY_PRIVATE,
                onVisibilityChange = {},
                accentColor = Color.Red
            )
        }

        composeRule.onNodeWithText("Only you can see it, in your profile.").assertIsDisplayed()
    }

    @Test
    fun visibilitySelector_disabled_disablesBothChips() {
        composeRule.setContent {
            VideoVisibilitySelector(
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                onVisibilityChange = {},
                accentColor = Color.Red,
                enabled = false
            )
        }

        composeRule.onNodeWithTag("visibility_public").assertIsNotEnabled()
        composeRule.onNodeWithTag("visibility_private").assertIsNotEnabled()
    }

    // ── DanceStylePicker ─────────────────────────────────────────────────────

    @Test
    fun stylePicker_empty_showsLabel() {
        composeRule.setContent {
            DanceStylePicker(value = "", onValueChange = {})
        }

        composeRule.onNodeWithTag("danceTypeField").assertIsDisplayed()
        // The placeholder stays hidden until the field is focused, so only the label shows.
        composeRule.onNodeWithText("Dance Type").assertIsDisplayed()
    }

    @Test
    fun stylePicker_openMenu_pickingAStyleReportsIt() {
        var picked = ""
        composeRule.setContent {
            var value by remember { mutableStateOf("") }
            DanceStylePicker(value = value, onValueChange = { value = it; picked = it })
        }

        composeRule.onNodeWithTag("danceTypeField").performClick()
        composeRule.onNodeWithText("Salsa").performClick()

        composeRule.waitForIdle()
        assertEquals("Salsa", picked)
        composeRule.onNodeWithText("Salsa").assertIsDisplayed()
    }

    @Test
    fun stylePicker_otherOption_switchesToFreeTextEntry() {
        composeRule.setContent {
            var value by remember { mutableStateOf("") }
            DanceStylePicker(value = value, onValueChange = { value = it })
        }

        composeRule.onNodeWithTag("danceTypeField").performClick()
        // "Other…" is the last item of a long, scrollable menu; clicking it while it is
        // off-screen would just dismiss the menu.
        composeRule.onNodeWithTag("danceStyle_other").performScrollTo().performClick()

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("danceTypeField").assertIsDisplayed()
        composeRule.onNodeWithText("Pick from the list instead").assertIsDisplayed()
    }

    @Test
    fun stylePicker_customNearMiss_offersStandardSpellingAndAcceptsIt() {
        var current = "Hiphp"
        composeRule.setContent {
            var value by remember { mutableStateOf("Hiphp") }
            DanceStylePicker(value = value, onValueChange = { value = it; current = it })
        }

        composeRule.onNodeWithTag("danceStyleSuggestion").assertIsDisplayed()
        composeRule.onNodeWithText("Did you mean Hip Hop?").assertIsDisplayed()
        composeRule.onNodeWithTag("danceStyleSuggestion").performClick()

        composeRule.waitForIdle()
        assertEquals("Hip Hop", current)
        // Back on the list-based field.
        composeRule.onAllNodesWithText("Pick from the list instead").assertCountEquals(0)
    }

    @Test
    fun stylePicker_standardStyleValue_isShownInTheListField() {
        composeRule.setContent {
            DanceStylePicker(value = "Ballet", onValueChange = {})
        }

        composeRule.onNodeWithText("Ballet").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("danceStyleSuggestion").fetchSemanticsNodes().isEmpty())
    }
}
