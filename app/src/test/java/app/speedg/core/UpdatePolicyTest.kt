package app.speedg.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdatePolicy.isNewer("1.1.0", "v1.2.0"))
        assertTrue(UpdatePolicy.isNewer("1.9.0", "1.10.0"))
        assertTrue(UpdatePolicy.isNewer("1.1", "1.1.1"))
        assertFalse(UpdatePolicy.isNewer("1.2.0", "v1.2.0"))
        assertFalse(UpdatePolicy.isNewer("1.2", "1.2.0"))
        assertFalse(UpdatePolicy.isNewer("1.2.0", "1.1.9"))
    }

    @Test
    fun unparsableVersionIsNeverNewer() {
        assertFalse(UpdatePolicy.isNewer("1.1.0", "latest"))
        assertFalse(UpdatePolicy.isNewer("1.1.0", ""))
        assertFalse(UpdatePolicy.isNewer("1.1.0", "2.0-beta"))
    }

    @Test
    fun onlyThisProjectsReleasesAreTrusted() {
        assertTrue(UpdatePolicy.isTrustedUrl("https://github.com/alequab/SpeedG/releases/download/v1.2.0/SpeedG.apk"))
        assertFalse(UpdatePolicy.isTrustedUrl("https://evil.example/SpeedG.apk"))
        assertFalse(UpdatePolicy.isTrustedUrl("http://github.com/alequab/SpeedG/releases/download/v1/SpeedG.apk"))
        assertFalse(UpdatePolicy.isTrustedUrl("https://github.com/alequab/SpeedG/releases/../../other/x.apk"))
        assertFalse(UpdatePolicy.isTrustedUrl("https://github.com/someoneelse/SpeedG/releases/download/v1/x.apk"))
    }
}
