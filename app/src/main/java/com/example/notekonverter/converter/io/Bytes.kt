package com.example.notekonverter.converter.io

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Growable byte buffer with explicit-endianness primitive writers. */
class ByteSink(private val order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {
    private val out = ByteArrayOutputStream()
    private val scratch = ByteBuffer.allocate(8).order(order)

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun u16(v: Int) = put(2) { scratch.putShort(v.toShort()) }
    fun u32(v: Int) = put(4) { scratch.putInt(v) }
    fun i64(v: Long) = put(8) { scratch.putLong(v) }
    fun f32(v: Float) = put(4) { scratch.putFloat(v) }
    fun f64(v: Double) = put(8) { scratch.putDouble(v) }

    /** `u16` code-unit count followed by UTF-16 units in this sink's byte order. */
    fun utf16(s: String) = apply {
        u16(s.length)
        s.forEach { u16(it.code) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private inline fun put(n: Int, block: () -> Unit) = apply {
        scratch.clear()
        block()
        out.write(scratch.array(), 0, n)
    }
}

/** Bounds-checked cursor over a byte array. */
class ByteSource(val data: ByteArray, order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {
    private val buf = ByteBuffer.wrap(data).order(order)

    var pos: Int
        get() = buf.position()
        set(v) {
            buf.position(v)
        }

    val remaining: Int get() = buf.remaining()

    fun u8(): Int = buf.get().toInt() and 0xFF
    fun u16(): Int = buf.getShort().toInt() and 0xFFFF
    fun i16(): Int = buf.getShort().toInt()
    fun i32(): Int = buf.getInt()
    fun u32(): Long = buf.getInt().toLong() and 0xFFFFFFFFL
    fun i64(): Long = buf.getLong()
    fun f32(): Float = buf.getFloat()
    fun f64(): Double = buf.getDouble()
    fun bytes(n: Int): ByteArray = ByteArray(n).also { buf.get(it) }
    fun skip(n: Int) {
        pos += n
    }

    fun utf16(): String {
        val n = u16()
        if (n == 0xFFFF) return ""
        val chars = CharArray(n) { u16().toChar() }
        return String(chars)
    }

    fun i32At(at: Int): Int = buf.getInt(at)
    fun u16At(at: Int): Int = buf.getShort(at).toInt() and 0xFFFF
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun hex(s: String): ByteArray {
    val clean = s.filterNot { it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
