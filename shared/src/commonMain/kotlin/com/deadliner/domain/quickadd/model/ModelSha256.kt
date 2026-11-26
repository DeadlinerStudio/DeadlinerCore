package com.deadliner.domain.quickadd.model

/** Small streaming SHA-256 implementation shared with OHOS; no extra native crypto runtime. */
internal class ModelSha256 {
    private val state = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
    private val block = ByteArray(64)
    private val words = IntArray(64)
    private var used = 0
    private var bytes = 0L
    private var finished = false

    fun update(data: ByteArray, count: Int = data.size) {
        check(!finished)
        require(count in 0..data.size)
        bytes += count
        var position = 0
        while (position < count) {
            val take = minOf(64 - used, count - position)
            data.copyInto(block, used, position, position + take)
            used += take
            position += take
            if (used == 64) { compress(); used = 0 }
        }
    }

    fun hex(): String {
        check(!finished)
        val bits = bytes * 8
        update(byteArrayOf(0x80.toByte()))
        while (used != 56) update(byteArrayOf(0))
        update(ByteArray(8) { (bits ushr (56 - it * 8)).toByte() })
        finished = true
        return state.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun compress() {
        val w = words
        for (i in 0..15) {
            val p = i * 4
            w[i] = ((block[p].toInt() and 255) shl 24) or ((block[p + 1].toInt() and 255) shl 16) or
                ((block[p + 2].toInt() and 255) shl 8) or (block[p + 3].toInt() and 255)
        }
        for (i in 16..63) {
            val x = w[i - 15]; val y = w[i - 2]
            val s0 = rotate(x, 7) xor rotate(x, 18) xor (x ushr 3)
            val s1 = rotate(y, 17) xor rotate(y, 19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in 0..63) {
            val s1 = rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)
            val t1 = h + s1 + ((e and f) xor (e.inv() and g)) + constants[i] + w[i]
            val s0 = rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)
            val t2 = s0 + ((a and b) xor (a and c) xor (b and c))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private fun rotate(value: Int, count: Int) = (value ushr count) or (value shl (32 - count))
    companion object {
        private val constants = longArrayOf(
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
        ).map { it.toInt() }.toIntArray()
    }
}
