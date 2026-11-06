package com.spop.poverlay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSerialTest {
    @Test fun `same ANDROID_ID always gives the same serial`() {
        val first = DeviceSerial.fromAndroidId("3f2a9c81d04e7b65")
        assertEquals(first, DeviceSerial.fromAndroidId("3f2a9c81d04e7b65"))
        assertEquals(first, DeviceSerial.fromAndroidId(" 3F2A9C81D04E7B65 "))
    }

    // Pinned values: changing the hash would rename every device on its next reinstall.
    @Test fun `hash is pinned to FNV-1a reference values`() {
        assertEquals("CD20", DeviceSerial.fromAndroidId("a"))
        assertEquals("46F4", DeviceSerial.fromAndroidId("foobar"))
    }

    @Test fun `serial is four upper-case hex digits`() {
        for (id in listOf("1", "3f2a9c81d04e7b65", "ffffffffffffffff", "0000000000000001")) {
            val serial = DeviceSerial.fromAndroidId(id)!!
            assertTrue(serial, Regex("[0-9A-F]{4}").matches(serial))
        }
    }

    @Test fun `different ANDROID_IDs usually differ`() {
        assertNotEquals(DeviceSerial.fromAndroidId("3f2a9c81d04e7b65"), DeviceSerial.fromAndroidId("3f2a9c81d04e7b66"))
    }

    @Test fun `missing or shared ANDROID_ID is not used`() {
        assertNull(DeviceSerial.fromAndroidId(null))
        assertNull(DeviceSerial.fromAndroidId(""))
        assertNull(DeviceSerial.fromAndroidId("9774d56d682e549c"))
    }
}
