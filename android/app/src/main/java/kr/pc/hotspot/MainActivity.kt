package kr.pc.hotspot

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.*
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.View
import android.widget.*

@Suppress("DEPRECATION", "MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var updateStatus: TextView
    private lateinit var devices: LinearLayout
    private var scanner: BluetoothLeScanner? = null
    private var session: BleSession? = null
    private val seen = mutableSetOf<String>()
    private val stopScan = Runnable { stopScanning(); status.text = "검색 완료. PC가 보이지 않으면 등록 모드와 블루투스를 확인하세요." }
    private val permissions: Array<String> get() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun permitted() = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun button(container: LinearLayout, title: String, action: () -> Unit) {
        container.addView(Button(this).apply { text = title; setOnClickListener { action() } })
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        val scroll = ScrollView(this).apply { addView(container); fitsSystemWindows = true }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        container.addView(TextView(this).apply { text = "PC 핫스팟"; textSize = 28f })
        container.addView(TextView(this).apply {
            text = "한 번 등록한 뒤 빠른 설정에서 켜고 끄세요.\n\n1. PC 터미널에서 pc-hotspot enroll 실행\n2. 아래에서 PC 검색 후 선택\n3. PC와 폰의 번호가 같으면 양쪽에서 승인\n4. 빠른 설정에 ‘PC 핫스팟’ 추가\n\n폰 잠금을 해제하고 사용하세요. 폰과 PC의 블루투스가 켜져 있어야 합니다."
            textSize = 16f
        })
        status = TextView(this).apply { textSize = 16f; text = savedLabel() }
        container.addView(status)
        updateStatus = TextView(this).apply { text = "앱 ${packageManager.getPackageInfo(packageName, 0).versionName} · 업데이트 확인 중…" }
        container.addView(updateStatus)
        button(container, "업데이트 확인") { checkUpdates() }
        button(container, "다운로드한 업데이트 설치") { UpdateManager.install(this) }
        button(container, "업데이트 알림 허용") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            else updateStatus.text = "업데이트 알림이 허용되어 있습니다"
        }
        UpdateManager.schedule(this)
        checkUpdates()
        button(container, "근처 기기 권한 허용") { if (!permitted()) requestPermissions(permissions, 1) else status.text = "권한이 허용되어 있습니다" }
        button(container, "블루투스 설정") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button(container, "PC 검색 (10초)") { scan() }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(devices)
        button(container, "등록된 PC 상태 확인") {
            if (session != null) return@button
            val address = Protocol.preferences(this).getString("address", null)
            if (address == null) status.text = "PC를 먼저 등록하세요"
            else runSession(address, Mode.QUERY)
        }
        button(container, "빠른 설정 타일 추가") {
            if (Build.VERSION.SDK_INT >= 33) {
                getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(this, HotspotTileService::class.java), "PC 핫스팟",
                    Icon.createWithResource(this, R.drawable.ic_hotspot), mainExecutor
                ) { status.text = "타일이 보이지 않으면 빠른 설정 편집에서 추가하세요" }
            } else status.text = "빠른 설정을 두 번 내리고 편집에서 ‘PC 핫스팟’을 추가하세요"
        }
        button(container, "이 폰의 PC 등록 지우기") {
            if (session != null) return@button
            android.app.AlertDialog.Builder(this).setMessage("앱의 등록 정보를 지웁니다. PC에서 pc-hotspot revoke를 실행하고, 다시 등록하려면 양쪽 블루투스 설정에서 페어링도 삭제하세요.")
                .setPositiveButton("지우기") { _, _ ->
                    Protocol.preferences(this).edit().clear().apply()
                    status.text = savedLabel()
                    TileService.requestListeningState(this, ComponentName(this, HotspotTileService::class.java))
                }.setNegativeButton("취소", null).show()
        }
    }
    private fun checkUpdates() {
        updateStatus.text = "업데이트 확인 중…"
        UpdateManager.check(this) { result ->
            if (isDestroyed) return@check
            updateStatus.text = result.message
            if (result.ready) android.app.AlertDialog.Builder(this)
                .setMessage("새 버전 다운로드와 서명 검증을 완료했습니다. 설치할까요?")
                .setPositiveButton("설치") { _, _ -> UpdateManager.install(this) }
                .setNegativeButton("나중에", null).show()
        }
    }
    private fun savedLabel() = Protocol.preferences(this).getString("address", null)?.let { "등록된 PC: $it" } ?: "등록된 PC 없음"
    private fun scan() {
        if (session != null) return
        if (!permitted()) { requestPermissions(permissions, 1); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { status.text = "블루투스를 켜세요"; return }
        if (Build.VERSION.SDK_INT <= 30 && !getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
            status.text = "Android 10·11에서는 검색할 때 위치 설정도 켜야 합니다"; return
        }
        stopScanning()
        seen.clear(); devices.removeAllViews()
        scanner = adapter.bluetoothLeScanner
        status.text = "PC 검색 중…"
        try {
            scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            handler.postDelayed(stopScan, 10_000)
        } catch (e: Exception) { stopScanning(); status.text = "검색 실패: ${e.javaClass.simpleName}" }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            if (scanner == null) return@post
            val address = result.device.address
            if (seen.add(address)) button(devices, "${result.scanRecord?.deviceName ?: "PC Hotspot"}\n$address") {
                if (session != null) return@button
                stopScanning()
                runSession(address, Mode.REGISTER)
            }
        } }
        override fun onScanFailed(error: Int) { handler.post { stopScanning(); status.text = "검색 실패 ($error) · 잠시 후 다시 검색하세요" } }
    }
    private fun runSession(address: String, mode: Mode) {
        status.text = if (mode == Mode.REGISTER) "페어링 번호를 양쪽에서 확인하세요. 등록 확인 중…" else "PC 상태 확인 중…"
        session = BleSession(this, address, mode) { outcome ->
            session = null
            val state = when (outcome.state) { 1 -> "켜짐"; 0 -> "꺼짐"; else -> "연결 안 됨" }
            status.text = "$state\n${outcome.message}"
            if (outcome.success) TileService.requestListeningState(this, ComponentName(this, HotspotTileService::class.java))
        }
        session?.start()
    }
    private fun stopScanning() {
        handler.removeCallbacks(stopScan)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        scanner = null
    }
    override fun onStop() { stopScanning(); super.onStop() }
    override fun onDestroy() { session?.cancel(); session = null; handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 2) {
            updateStatus.text = if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                "업데이트 알림 허용됨" else "알림 없이도 앱을 열면 업데이트를 확인할 수 있습니다"
            return
        }
        status.text = if (permitted()) "권한 허용됨 · PC 검색을 누르세요" else "근처 기기 권한이 필요합니다"
    }
}
