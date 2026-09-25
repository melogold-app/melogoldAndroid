package app.melogold.domain.server

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** API §1.6 `UserCode`: what a person types comes to `XXXX-XXXX` or to nothing. */
class UserCodeTest {
    @Test
    fun `the forms a person types come to one code`() {
        listOf("K7QX-M2PD", "k7qx m2pd", "k7qxm2pd", "  K7QX_M2PD\n", "k7-qx-m2-pd", "K 7 Q X M 2 P D").forEach {
            assertEquals("K7QX-M2PD", UserCode.normalize(it), it)
        }
    }

    @Test
    fun `lookalike letters are read as digits`() {
        assertEquals("K70X-M1PD", UserCode.normalize("k7ox-mipd"))
        assertEquals("K70X-M1PD", UserCode.normalize("K7OX-MLPD"))
        assertEquals("0011-1100", UserCode.normalize("oOiI-lLOo"))
    }

    @Test
    fun `not a code`() {
        listOf(
            "",
            "   ",
            "K7QX-M2P", // 7 characters
            "K7QX-M2PDA", // 9
            "K7QX-M2PD-K7QX-M2PD-K7QX", // a recovery code
            "K7QU-M2PD", // U is not in Crockford's alphabet
            "K7QX+M2PD",
            "К7QX-M2PD" // a Cyrillic К
        ).forEach { assertNull(UserCode.normalize(it), it) }
    }
}
