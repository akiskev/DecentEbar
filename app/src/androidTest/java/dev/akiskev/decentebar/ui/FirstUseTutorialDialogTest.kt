package dev.akiskev.decentebar.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstUseTutorialDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstStepRendersExpectedTitle() {
        setDialog()

        composeRule.onNodeWithText("First-use tutorial").assertIsDisplayed()
        composeRule.onNodeWithText("Stay in control").assertIsDisplayed()
        composeRule.onNodeWithText("Step 1 of 6").assertIsDisplayed()
    }

    @Test
    fun nextAndBackChangeSteps() {
        setDialog()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Enable Accessibility").assertIsDisplayed()

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Stay in control").assertIsDisplayed()
    }

    @Test
    fun skipInvokesCallback() {
        var skipCount = 0
        setDialog(onSkip = { skipCount += 1 })

        composeRule.onNodeWithText("Skip").performClick()

        composeRule.runOnIdle {
            assertEquals(1, skipCount)
        }
    }

    @Test
    fun doneInvokesCallbackOnFinalStep() {
        var doneCount = 0
        setDialog(onDone = { doneCount += 1 })

        repeat(FirstUseTutorialSteps.lastIndex) {
            composeRule.onNodeWithText("Next").performClick()
        }
        composeRule.onNodeWithText("Done").performClick()

        composeRule.runOnIdle {
            assertEquals(1, doneCount)
        }
    }

    @Test
    fun finalStepContainsAboutReplayMessage() {
        setDialog()

        repeat(FirstUseTutorialSteps.lastIndex) {
            composeRule.onNodeWithText("Next").performClick()
        }

        composeRule.onNodeWithText("Run, stop, and review").assertIsDisplayed()
        composeRule.onNodeWithText("About > First-use tutorial", substring = true).assertIsDisplayed()
    }

    private fun setDialog(
        onSkip: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        composeRule.setContent {
            DecentebarTheme(dynamicColor = false, themeMode = ThemeMode.SYSTEM) {
                FirstUseTutorialDialog(
                    onSkip = onSkip,
                    onDone = onDone
                )
            }
        }
    }
}
