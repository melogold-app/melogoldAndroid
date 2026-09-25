package app.melogold.domain.server

/**
 * What a device of the account is, by its `platform` (API §1.6): one table for the whole app — the devices of the
 * account, the device filter of History, the approval of a sign-in by code (tasks/0004). The same as `DeviceSymbols`
 * of Windows and `DeviceSymbol` of the Apple client.
 */
enum class DeviceKind {
    Phone,
    Tablet,
    Computer,
    Watch,
    Headset,

    /** A platform this app doesn't know: drawn as a computer, called "Device". */
    Other;

    companion object {
        /** By the exact value of `platform` in lower case; an unknown one never breaks the list. */
        fun of(platform: String?): DeviceKind = when (platform?.trim()?.lowercase()) {
            "android", "ios" -> Phone
            "ipados" -> Tablet
            "macos", "windows", "linux" -> Computer
            "watchos" -> Watch
            "visionos" -> Headset
            else -> Other
        }
    }
}
