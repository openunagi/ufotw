package com.first.ufotw

import java.util.UUID

object UfoTwProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("40ee1111-63ec-4b7f-8ce7-712efd55b90e")
    val TX_CHAR_UUID: UUID  = UUID.fromString("40ee2222-63ec-4b7f-8ce7-712efd55b90e")

    const val CW  = 0
    const val CCW = 1

    /** 両ロータを1パケットで制御 */
    fun buildCommand(speed1: Int, dir1: Int, speed2: Int, dir2: Int): ByteArray {
        val b1 = ((dir1 shl 7) or speed1.coerceIn(0, 100)).toByte()
        val b2 = ((dir2 shl 7) or speed2.coerceIn(0, 100)).toByte()
        return byteArrayOf(0x05, b1, b2)
    }

    fun stopCommand(): ByteArray = buildCommand(0, CW, 0, CW)
}
