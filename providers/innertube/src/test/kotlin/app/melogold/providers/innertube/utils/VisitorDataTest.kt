package app.melogold.providers.innertube.utils

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The country YouTube placed a request in, from real `visitorData` of player answers (2026-09-26). */
class VisitorDataTest {
    @Test
    fun `a request from the Netherlands`() {
        assertEquals(
            "NL",
            visitorCountry("CgtRTWpHWl9XellHZyjBhN7VBjIoCgJOTBIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgYw%3D%3D")
        )
    }

    @Test
    fun `a request from Germany, url-safe base64 with a long tail`() {
        assertEquals(
            "DE",
            visitorCountry(
                "Cgs4bmZBZU9NZ2hGVSiLhd7VBjIOCgJERRIIEgAgRlICCHE6AggBYuACCt0CMTguWVRFPUdHUjNxZUdSUDVfUTFNMXVSVVVVYVRs" +
                    "Q1BWb3VINWZaVmpWMlk5TkFJYWtwenZQdGlTS2NMMmRiV1pFbDFjNllRb0Nwd3pMRXhlVk5FYlVfSnhCb21TZnBZRE5pbEZjTzJP" +
                    "NVdxR211MnlwSTJSWEt4TVllX1BiTzJ0YmlGM0gxWEpJckV4REU1TnliV1RRY0NWQ19tamZfMkIzVWw4Szg5cnRRYm1KbXc0aEtH" +
                    "YmY0ZVA4c3pXWXhBM3hVaXNhOWd0eFlxYjNvUU1kMVZjQ01PWFFJTElTRG1ZdnpNU2IyOFpPWHNpRGY3RHZNeEFVX2kxYUZVOHk3" +
                    "WTV4LVd0akg2c1RXRkZ5cGR1emxiX0Q1Ykg5b0VHN1pfVWFiNUlzNW9iUWhmOUQ2X2IwNGJIaTRTVWRKc1laSTZBM0FEcGdDR256" +
                    "NnNkd1E2ZVRWYzNqQ0I0eFc5Zw%3D%3D"
            )
        )
    }

    @Test
    fun `nothing usable is null`() {
        assertNull(visitorCountry(null))
        assertNull(visitorCountry(""))
        assertNull(visitorCountry("not base64 at all!"))
        // Only the visitor id (field 1), no field 6
        assertNull(visitorCountry("CgtRTWpHWl9XellHZw"))
    }
}
