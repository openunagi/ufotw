package com.first.ufotw

import org.junit.Assert.*
import org.junit.Test

class UfoTwProtocolTest {

    @Test
    fun `buildCommand - header byte is 0x05`() {
        val cmd = UfoTwProtocol.buildCommand(0, UfoTwProtocol.CW, 0, UfoTwProtocol.CW)
        assertEquals(0x05.toByte(), cmd[0])
    }

    @Test
    fun `buildCommand - stop is all zeros after header`() {
        val cmd = UfoTwProtocol.stopCommand()
        assertArrayEquals(byteArrayOf(0x05, 0x00, 0x00), cmd)
    }

    @Test
    fun `buildCommand - CW direction bit is 0`() {
        val cmd = UfoTwProtocol.buildCommand(50, UfoTwProtocol.CW, 50, UfoTwProtocol.CW)
        // bit7 = 0, speed = 50 = 0x32
        assertEquals(50.toByte(), cmd[1])
        assertEquals(50.toByte(), cmd[2])
    }

    @Test
    fun `buildCommand - CCW direction sets bit7`() {
        val cmd = UfoTwProtocol.buildCommand(50, UfoTwProtocol.CCW, 50, UfoTwProtocol.CCW)
        // bit7 = 1, speed = 50 → 0x80 | 0x32 = 0xB2
        val expected = (0x80 or 50).toByte()
        assertEquals(expected, cmd[1])
        assertEquals(expected, cmd[2])
    }

    @Test
    fun `buildCommand - rotors are independent`() {
        val cmd = UfoTwProtocol.buildCommand(30, UfoTwProtocol.CW, 70, UfoTwProtocol.CCW)
        assertEquals(30.toByte(), cmd[1])
        assertEquals((0x80 or 70).toByte(), cmd[2])
    }

    @Test
    fun `buildCommand - speed is clamped to 0-100`() {
        val cmdOver = UfoTwProtocol.buildCommand(200, UfoTwProtocol.CW, -10, UfoTwProtocol.CW)
        assertEquals(100.toByte(), cmdOver[1])
        assertEquals(0.toByte(), cmdOver[2])
    }

    @Test
    fun `buildCommand - max speed 100`() {
        val cmd = UfoTwProtocol.buildCommand(100, UfoTwProtocol.CW, 100, UfoTwProtocol.CW)
        assertEquals(100.toByte(), cmd[1])
        assertEquals(100.toByte(), cmd[2])
    }

    @Test
    fun `buildCommand - command length is always 3`() {
        val cmd = UfoTwProtocol.buildCommand(50, UfoTwProtocol.CCW, 0, UfoTwProtocol.CW)
        assertEquals(3, cmd.size)
    }
}
