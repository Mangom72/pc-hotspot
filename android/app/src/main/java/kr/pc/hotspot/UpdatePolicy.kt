package kr.pc.hotspot

import java.net.URI

/** Transport and version constraints used before downloading an installer. */
object UpdatePolicy {
    const val MAX_APK_BYTES = 20L * 1024 * 1024
    const val FEED_URL = "https://github.com/Mangom72/pc-hotspot/releases/latest/download/update.json"
    fun validate(version: Long, installed: Long, minSdk: Int, deviceSdk: Int, url: String, sha256: String, size: Long): Boolean {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1)
        require(uri.path.startsWith("/Mangom72/pc-hotspot/releases/download/"))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(size in 1..MAX_APK_BYTES)
        return version > installed && deviceSdk >= minSdk
    }
}
