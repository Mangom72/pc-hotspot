package kr.pc.hotspot

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object Protocol {
    val SERVICE: UUID = UUID.fromString("af937001-6d7b-4b87-93ea-9a6b7dc44e10")
    val STATE: UUID = UUID.fromString("af937002-6d7b-4b87-93ea-9a6b7dc44e10")
    val COMMAND: UUID = UUID.fromString("af937003-6d7b-4b87-93ea-9a6b7dc44e10")
    val RESULT: UUID = UUID.fromString("af937004-6d7b-4b87-93ea-9a6b7dc44e10")
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val busy = AtomicBoolean(false)
    fun preferences(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)
    fun permitted(c: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

data class Outcome(val state: Int = 2, val message: String = "", val success: Boolean = false)
enum class Mode { QUERY, TOGGLE, REGISTER }

@Suppress("DEPRECATION", "MissingPermission")
class BleSession(private val context: Context, private val address: String,
                 private val mode: Mode, private val complete: (Outcome) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var ended = false
    private var ownsGate = false
    private var registeredReceiver = false
    private var sent = false
    private var recovering = false
    private var requested = -1
    private var reason = ""
    private var stage = ""
    private var commandResult: ByteArray? = null
    private val request = ByteArray(8).also { SecureRandom().nextBytes(it) }
    private val timeout = Runnable { fail("응답 시간 초과 ($stage) · PC 등록 모드와 블루투스를 확인하세요") }
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address != address) return
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDING -> deadline(stage, 65_000)
                // The encrypted read remains in flight. Android retries it after
                // bonding; issuing another read here would collide with that read.
                BluetoothDevice.BOND_BONDED -> deadline(stage, 20_000)
                BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING)
                    fail("페어링 취소됨 ($stage) · PC 등록 모드와 번호 승인을 확인하세요")
            }
        }
    }
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!Protocol.busy.compareAndSet(false, true)) { finish(Outcome(message = "다른 요청 처리 중")); return }
        ownsGate = true
        try {
            if (!Protocol.permitted(context)) { finish(Outcome(message = "앱에서 근처 기기 권한을 허용하세요")); return }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) { finish(Outcome(message = "폰 블루투스가 꺼져 있습니다")); return }
            val device = adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (mode != Mode.REGISTER) { finish(Outcome(message = "앱에서 PC를 먼저 등록하세요")); return }
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
                else context.registerReceiver(bondReceiver, filter)
                registeredReceiver = true
            }
            // Never call transport-AUTO createBond() before opening the LE link:
            // a dual-mode PC can otherwise be paired over Classic Bluetooth.
            // The authenticated read on this LE connection triggers Android's
            // GATT authentication/bonding flow on the correct transport.
            connect()
        } catch (e: Exception) { fail("블루투스 시작 실패: ${e.javaClass.simpleName}") }
    }
    private fun deadline(name: String, ms: Long = 12_000) {
        stage = name
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ms)
    }
    private fun connect() {
        if (ended) return
        gatt?.close()
        deadline("connect")
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        // autoConnect=false: no later execution after leaving Bluetooth range.
        gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail("연결을 시작할 수 없습니다")
    }
    private fun char(uuid: UUID) = gatt?.getService(Protocol.SERVICE)?.getCharacteristic(uuid)
    private fun readState() {
        deadline(if (sent && !recovering) "verify" else "read", if (mode == Mode.REGISTER) 65_000 else 12_000)
        val c = char(Protocol.STATE)
        if (c == null || gatt?.readCharacteristic(c) != true) fail("상태 조회 시작 실패")
    }
    private val callbacks = object : BluetoothGattCallback() {
        private fun dispatch(g: BluetoothGatt, block: () -> Unit) {
            handler.post { if (!ended && g === gatt) try { block() } catch (e: Exception) { fail("블루투스 오류: ${e.javaClass.simpleName}") } }
        }
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = dispatch(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                fail("BLE 연결 실패 (상태 $status) · PC 전원, 거리 또는 등록 상태를 확인하세요")
            else if (newState == BluetoothProfile.STATE_CONNECTED) {
                deadline("discover")
                if (!g.discoverServices()) fail("서비스 검색 실패")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = dispatch(g) {
            if (status != BluetoothGatt.GATT_SUCCESS || char(Protocol.STATE) == null) { fail("PC 핫스팟 서비스를 찾을 수 없습니다"); return@dispatch }
            if (mode == Mode.TOGGLE && !recovering) {
                val result = char(Protocol.RESULT)
                val ccc = result?.getDescriptor(Protocol.CCC)
                if (result == null || ccc == null || !g.setCharacteristicNotification(result, true)) { fail("결과 알림 설정 실패"); return@dispatch }
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                deadline("subscribe")
                if (!g.writeDescriptor(ccc)) fail("알림 구독 시작 실패")
            } else readState()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = dispatch(g) {
            if (status == BluetoothGatt.GATT_SUCCESS) readState() else fail("암호화 연결 실패 · PC에서 다시 등록하세요")
        }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val value = characteristic.value?.clone() ?: byteArrayOf()
            dispatch(g) { read(value, status) }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) = dispatch(g) { read(value, status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatch(g) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("명령 승인 실패 · PC 등록 또는 연결을 확인하세요"); return@dispatch }
            deadline("result", 8_000)
            commandResult?.let { result(it) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.clone() ?: byteArrayOf()
            dispatch(g) { notification(value) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = dispatch(g) { notification(value) }
    }
    private fun read(value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("암호화 상태 조회 실패 (GATT $status) · PC 등록 모드와 페어링을 확인하세요"); return }
        if (value.size != 3 || value[0].toInt() != 1 || value[1].toInt() !in 0..1 || value[2].toInt() != 0) { fail("PC에서 실제 핫스팟 상태를 확인하지 못했습니다"); return }
        val state = value[1].toInt()
        if (recovering) {
            finish(Outcome(state, "$reason · 재연결 상태: ${if (state == 1) "켜짐" else "꺼짐"}. 명령을 재전송하지 않았습니다."))
        } else if (stage == "verify") {
            finish(Outcome(state, if (state == requested) "" else "다른 요청으로 상태가 변경되었습니다", state == requested))
        } else if (mode == Mode.TOGGLE) {
            requested = 1 - state
            val c = char(Protocol.COMMAND)
            if (c == null) { fail("명령 서비스를 찾을 수 없습니다"); return }
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = byteArrayOf(1, requested.toByte()) + request
            deadline("write", 55_000)
            sent = true
            if (gatt?.writeCharacteristic(c) != true) { sent = false; fail("명령 전송 시작 실패") }
        } else {
            if (mode == Mode.REGISTER) Protocol.preferences(context).edit().putString("address", address).apply()
            finish(Outcome(state, if (mode == Mode.REGISTER) "PC 등록 완료" else "", true))
        }
    }
    private fun notification(value: ByteArray) {
        if (value.size != 11 || value[0].toInt() != 1 || !value.copyOfRange(3, 11).contentEquals(request)) return
        commandResult = value.clone()
        if (stage == "result") result(value)
    }
    private fun result(value: ByteArray) {
        when (value[2].toInt()) {
            0 -> readState()
            1 -> finish(Outcome(value[1].toInt(), "다른 요청 처리 중 · 다시 누르세요"))
            2 -> finish(Outcome(value[1].toInt(), "PC가 다른 Wi-Fi에 연결되어 있어 켤 수 없습니다"))
            else -> finish(Outcome(value[1].toInt(), "PC NetworkManager 명령 실패 · 실제 상태를 확인하세요"))
        }
    }
    private fun fail(message: String) {
        if (ended) return
        if (sent && !recovering) {
            // A write may have executed even when its acknowledgement was lost.
            // Reconnect once to read; never resend the command or toggle again.
            reason = message
            recovering = true
            handler.removeCallbacks(timeout)
            gatt?.close(); gatt = null
            handler.postDelayed({ if (!ended) try { connect() } catch (e: Exception) { finish(Outcome(message = "$reason · 재연결 실패")) } }, 250)
        } else finish(Outcome(message = if (recovering) "$reason · 실제 상태 확인 실패" else message))
    }
    fun cancel() { finish(Outcome(message = "취소됨"), false) }
    private fun finish(outcome: Outcome, deliver: Boolean = true) {
        if (ended) return
        ended = true
        handler.removeCallbacksAndMessages(null)
        if (registeredReceiver) context.unregisterReceiver(bondReceiver)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null
        if (ownsGate) Protocol.busy.set(false)
        if (deliver) complete(outcome)
    }
}
