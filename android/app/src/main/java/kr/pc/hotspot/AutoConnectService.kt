package kr.pc.hotspot

import android.app.*
import android.content.*
import android.net.wifi.*
import android.os.*
import android.provider.Settings

/** Credentials stay in app-private storage, excluded from Android backup. */
object AutoConnect {
    fun prefs(c: Context) = c.getSharedPreferences("auto_connect", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    private fun suggestion(c: Context): WifiNetworkSuggestion? {
        val p = prefs(c)
        val name = p.getString("ssid", null) ?: return null
        val password = p.getString("password", null) ?: return null
        return WifiNetworkSuggestion.Builder().setSsid(name).setWpa2Passphrase(password).build()
    }
    fun register(c: Context): String {
        val candidate = suggestion(c) ?: return "먼저 핫스팟 연결 정보를 저장하세요"
        return try {
            val wifi = c.getSystemService(WifiManager::class.java)
            val result = wifi.addNetworkSuggestions(listOf(candidate))
            when (result) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "연결 후보 등록됨 · 자동 연결되지 않으면 Wi-Fi 설정에서 선택하세요"
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> "Wi-Fi 제안 허용이 필요합니다 · 앱 설정에서 Wi-Fi 제어를 허용하세요"
                else -> "연결 후보 등록 실패 ($result) · Wi-Fi 설정에서 연결하세요"
            }
        } catch (_: SecurityException) { "Wi-Fi 제안 권한을 허용하세요" }
          catch (_: IllegalArgumentException) { "핫스팟 이름과 비밀번호를 확인하세요" }
    }
    fun remove(c: Context) {
        runCatching {
            val wifi = c.getSystemService(WifiManager::class.java)
            // Do not disconnect an existing connection when monitoring stops.
            if (Build.VERSION.SDK_INT >= 33)
                wifi.removeNetworkSuggestions(emptyList(), WifiManager.ACTION_REMOVE_SUGGESTION_LINGER)
            else wifi.removeNetworkSuggestions(emptyList())
        }
    }
    fun stop(c: Context) {
        prefs(c).edit().putBoolean("enabled", false).apply()
        c.stopService(Intent(c, AutoConnectService::class.java))
        remove(c)
    }
}

class AutoConnectService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: BleSession? = null
    private var retryDelay = 10_000L
    private var previous = -1
    private var message = "등록된 PC를 찾고 있습니다"
    private val retry = Runnable { connect() }
    private val notificationManager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(NotificationChannel("auto_connect", "핫스팟 자동 연결", NotificationManager.IMPORTANCE_LOW))
        startForeground(7031, notification())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !AutoConnect.enabled(this)) {
            AutoConnect.stop(this); stopSelf(); return START_NOT_STICKY
        }
        if (session == null) { handler.removeCallbacks(retry); connect() }
        return START_STICKY
    }
    private fun connect() {
        if (!AutoConnect.enabled(this)) { stopSelf(); return }
        val address = Protocol.preferences(this).getString("address", null)
        if (address == null) { update("앱에서 PC를 먼저 등록하세요"); stopSelf(); return }
        session = BleSession(this, address, Mode.WATCH) { outcome ->
            if (outcome.success) {
                retryDelay = 10_000
                if (previous != outcome.state) {
                    previous = outcome.state
                    if (outcome.state == 1) update("핫스팟 켜짐 · ${AutoConnect.register(this)}")
                    else { AutoConnect.remove(this); update("핫스팟이 켜지면 연결을 시도합니다") }
                }
            } else {
                session?.cancel(); session = null
                previous = -1
                AutoConnect.remove(this)
                update("PC 연결 대기 · ${outcome.message}")
                handler.removeCallbacks(retry)
                handler.postDelayed(retry, retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(60_000)
            }
        }
        session?.start()
    }
    private fun update(text: String) {
        message = text
        AutoConnect.prefs(this).edit().putString("status", text).apply()
        notificationManager.notify(7031, notification())
    }
    private fun notification(): Notification {
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 7031, Intent(this, MainActivity::class.java), immutable)
        val wifi = PendingIntent.getActivity(this, 7032, Intent(Settings.ACTION_WIFI_SETTINGS), immutable)
        val stop = PendingIntent.getService(this, 7033, Intent(this, AutoConnectService::class.java).setAction("stop"), immutable)
        return Notification.Builder(this, "auto_connect").setSmallIcon(R.drawable.ic_hotspot)
            .setContentTitle("PC 핫스팟 자동 연결").setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Wi-Fi 설정", wifi).build())
            .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.cancel(); session = null
        AutoConnect.remove(this)
        notificationManager.cancel(7031)
        super.onDestroy()
    }
}
