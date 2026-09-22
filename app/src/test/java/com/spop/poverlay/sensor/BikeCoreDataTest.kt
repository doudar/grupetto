package com.spop.poverlay.sensor

import android.os.Parcel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class BikeCoreDataTest {

    @Test
    fun `readBikeCoreData reads only the six leading positional fields in order`() {
        val parcel = mockk<Parcel>()
        every { parcel.readLong() } returnsMany listOf(123L, 456L, 789L, 1011L)
        every { parcel.readInt() } returnsMany listOf(12, 34)

        val result = readBikeCoreData(parcel)

        assertEquals(BikeCoreData(rpm = 123L, power = 456L, currentResistance = 12, targetResistance = 34), result)
        verify(exactly = 4) { parcel.readLong() }
        verify(exactly = 2) { parcel.readInt() }
        verify(exactly = 0) { parcel.readString() }
        verify(exactly = 0) { parcel.createByteArray() }
        verify(exactly = 0) { parcel.readIntArray(any()) }
    }
}
