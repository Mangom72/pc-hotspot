package kr.pc.hotspot

import java.util.concurrent.TimeUnit

/** Runs as shell through Shizuku. No arbitrary commands or raw output cross Binder. */
class WifiConnector : IWifiConnector.Stub() {
    override fun destroy() { System.exit(0) }
    private fun command(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("/system/bin/cmd", "wifi") + args).redirectErrorStream(true).start()
        // Drain the small cmd output concurrently so a blocked pipe cannot defeat the timeout.
        var output = ""
        val reader = Thread { output = process.inputStream.bufferedReader().use { it.readText().take(65536) } }
        reader.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly(); reader.join(1000); return -1 to ""
        }
        reader.join(1000)
        return process.exitValue() to output
    }
    override fun connect(ssid: String, password: String): Int {
        if (ssid.toByteArray(Charsets.UTF_8).size !in 1..32 || ssid.contains('\n') || ssid.contains('\r') ||
            password.length !in 8..63 || password.any { it.code !in 32..126 }) return 2
        return try { if (command("connect-network", ssid, "wpa2", password).first == 0) 0 else 1 }
        catch (_: Exception) { 1 }
    }
    override fun connected(ssid: String): Boolean = try {
        val (code, output) = command("status")
        code == 0 && output.lineSequence().any {
            it.trim() == "Wifi is connected to $ssid" || it.trim() == "Wifi is connected to \"$ssid\""
        }
    } catch (_: Exception) { false }
}
