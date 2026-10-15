package com.example.chorevo.firebase

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstPage_showsTitle_andSkipFinishes() {
        var finished = 0
        composeRule.setContent { OnboardingScreen(onFinish = { finished++ }) }

        composeRule.onNodeWithText("Learn any routine").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
        composeRule.onNodeWithTag("onboardingSkip").performClick()

        assertEquals(1, finished)
    }

    @Test
    fun next_walksThroughPages_thenGetStartedFinishes() {
        var finished = 0
        composeRule.setContent { OnboardingScreen(onFinish = { finished++ }) }

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Live AI feedback").assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Level up every week").assertIsDisplayed()
        assertEquals(0, finished)

        composeRule.onNodeWithText("Get started").performClick()
        assertEquals(1, finished)
    }
}
