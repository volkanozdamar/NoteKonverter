package com.example.notekonverter.converter.io

import java.nio.ByteOrder

/** BSON binary (type 0x05) payload, kept distinct from strings and arrays. */
class BsonBinary(val bytes: ByteArray, val subtype: Int = 0)

/**
 * Minimal BSON codec for Saber's `.sbn2` notes. Documents decode to
 * `Map<String, Any?>` and arrays to `List<Any?>`; int32 decodes to [Int],
 * int64 to [Long], double to [Double], binary to [BsonBinary].
 */
object Bson {

    fun decode(data: ByteArray): Map<String, Any?> = document(ByteSource(data), array = false).obj()!!

    fun encode(doc: Map<String, Any?>): ByteArray = ByteSink().apply { document(doc.entries.map { it.key to it.value }) }.toByteArray()

    private fun document(s: ByteSource, array: Boolean): Any {
        val start = s.pos
        val size = s.i32()
        val end = start + size - 1
        if (size < 5 || end >= s.data.size) throw IllegalArgumentException("bad BSON document size")
        val map = LinkedHashMap<String, Any?>()
        while (s.pos < end) {
            val type = s.u8()
            val key = cstring(s)
            map[key] = when (type) {
                0x01 -> s.f64()
                0x02 -> {
                    val n = s.i32()
                    String(s.bytes(n - 1), Charsets.UTF_8).also { s.skip(1) }
                }
                0x03 -> document(s, array = false)
                0x04 -> document(s, array = true)
                0x05 -> {
                    val n = s.i32()
                    val sub = s.u8()
                    BsonBinary(s.bytes(n), sub)
                }
                0x06, 0x0A -> null // undefined, null
                0x07 -> s.bytes(12) // ObjectId
                0x08 -> s.u8() != 0
                0x09 -> s.i64() // UTC datetime
                0x10 -> s.i32()
                0x11, 0x12 -> s.i64() // timestamp, int64
                0x13 -> s.bytes(16) // decimal128
                else -> throw IllegalArgumentException("unsupported BSON type $type")
            }
        }
        s.skip(1) // terminating 0x00
        return if (array) map.values.toList() else map
    }

    private fun cstring(s: ByteSource): String {
        val start = s.pos
        while (s.data[s.pos].toInt() != 0) s.pos++
        return String(s.data, start, s.pos++ - start, Charsets.UTF_8)
    }

    private fun ByteSink.document(entries: List<Pair<String, Any?>>) {
        val body = ByteSink(ByteOrder.LITTLE_ENDIAN)
        for ((key, value) in entries) body.element(key, value)
        u32(4 + body.size + 1).bytes(body.toByteArray()).u8(0)
    }

    private fun ByteSink.element(key: String, value: Any?) {
        fun head(type: Int) = u8(type).bytes(key.toByteArray(Charsets.UTF_8)).u8(0)
        when (value) {
            null -> head(0x0A)
            is Boolean -> head(0x08).u8(if (value) 1 else 0)
            is Int -> head(0x10).u32(value)
            is Long -> head(0x12).i64(value)
            is Float -> head(0x01).f64(value.toDouble())
            is Double -> head(0x01).f64(value)
            is String -> {
                val b = value.toByteArray(Charsets.UTF_8)
                head(0x02).u32(b.size + 1).bytes(b).u8(0)
            }
            is BsonBinary -> head(0x05).u32(value.bytes.size).u8(value.subtype).bytes(value.bytes)
            is Map<*, *> -> head(0x03).document(value.entries.map { it.key.toString() to it.value })
            is List<*> -> head(0x04).document(value.mapIndexed { i, v -> i.toString() to v })
            else -> throw IllegalArgumentException("cannot encode ${value::class.simpleName} as BSON")
        }
    }
}
