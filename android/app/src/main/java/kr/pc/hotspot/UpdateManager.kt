package kr.pc.hotspot

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
object UpdateManager {
    private const val JOB_ID = 7021
    private const val NOTIFICATION_ID = 7022
    private const val CHANNEL = "updates"
    private val executor = Executors.newSingleThreadExecutor()
    private val checking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    data class Result(val message: String, val ready: Boolean = false)
    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun apk(c: Context) = File(c.filesDir, "updates/update.apk")
    fun schedule(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
        val scheduler = c.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(6 * 60 * 60 * 1000L).build())
    }
    fun check(context: Context, callback: (Result) -> Unit) {
        val c = context.applicationContext
        if (!checking.compareAndSet(false, true)) { callback(Result("업데이트 확인 중")); return }
        executor.execute {
            val result = try { downloadIfNew(c) } catch (e: Exception) {
                Result("업데이트 확인 실패 · ${e.message ?: e.javaClass.simpleName}", ready(c))
            } finally { checking.set(false) }
            main.post { callback(result) }
        }
    }
    private fun connect(raw: String, redirects: Int = 0): HttpURLConnection {
        require(redirects < 6) { "다운로드 리디렉션 오류" }
        val url = URL(raw)
        // GitHub release downloads redirect to its signed, time-limited CDN URL.
        require(url.protocol == "https" && url.userInfo == null && url.port == -1)
        require(url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PC-Hotspot-Android")
        }
        val status = connection.responseCode
        if (status in listOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location") ?: error("다운로드 주소 없음")
            connection.disconnect()
            return connect(URL(url, location).toString(), redirects + 1)
        }
        if (status != 200) { connection.disconnect(); error("HTTP $status") }
        return connection
    }
    private fun downloadIfNew(c: Context): Result {
        val feed = connect(UpdatePolicy.FEED_URL)
        val manifest = try {
            val bytes = feed.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    require(output.size() + n <= 16_384) { "버전 정보 크기 오류" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        } finally { feed.disconnect() }
        val version = manifest.getLong("versionCode")
        val installed = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
        val expectedHash = manifest.getString("sha256")
        val expectedSize = manifest.getLong("size")
        val downloadUrl = manifest.getString("url")
        if (!UpdatePolicy.validate(version, installed, manifest.getInt("minSdk"), Build.VERSION.SDK_INT,
                downloadUrl, expectedHash, expectedSize)) {
            if (prefs(c).getLong("version", 0) <= installed) {
                apk(c).delete(); prefs(c).edit().clear().apply()
                c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
            return Result("현재 버전이 최신입니다")
        }
        if (ready(c) && prefs(c).getLong("version", 0) == version && prefs(c).getString("sha256", null) == expectedHash) {
            notify(c); return Result("새 버전 ${manifest.getString("versionName")} 설치 준비됨", true)
        }
        val target = apk(c)
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, "download.tmp.apk")
        try {
            val connection = connect(downloadUrl)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                connection.inputStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= expectedSize && total <= UpdatePolicy.MAX_APK_BYTES) { "APK 크기 오류" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
            } finally { connection.disconnect() }
            require(total == expectedSize && hex(digest.digest()) == expectedHash) { "APK 무결성 검증 실패" }
            require(verify(c, temporary, version)) { "앱 이름·버전·서명 검증 실패" }
            require(temporary.renameTo(target)) { "APK 저장 실패" }
            prefs(c).edit().putLong("version", version).putString("sha256", expectedHash)
                .putString("versionName", manifest.getString("versionName")).apply()
            notify(c)
            return Result("새 버전 ${manifest.getString("versionName")} 다운로드 완료", true)
        } finally { temporary.delete() }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun verify(c: Context, file: File, version: Long): Boolean {
        val current = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = c.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != c.packageName || archive.longVersionCode != version || version <= current.longVersionCode) return false
        val live = current.signingInfo?.apkContentsSigners?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet()
        val incoming = archive.signingInfo?.apkContentsSigners?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet()
        return live != null && live.isNotEmpty() && live == incoming
    }
    fun ready(c: Context): Boolean = runCatching {
        val version = prefs(c).getLong("version", 0)
        val file = apk(c)
        if (!file.isFile || !verify(c, file, version)) return@runCatching false
        val expected = prefs(c).getString("sha256", null) ?: return@runCatching false
        val actual = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
            hex(digest.digest())
        }
        actual == expected
    }.getOrDefault(false)
    private fun notify(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(c, MainActivity::class.java).putExtra("update", true)
        val pending = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_hotspot).setContentTitle("PC 핫스팟 업데이트")
                .setContentText("새 버전 다운로드 완료 · 눌러 설치하세요").setContentIntent(pending).setAutoCancel(true).build())
    }
    fun install(activity: Activity) {
        if (!ready(activity)) { ToastMessage.show(activity, "설치할 새 버전이 없습니다"); return }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            ToastMessage.show(activity, "PC 핫스팟의 설치 허용 후 ‘업데이트 설치’를 다시 누르세요")
            return
        }
        val uri = Uri.parse("content://${activity.packageName}.updates/update.apk")
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private object ToastMessage {
    fun show(c: Context, text: String) = android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show()
}
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        UpdateManager.check(this) { jobFinished(params, false) }
        return true
    }
    // Downloading runs on a bounded executor; it never starts an installer itself.
    override fun onStopJob(params: JobParameters): Boolean = true
}
