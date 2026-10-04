package kr.pc.hotspot

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class HotspotTileService : TileService() {
    private var session: BleSession? = null
    private var toggling = false
    private var unlocking = false
    private var listening = false
    private var latest = Outcome(message = "연결 안 됨")
    override fun onStartListening() {
        super.onStartListening()
        listening = true
        render(latest)
        if (session == null && !unlocking) runSession(Mode.QUERY)
    }
    override fun onStopListening() {
        listening = false
        if (!toggling) { session?.cancel(); session = null }
        super.onStopListening()
    }
    override fun onClick() {
        super.onClick()
        if (toggling || unlocking) return
        if (isLocked) {
            unlocking = true
            unlockAndRun {
                unlocking = false
                if (!isLocked) toggle()
            }
        } else toggle()
    }
    private fun toggle() {
        if (toggling) return
        session?.cancel(); session = null
        runSession(Mode.TOGGLE)
    }
    private fun runSession(mode: Mode) {
        val address = Protocol.preferences(this).getString("address", null)
        if (address == null) {
            latest = Outcome(message = "앱에서 PC를 등록하세요")
            render(latest)
            if (mode == Mode.TOGGLE) Toast.makeText(this, latest.message, Toast.LENGTH_LONG).show()
            return
        }
        toggling = mode == Mode.TOGGLE
        qsTile?.apply {
            label = "PC 핫스팟"
            subtitle = if (toggling) "처리 중" else "상태 확인 중"
            state = if (toggling) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
            updateTile()
        }
        session = BleSession(this, address, mode) { outcome ->
            session = null
            toggling = false
            latest = outcome
            if (listening) render(outcome)
            if (mode == Mode.TOGGLE && outcome.message.isNotEmpty())
                Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
        }
        session?.start()
    }
    private fun render(outcome: Outcome) {
        qsTile?.apply {
            label = "PC 핫스팟"
            // Keep failed connections tappable, so the user can explicitly retry.
            state = if (outcome.state == 1) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = when (outcome.state) { 1 -> "켜짐"; 0 -> "꺼짐"; else -> "연결 안 됨 · ${outcome.message}" }
            if (Build.VERSION.SDK_INT >= 30) stateDescription = if (outcome.message.isEmpty()) subtitle else "$subtitle · ${outcome.message}"
            updateTile()
        }
    }
    override fun onDestroy() {
        session?.cancel(); session = null
        super.onDestroy()
    }
}
