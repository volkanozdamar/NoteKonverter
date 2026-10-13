package com.example.notekonverter.converter.io

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One decoded protobuf field. `bytes` holds wire types 1, 2 and 5; `varint` holds wire type 0. */
class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray) {
    val string: String get() = bytes.toString(Charsets.UTF_8)
    val float: Float get() = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat()
}

/** Schema-less protobuf decoder: enough for the Boox metadata records. */
fun parseProto(data: ByteArray): List<ProtoField> {
    val fields = ArrayList<ProtoField>()
    var i = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (i >= data.size) throw IllegalArgumentException("truncated varint")
            val b = data[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    while (i < data.size) {
        val key = varint()
        val number = (key ushr 3).toInt()
        val wire = (key and 7).toInt()
        when (wire) {
            0 -> fields += ProtoField(number, wire, varint(), EMPTY)
            1, 5 -> {
                val n = if (wire == 1) 8 else 4
                fields += ProtoField(number, wire, 0, data.copyOfRange(i, i + n))
                i += n
            }
            2 -> {
                val n = varint().toInt()
                if (n < 0 || i + n > data.size) throw IllegalArgumentException("bad length")
                fields += ProtoField(number, wire, 0, data.copyOfRange(i, i + n))
                i += n
            }
            else -> throw IllegalArgumentException("unsupported wire type $wire")
        }
    }
    return fields
}

private val EMPTY = ByteArray(0)

fun List<ProtoField>.first(number: Int): ProtoField? = firstOrNull { it.number == number }

/** Minimal protobuf encoder. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun key(number: Int, wire: Int) = rawVarint(((number shl 3) or wire).toLong())

    /** Signed ints are sign-extended to 64 bits, as protobuf int32/int64 do. */
    fun varint(number: Int, value: Long) = apply { key(number, 0); rawVarint(value) }

    fun float(number: Int, value: Float) = apply {
        key(number, 5)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
    }

    fun bytes(number: Int, value: ByteArray) = apply {
        key(number, 2)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun string(number: Int, value: String) = bytes(number, value.toByteArray(Charsets.UTF_8))

    fun message(number: Int, build: ProtoWriter.() -> Unit) = bytes(number, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}
