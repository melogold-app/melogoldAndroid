package app.melogold.domain.server

/**
 * The sign-in code a new device shows, like the watch (API §1.6 `UserCode`, §4.6 mode `request`): Crockford's
 * alphabet; the input goes to upper case, loses whitespace, `-` and `_`, reads `O` as `0` and `I`, `L` as `1`; exactly
 * 8 characters; shown as `XXXX-XXXX`. The same rule as the server's `normalizeCrockfordCode`.
 */
object UserCode {
    const val LENGTH = 8
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP = 4

    /** `k7qx m2pd` → `K7QX-M2PD`; null when [input] is not a code. */
    fun normalize(input: String): String? {
        val code = buildString {
            for (char in input.uppercase()) {
                when {
                    char.isWhitespace() || char == '-' || char == '_' -> Unit
                    char == 'O' -> append('0')
                    char == 'I' || char == 'L' -> append('1')
                    else -> append(char)
                }
            }
        }
        if (code.length != LENGTH || code.any { it !in ALPHABET }) return null
        return code.substring(0, GROUP) + "-" + code.substring(GROUP)
    }
}
