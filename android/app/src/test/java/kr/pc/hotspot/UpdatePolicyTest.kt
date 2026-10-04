package kr.pc.hotspot

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val url = "https://github.com/Mangom72/pc-hotspot/releases/download/v1.0.3/pc-hotspot-1.0.3.apk"
    private val sha = "a".repeat(64)
    private fun allowed(version: Long = 4, installed: Long = 3, min: Int = 29, sdk: Int = 36,
                        location: String = url, hash: String = sha, size: Long = 2_000_000) =
        UpdatePolicy.validate(version, installed, min, sdk, location, hash, size)
    @Test fun newerCompatibleReleaseIsEligible() { assertTrue(allowed()) }
    @Test fun downgradeAndSameVersionAreIgnored() { assertFalse(allowed(version = 2)); assertFalse(allowed(version = 3)) }
    @Test fun unsupportedAndroidIsIgnored() { assertFalse(allowed(min = 37)) }
    @Test fun maliciousAndUnrelatedUrlsAreRejected() {
        for (value in listOf(url.replace("https:", "http:"), url.replace("github.com", "example.com"),
            url.replace("Mangom72/pc-hotspot", "evil/app"), url.replace("github.com", "token@github.com")))
            try { allowed(location = value); fail("Accepted $value") } catch (_: IllegalArgumentException) { }
    }
    @Test fun badHashAndOversizedPayloadAreRejected() {
        try { allowed(hash = "nope"); fail() } catch (_: IllegalArgumentException) { }
        try { allowed(size = UpdatePolicy.MAX_APK_BYTES + 1); fail() } catch (_: IllegalArgumentException) { }
    }
}
