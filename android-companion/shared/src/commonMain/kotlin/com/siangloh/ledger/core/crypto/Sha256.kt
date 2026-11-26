package com.siangloh.ledger.core.crypto

private const val HEX_CHARS = "0123456789abcdef"

private val SHA256_K = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
)

private inline fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

/**
 * Pure Kotlin RFC 6234 SHA-256 implementation returning the 32-byte digest.
 */
fun sha256Bytes(bytes: ByteArray): ByteArray {
    val len = bytes.size
    val bitLen = len.toLong() * 8L
    val rem = (len + 1) % 64
    val zeroPad = if (rem <= 56) (56 - rem) else (120 - rem)
    val totalLen = len + 1 + zeroPad + 8

    val padded = ByteArray(totalLen)
    bytes.copyInto(padded, destinationOffset = 0, startIndex = 0, endIndex = len)
    padded[len] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[totalLen - 8 + i] = ((bitLen ushr ((7 - i) * 8)) and 0xffL).toByte()
    }

    var h0 = 0x6a09e667
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19

    val w = IntArray(64)
    var offset = 0
    while (offset < totalLen) {
        for (t in 0 until 16) {
            val j = offset + t * 4
            w[t] = ((padded[j].toInt() and 0xff) shl 24) or
                ((padded[j + 1].toInt() and 0xff) shl 16) or
                ((padded[j + 2].toInt() and 0xff) shl 8) or
                (padded[j + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val v15 = w[t - 15]
            val s0 = rotr(v15, 7) xor rotr(v15, 18) xor (v15 ushr 3)
            val v2 = w[t - 2]
            val s1 = rotr(v2, 17) xor rotr(v2, 19) xor (v2 ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7

        for (t in 0 until 64) {
            val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + bigS1 + ch + SHA256_K[t] + w[t]
            val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = bigS0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h

        offset += 64
    }

    val digest = ByteArray(32)
    val words = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
    for (i in 0 until 8) {
        val word = words[i]
        val base = i * 4
        digest[base] = ((word ushr 24) and 0xff).toByte()
        digest[base + 1] = ((word ushr 16) and 0xff).toByte()
        digest[base + 2] = ((word ushr 8) and 0xff).toByte()
        digest[base + 3] = (word and 0xff).toByte()
    }
    return digest
}

/**
 * Pure Kotlin RFC 6234 SHA-256 returning a 64-character lowercase hex digest for UTF-8 [input].
 */
fun sha256Hex(input: String): String = sha256Hex(input.encodeToByteArray())

/**
 * Pure Kotlin RFC 6234 SHA-256 returning a 64-character lowercase hex digest for [bytes].
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = sha256Bytes(bytes)
    val chars = CharArray(64)
    for (i in digest.indices) {
        val v = digest[i].toInt() and 0xff
        chars[i * 2] = HEX_CHARS[v ushr 4]
        chars[i * 2 + 1] = HEX_CHARS[v and 0x0f]
    }
    return chars.concatToString()
}

object Sha256 {
    fun sha256Bytes(bytes: ByteArray): ByteArray = com.siangloh.ledger.core.crypto.sha256Bytes(bytes)
    fun sha256Hex(input: String): String = com.siangloh.ledger.core.crypto.sha256Hex(input)
    fun sha256Hex(bytes: ByteArray): String = com.siangloh.ledger.core.crypto.sha256Hex(bytes)
}
