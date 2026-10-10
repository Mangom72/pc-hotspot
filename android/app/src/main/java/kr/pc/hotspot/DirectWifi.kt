package kr.pc.hotspot

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class DirectWifi(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var connector: IWifiConnector? = null
    private var connection: ServiceConnection? = null
    @Volatile private var generation = 0
    private val args = Shizuku.UserServiceArgs(ComponentName(context, WifiConnector::class.java))
        .daemon(false).processNameSuffix("wifi").tag("hotspot-wifi").version(7)
    companion object {
        fun ready() = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        fun status(): String = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Shizuku를 먼저 시작하세요 · 폰 재부팅 후 다시 시작해야 합니다"
            !ready() -> "Shizuku 권한 허용이 필요합니다"
            else -> "Shizuku 준비됨 · 다른 Wi-Fi에서도 직접 전환합니다"
        }
    }
    fun cancel() { generation++; handler.removeCallbacksAndMessages(null) }
    fun connect(ssid: String, password: String, report: (String) -> Unit) {
        cancel()
        val token = generation
        if (!ready()) { report(status()); return }
        fun run(service: IWifiConnector) {
            report("핫스팟으로 전환 중…")
            worker.execute {
                if (token != generation) return@execute
                val started = runCatching { if (service.connected(ssid)) true else service.connect(ssid, password) == 0 }.getOrDefault(false)
                handler.post {
                    if (token != generation) return@post
                    if (!started) { report("직접 연결 요청 실패 · Wi-Fi 설정에서 연결하세요"); return@post }
                    fun poll(left: Int) {
                        if (token != generation) return
                        worker.execute {
                            if (token != generation) return@execute
                            val connected = runCatching { service.connected(ssid) }.getOrDefault(false)
                            handler.post {
                                if (token != generation) return@post
                                if (connected) report("핫스팟 연결 완료")
                                else if (left == 0) report("연결 확인 시간 초과 · Wi-Fi 설정을 확인하세요")
                                else handler.postDelayed({ poll(left - 1) }, 2000)
                            }
                        }
                    }
                    poll(15)
                }
            }
        }
        connector?.let { run(it); return }
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (token != generation) return
                val service = IWifiConnector.Stub.asInterface(binder) ?: return
                connector = service; handler.removeCallbacksAndMessages(null); run(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) { connector = null }
        }
        connection = binding
        try {
            Shizuku.bindUserService(args, binding)
            handler.postDelayed({ if (token == generation && connector == null) { generation++; report("Shizuku 연결 실패 · 앱에서 다시 시도하세요") } }, 15000)
        } catch (_: Exception) { report("Shizuku 연결 실패 · 권한과 실행 상태를 확인하세요") }
    }
    fun close() {
        cancel()
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        connection = null; connector = null; worker.shutdownNow()
    }
}
