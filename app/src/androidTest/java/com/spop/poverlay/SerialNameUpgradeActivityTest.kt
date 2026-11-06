package com.spop.poverlay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class SerialNameUpgradeActivityTest {
    @get:Rule(order = 0) val upgrade = object : ExternalResource() {
        private lateinit var configuration: android.content.SharedPreferences
        private lateinit var notices: android.content.SharedPreferences
        private var savedBle: Boolean? = null
        private var savedDirCon: Boolean? = null
        private var savedPending: Boolean? = null
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            configuration = context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, android.content.Context.MODE_PRIVATE)
            notices = context.getSharedPreferences("upgrade-notices", android.content.Context.MODE_PRIVATE)
            savedBle = configuration.all["bleTxEnabled"] as Boolean?
            savedDirCon = configuration.all["dirConEnabled"] as Boolean?
            savedPending = notices.all["serialNameUpgradeNoticeV1Pending"] as Boolean?
            configuration.edit().putBoolean("bleTxEnabled", false).putBoolean("dirConEnabled", false).commit()
            notices.edit().remove("serialNameUpgradeNoticeV1Pending").commit()
            SerialNameUpgradeNotice(notices).initialize(hasExistingSerial = true)
        }
        override fun after() {
            fun restore(preferences: android.content.SharedPreferences, key: String, value: Boolean?) {
                preferences.edit().apply {
                    if (value == null) remove(key) else putBoolean(key, value)
                }.commit()
            }
            restore(configuration, "bleTxEnabled", savedBle)
            restore(configuration, "dirConEnabled", savedDirCon)
            restore(notices, "serialNameUpgradeNoticeV1Pending", savedPending)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun activityRecreationKeepsPendingNoticeAndRemembersDismissal() {
        compose.onNodeWithText("Check your Grupetto connection").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Check your Grupetto connection").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Check your Grupetto connection").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Check your Grupetto connection").assertDoesNotExist()
    }
}
