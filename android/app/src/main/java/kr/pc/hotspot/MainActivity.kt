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
import rikka.shizuku.Shizuku

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
        ui.button(container, title, action = action)
    }
    private lateinit var ui: OneUi
    private lateinit var autoStatus: TextView
    private lateinit var installUpdate: Button
    private lateinit var checkUpdate: Button
    private var autoSwitch: Switch? = null
    private val autoListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "status") handler.post { if (!isDestroyed && ::autoStatus.isInitialized) refreshAutoStatus() }
    }
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 7040) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                AutoConnect.prefs(this).edit().putBoolean("direct", true).apply()
                retryDirect()
            } else Toast.makeText(this, "Shizuku 권한이 필요합니다", Toast.LENGTH_LONG).show()
            refreshAutoStatus()
        }
    }
    private fun retryDirect() {
        if (AutoConnect.enabled(this)) startForegroundService(Intent(this, AutoConnectService::class.java).setAction("reconnect"))
    }
    private fun enableDirect() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            android.app.AlertDialog.Builder(this).setTitle("Shizuku를 시작하세요")
                .setMessage("Shizuku 앱에서 무선 디버깅으로 시작한 뒤 돌아와 주세요. 폰을 재부팅하면 다시 시작해야 합니다.")
                .setPositiveButton("Shizuku 열기") { _, _ ->
                    val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    startActivity(launch ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")))
                }.setNegativeButton("닫기", null).show()
        } else if (DirectWifi.ready()) {
            AutoConnect.prefs(this).edit().putBoolean("direct", true).apply(); retryDirect(); refreshAutoStatus()
        } else runCatching { Shizuku.requestPermission(7040) }.onFailure {
            Toast.makeText(this, "Shizuku에서 PC 핫스팟 권한을 허용하세요", Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(state: Bundle?) {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setTheme(if (night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        Shizuku.addRequestPermissionResultListener(shizukuPermission)
        AutoConnect.prefs(this).registerOnSharedPreferenceChangeListener(autoListener)
        ui = OneUi(this)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = if (ui.dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.background) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val centered = LinearLayout(this).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL }
        scroll.addView(centered)
        val available = resources.configuration.screenWidthDp
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28))
        }
        centered.addView(container, LinearLayout.LayoutParams(if (available > 720) ui.dp(720) else -1, -2))
        container.addView(ui.label("PC 핫스팟", 34f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(ui.dp(8), ui.dp(if (resources.configuration.screenHeightDp > 600) 64 else 20), 0, ui.dp(12))
            setAccessibilityHeading(true)
        })
        container.addView(ui.label("가까이 있는 PC와 간편하게 연결하세요", 15f, true).apply { setPadding(ui.dp(8), 0, 0, ui.dp(14)) })
        val connectionPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        container.addView(connectionPage); container.addView(settingsPage)
        val connection = ui.section(connectionPage, "내 PC")
        status = ui.label(savedLabel(), 20f)
        connection.addView(status)
        connection.addView(ui.label("폰과 PC의 블루투스를 켜 주세요", 14f, true))
        ui.button(connection, "핫스팟 켜기 / 끄기", true) {
            if (session == null) {
                val address = Protocol.preferences(this).getString("address", null)
                if (address == null) status.text = "아래에서 PC를 먼저 등록하세요" else runSession(address, Mode.TOGGLE)
            }
        }
        ui.button(connection, "상태 새로고침") {
            val address = Protocol.preferences(this).getString("address", null)
            if (address == null) status.text = "PC를 먼저 등록하세요"
            else if (session == null) runSession(address, Mode.QUERY)
        }
        val automatic = ui.section(connectionPage, "자동 연결")
        autoSwitch = ui.toggle(automatic, "핫스팟에 자동 연결", "PC 핫스팟이 켜지면 Wi-Fi 연결을 시도합니다", AutoConnect.enabled(this)) { enabled ->
            if (enabled) configureAutoConnect() else { AutoConnect.stop(this); refreshAutoStatus() }
        }
        autoStatus = ui.label("", 14f, true); automatic.addView(autoStatus)
        ui.button(automatic, "연결 정보 설정") { configureAutoConnect() }
        ui.button(automatic, "Shizuku 직접 전환 사용") { enableDirect() }
        ui.button(automatic, "Android 연결 제안 사용") {
            AutoConnect.prefs(this).edit().putBoolean("direct", false).apply(); retryDirect(); refreshAutoStatus()
        }
        ui.button(automatic, "자동 연결 다시 시도") { retryDirect() }
        ui.button(automatic, "Wi-Fi 설정") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        val setup = ui.section(connectionPage, "PC 등록")
        setup.addView(ui.label("처음에는 PC에서 등록 모드를 연 뒤 검색하세요. 페어링 번호가 같으면 양쪽에서 승인하세요.", 14f, true))
        ui.button(setup, "PC 검색") { scan() }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; setup.addView(devices)
        ui.button(setup, "빠른 설정에 타일 추가") { addTile() }
        val updates = ui.section(settingsPage, "앱 업데이트")
        updates.addView(ui.label("버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", 20f))
        ui.toggle(updates, "업데이트 자동 확인", "약 6시간마다 확인하고 새 버전을 준비합니다", UpdateManager.automatic(this)) {
            UpdateManager.setAutomatic(this, it)
        }
        updateStatus = ui.label("업데이트 확인 중…", 14f, true); updates.addView(updateStatus)
        checkUpdate = ui.button(updates, "지금 확인") { checkUpdates() }
        installUpdate = ui.button(updates, "업데이트 설치", true) { UpdateManager.install(this) }
        updates.addView(ui.label("새 버전은 알림으로 알려드립니다. 설치할 때만 확인이 필요합니다.", 13f, true).apply { setPadding(0, ui.dp(12), 0, 0) })
        val permissionsCard = ui.section(settingsPage, "권한 및 연결")
        ui.button(permissionsCard, "알림 허용") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            else Toast.makeText(this, "알림이 허용되어 있습니다", Toast.LENGTH_SHORT).show()
        }
        ui.button(permissionsCard, "근처 기기 권한 허용") {
            if (!permitted()) requestPermissions(permissions, 1) else status.text = "권한이 허용되어 있습니다"
        }
        ui.button(permissionsCard, "블루투스 설정") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        ui.button(permissionsCard, "PC 등록 해제") {
            if (session != null) return@button
            android.app.AlertDialog.Builder(this).setTitle("PC 등록을 해제할까요?")
                .setMessage("자동 연결도 중지됩니다. 다시 등록하려면 양쪽 블루투스 설정에서 페어링을 삭제하고 PC 등록 모드를 열어 주세요.")
                .setPositiveButton("해제") { _, _ ->
                    AutoConnect.stop(this); Protocol.preferences(this).edit().clear().apply()
                    status.text = savedLabel(); refreshAutoStatus()
                    TileService.requestListeningState(this, ComponentName(this, HotspotTileService::class.java))
                }.setNegativeButton("취소", null).show()
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)); setBackgroundColor(ui.surface) }
        root.addView(nav)
        val tabs = mutableListOf<Button>()
        fun tab(title: String, first: Boolean) {
            val wrapper = LinearLayout(this)
            nav.addView(wrapper, LinearLayout.LayoutParams(0, -2, 1f))
            val b = ui.button(wrapper, title) {
                connectionPage.visibility = if (first) View.VISIBLE else View.GONE
                settingsPage.visibility = if (first) View.GONE else View.VISIBLE
                scroll.scrollTo(0, 0)
                tabs.forEach { item ->
                    item.isSelected = item.text == title
                    item.setTextColor(if (item.isSelected) ui.accent else ui.secondary)
                    item.typeface = android.graphics.Typeface.create(if (item.isSelected) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
                }
            }
            tabs.add(b)
            b.isSelected = first
            b.setTextColor(if (first) ui.accent else ui.secondary)
        }
        tab("연결", true); tab("설정", false)
        UpdateManager.schedule(this)
        refreshAutoStatus(); checkUpdates()
        if (AutoConnect.enabled(this) && Protocol.permitted(this))
            runCatching { startForegroundService(Intent(this, AutoConnectService::class.java)) }
    }
    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, HotspotTileService::class.java), "PC 핫스팟",
            Icon.createWithResource(this, R.drawable.ic_hotspot), mainExecutor
        ) { status.text = "빠른 설정 편집에서도 타일을 추가할 수 있습니다" }
        else status.text = "빠른 설정 편집에서 ‘PC 핫스팟’을 추가하세요"
    }
    private fun refreshAutoStatus() {
        val p = AutoConnect.prefs(this)
        val mode = if (p.getBoolean("direct", false)) "직접 전환 · ${DirectWifi.status()}" else "Android 연결 제안 · 전환 여부는 Android가 결정합니다"
        autoStatus.text = mode + "\n" + if (AutoConnect.enabled(this)) p.getString("status", "PC 연결 대기") else "꺼짐 · 필요할 때 켜 주세요"
        autoSwitch?.setOnCheckedChangeListener(null)
        autoSwitch?.isChecked = AutoConnect.enabled(this)
        autoSwitch?.setOnCheckedChangeListener { _, enabled ->
            if (enabled) configureAutoConnect() else { AutoConnect.stop(this); refreshAutoStatus() }
        }
    }
    private fun configureAutoConnect() {
        if (Protocol.preferences(this).getString("address", null) == null) {
            status.text = "PC를 먼저 등록하세요"; refreshAutoStatus(); return
        }
        if (!permitted()) { requestPermissions(permissions, 1); refreshAutoStatus(); return }
        val p = AutoConnect.prefs(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0) }
        val ssid = EditText(this).apply { hint = "Wi-Fi 이름"; setText(p.getString("ssid", "archHotspot")); setSingleLine() }
        val password = EditText(this).apply {
            hint = if (p.contains("password")) "저장된 비밀번호 사용 · 변경할 때만 입력" else "핫스팟 비밀번호"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        form.addView(ssid); form.addView(password)
        form.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        form.addView(ui.label("다른 Wi-Fi에서 직접 전환하려면 Shizuku 직접 전환을 사용하세요.", 13f, true))
        val dialog = android.app.AlertDialog.Builder(this).setTitle("자동 연결 설정").setView(form)
            .setPositiveButton("저장 후 켜기", null).setNegativeButton("취소") { _, _ -> refreshAutoStatus() }.create()
        dialog.setOnCancelListener { refreshAutoStatus() }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val pass = password.text.toString().ifEmpty { p.getString("password", "") ?: "" }
                if (name.toByteArray(Charsets.UTF_8).size !in 1..32 || pass.length !in 8..63 || pass.any { it.code !in 32..126 }) {
                    password.error = "Wi-Fi 이름과 8~63자의 비밀번호를 확인하세요"; return@setOnClickListener
                }
                AutoConnect.stop(this)
                p.edit().putString("ssid", name).putString("password", pass).putBoolean("enabled", true).apply()
                if (!p.getBoolean("direct", false)) autoStatus.text = AutoConnect.register(this)
                try {
                    startForegroundService(Intent(this, AutoConnectService::class.java))
                    dialog.dismiss(); refreshAutoStatus()
                } catch (_: Exception) {
                    AutoConnect.stop(this); password.error = "자동 연결을 시작하지 못했습니다. 권한을 확인하세요"
                }
            }
        }
        dialog.show()
    }
    override fun onResume() { super.onResume(); if (::autoStatus.isInitialized) refreshAutoStatus() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7023 && packageManager.canRequestPackageInstalls()) UpdateManager.install(this)
    }
    private fun checkUpdates() {
        updateStatus.text = "업데이트 확인 중…"
        checkUpdate.isEnabled = false
        UpdateManager.check(this) { result ->
            if (isDestroyed) return@check
            checkUpdate.isEnabled = true
            updateStatus.text = result.message + "\n" + UpdateManager.lastCheckLabel(this)
            installUpdate.visibility = if (result.ready) View.VISIBLE else View.GONE
            if (result.ready && intent.getBooleanExtra("update", false)) android.app.AlertDialog.Builder(this)
                .setMessage("새 버전 다운로드와 서명 검증을 완료했습니다. 설치할까요?")
                .setPositiveButton("설치") { _, _ -> UpdateManager.install(this) }
                .setNegativeButton("나중에", null).show()
        }
    }
    private fun savedLabel() = if (Protocol.preferences(this).contains("address")) "등록된 PC" else "등록된 PC 없음"
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
    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        AutoConnect.prefs(this).unregisterOnSharedPreferenceChangeListener(autoListener)
        session?.cancel(); session = null; handler.removeCallbacksAndMessages(null); super.onDestroy()
    }
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
