package com.spop.poverlay

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.darkColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CoffeeLinkTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun logoClickLaunchesExactSupportUrl() {
        var launched: Intent? = null
        val browser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides browser) {
                MaterialTheme(colors = darkColors()) { Surface { CoffeeLink() } }
            }
        }
        compose.onNodeWithContentDescription("Buy me a coffee (opens browser)")
            .assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_VIEW, launched?.action)
            assertEquals("https://buymeacoffee.com/anthonydouv", launched?.dataString)
        }
    }

    @Test fun missingBrowserShowsCopyableLinkAndCanDismiss() {
        val browser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides browser) {
                MaterialTheme(colors = darkColors()) { Surface { CoffeeLink() } }
            }
        }
        compose.onNodeWithContentDescription("Buy me a coffee (opens browser)").performClick()
        compose.onNodeWithText("Could not open a browser").assertIsDisplayed()
        compose.onNodeWithText(COFFEE_URL).assertIsDisplayed()
        compose.onNodeWithText("Copy link").performClick()
        compose.onNodeWithText("Could not open a browser").assertDoesNotExist()
    }

    @Test fun restrictedBrowserDoesNotCrash() {
        val browser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException("Browser blocked") }
        }
        assertFalse(openCoffeeLink(browser))
    }
}
