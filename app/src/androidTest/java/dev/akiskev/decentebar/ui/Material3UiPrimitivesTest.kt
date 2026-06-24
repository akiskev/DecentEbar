package dev.akiskev.decentebar.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class Material3UiPrimitivesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun segmentedChoiceUpdatesSelection() {
        var selected by mutableStateOf("One")

        composeRule.setContent {
            DecentebarTheme(dynamicColor = false, themeMode = ThemeMode.SYSTEM) {
                SegmentedChoice(
                    options = listOf("One", "Two", "Three"),
                    selected = selected,
                    onSelected = { selected = it },
                    label = { it }
                )
            }
        }

        composeRule.onNodeWithText("Two").performClick()

        composeRule.runOnIdle {
            assertEquals("Two", selected)
        }
    }

    @Test
    fun sliderFieldCommitsTypedValue() {
        var pressure by mutableStateOf(3.0)

        composeRule.setContent {
            DecentebarTheme(dynamicColor = false, themeMode = ThemeMode.SYSTEM) {
                SliderField(
                    label = "Pressure",
                    value = pressure,
                    valueRange = 0f..12f,
                    steps = 119,
                    unit = "bar",
                    onChange = { pressure = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Pressure value")
            .performTextReplacement("8.5")

        composeRule.runOnIdle {
            assertEquals(8.5, pressure, 0.01)
        }
    }

    @Test
    fun labeledSwitchExposesAccessibleLabel() {
        var enabled by mutableStateOf(false)

        composeRule.setContent {
            DecentebarTheme(dynamicColor = false, themeMode = ThemeMode.SYSTEM) {
                LabeledSwitch(
                    label = "Developer Mode",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Developer Mode")
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(true, enabled)
        }
    }

    @Test
    fun metricGridDisplaysMetricValues() {
        composeRule.setContent {
            DecentebarTheme(dynamicColor = false, themeMode = ThemeMode.SYSTEM) {
                MetricGrid(
                    metrics = listOf(
                        "Service" to "Enabled",
                        "Safety" to "Idle"
                    )
                )
            }
        }

        composeRule.onNodeWithText("Service").assertIsDisplayed()
        composeRule.onNodeWithText("Enabled").assertIsDisplayed()
        composeRule.onNodeWithText("Safety").assertIsDisplayed()
        composeRule.onNodeWithText("Idle").assertIsDisplayed()
    }
}
