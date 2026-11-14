package app.avoska.engine

/**
 * Syncthing device IDs: 52 base32 characters plus a Luhn mod 32 check character after each
 * 13, written as 8 groups of 7 separated by dashes.
 */
object DeviceIds {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val CHUNK = 13

    /** Returns the ID in canonical form, or null if [input] is not a valid device ID. */
    fun normalize(input: String): String? {
        val raw = input.uppercase().filter { it != '-' && it != ' ' && !it.isWhitespace() }
        if (raw.length != 4 * (CHUNK + 1) || raw.any { it !in ALPHABET }) return null
        for (i in 0 until 4) {
            val start = i * (CHUNK + 1)
            if (luhnBase32(raw.substring(start, start + CHUNK)) != raw[start + CHUNK]) return null
        }
        return raw.chunked(7).joinToString("-")
    }

    fun isValid(input: String): Boolean = normalize(input) != null

    private fun luhnBase32(s: String): Char {
        var factor = 1
        var sum = 0
        for (c in s) {
            val addend = factor * ALPHABET.indexOf(c)
            factor = if (factor == 2) 1 else 2
            sum += addend / ALPHABET.length + addend % ALPHABET.length
        }
        return ALPHABET[(ALPHABET.length - sum % ALPHABET.length) % ALPHABET.length]
    }
}
