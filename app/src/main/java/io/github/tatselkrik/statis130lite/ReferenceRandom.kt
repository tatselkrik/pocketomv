package io.github.tatselkrik.statis130lite

import kotlin.math.floor
import org.apache.commons.statistics.distribution.NormalDistribution

/** MT19937 with 32-bit LCG seed expansion and inverse-normal draws.
 * Seed expansion and the two-uniform inversion convention reproduce R's
 * Mersenne-Twister/Inversion stream for a signed integer seed.
 * Integer overflow is intentional arithmetic modulo 2^32.
 */
internal class ReferenceRandom(seed: Int) {
    private val state: IntArray
    private var index = 624
    private val normal = NormalDistribution.of(0.0, 1.0)
    init {
        require(seed != Int.MIN_VALUE) { "Use an integer seed from −2,147,483,647 to 2,147,483,647." }
        var expanded = seed
        repeat(51) { expanded = expanded * 69069 + 1 }
        state = IntArray(624) { expanded = expanded * 69069 + 1; expanded }
    }
    fun uniform(): Double {
        if (index == state.size) {
            for (i in state.indices) {
                val joined = (state[i] and Int.MIN_VALUE) or (state[(i + 1) % 624] and Int.MAX_VALUE)
                state[i] = state[(i + 397) % 624] xor (joined ushr 1) xor
                    if (joined and 1 == 0) 0 else 0x9908b0df.toInt()
            }
            index = 0
        }
        var word = state[index++]
        word = word xor (word ushr 11)
        word = word xor ((word shl 7) and 0x9d2c5680.toInt())
        word = word xor ((word shl 15) and 0xefc60000.toInt())
        word = word xor (word ushr 18)
        val value = (word.toLong() and 0xffffffffL) / 4294967296.0
        // Preserve the reference's rounded open-interval fallback constant.
        return if (value == 0.0) 0.5 * 2.328306437080797e-10 else value
    }
    fun normal(): Double {
        val high = floor(134217728.0 * uniform())
        return normal.inverseCumulativeProbability((high + uniform()) / 134217728.0)
    }
}
