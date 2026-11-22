package de.nightdesk.trade

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class StartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsInPaperModeAndOpensTradeScreen() {
        compose.onNodeWithText("PAPIERHANDEL · SIMULATION").assertIsDisplayed()
        compose.onNodeWithText("Virtuelles Portfolio").assertIsDisplayed()
        compose.onNodeWithText("Trading", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Papierorder").assertIsDisplayed()
        compose.onNodeWithText("Ordervorschau").assertIsDisplayed()
    }
}
