package com.spop.poverlay

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialNameUpgradeNoticeTest {
    private val stored = mutableMapOf<String, Boolean>()
    private val pending = mutableMapOf<String, Boolean>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    init {
        every { preferences.contains(any()) } answers { stored.containsKey(firstArg()) }
        every { preferences.getBoolean(any(), any()) } answers {
            stored[firstArg<String>()] ?: secondArg<Boolean>()
        }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            pending[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.apply() } answers {
            stored.putAll(pending)
            pending.clear()
        }
        every { editor.commit() } answers {
            stored.putAll(pending)
            pending.clear()
            true
        }
    }

    @Test fun upgradeWithExistingSerialShowsNotice() {
        val notice = SerialNameUpgradeNotice(preferences)
        notice.initialize(hasExistingSerial = true)
        assertTrue(notice.shouldShow)
    }

    @Test fun freshInstallStaysQuietEvenAfterItsSerialHasBeenCreated() {
        SerialNameUpgradeNotice(preferences).initialize(hasExistingSerial = false)
        val restarted = SerialNameUpgradeNotice(preferences)
        restarted.initialize(hasExistingSerial = true)
        assertFalse(restarted.shouldShow)
    }

    @Test fun backgroundStartAndActivityRecreationDoNotConsumeNotice() {
        SerialNameUpgradeNotice(preferences).initialize(hasExistingSerial = true)
        repeat(3) {
            val recreated = SerialNameUpgradeNotice(preferences)
            recreated.initialize(hasExistingSerial = true)
            assertTrue(recreated.shouldShow)
        }
    }

    @Test fun dismissalIsRememberedAcrossRestartsAndFutureUpgrades() {
        val notice = SerialNameUpgradeNotice(preferences)
        notice.initialize(hasExistingSerial = true)
        notice.dismiss()
        assertFalse(notice.shouldShow)
        val nextUpgrade = SerialNameUpgradeNotice(preferences)
        nextUpgrade.initialize(hasExistingSerial = true)
        assertFalse(nextUpgrade.shouldShow)
    }
}