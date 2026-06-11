package com.first.ufotw

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.first.ufotw.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager
    private lateinit var patternEngine: PatternEngine
    private var menu: Menu? = null

    private var speed1 = 0; private var dir1 = UfoTwProtocol.CW
    private var speed2 = 0; private var dir2 = UfoTwProtocol.CW

    enum class AppMode { NONE, LOCAL, REMOTE }
    private var appMode = AppMode.NONE
    private var remoteSession: RemoteSession? = null
    private var remoteRoomCode: String = ""

    // Plaza fields
    private lateinit var sharedPlazaRepo: SharedPlazaRepository
    private lateinit var plazaAdapter: SharedPatternAdapter
    private var plazaJob: Job? = null

    // Playhead fields
    private var playingPattern: SharedPattern? = null
    private var playStartMs: Long = 0L
    private var playheadJob: Job? = null

    private val isLinked get() = binding.toggleLink.checkedButtonId != R.id.btnLinkOff
    private val isMirror get() = binding.toggleLink.checkedButtonId == R.id.btnLinkMirror

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // リモート受信インジケーター非表示用（最後の受信から一定時間で消える）
    private val hideIndicatorRunnable = Runnable {
        binding.tvRemoteIndicator.visibility = View.GONE
        updateConnectionStatus()
    }

    companion object {
        private const val REMOTE_INDICATOR_TIMEOUT_MS = 4000L
    }

    // 送信失敗 Snackbar の連打防止
    private var sendFailureSnackbarShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ble = BleManager(this)
        patternEngine = PatternEngine(this, ble)

        patternEngine.isMirrorMode = { isMirror }
        setupBleCallbacks()
        setupUi()
        buildPatternChips()
        showModeSelect()

        // Plaza setup
        sharedPlazaRepo = SharedPlazaRepository()
        plazaAdapter = SharedPatternAdapter().apply {
            onTryClick = { sp -> onTrySharedPattern(sp) }
        }
        binding.rvPlaza.layoutManager = LinearLayoutManager(this)
        binding.rvPlaza.adapter = plazaAdapter

        binding.btnOpenPlaza.setOnClickListener { openPlaza() }
        binding.btnPlazaBack.setOnClickListener { closePlaza() }
        binding.etPlazaSearch.addTextChangedListener { text ->
            plazaAdapter.setFilter(text?.toString().orEmpty())
        }

        // システムバックボタンでモード選択に戻る（広場が開いているときは広場を閉じる）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.layoutPlaza.visibility == View.VISIBLE -> closePlaza()
                    appMode != AppMode.NONE -> goBackToModeSelect()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // ── モード選択 ────────────────────────────────────────────────────────────

    private fun showModeSelect() {
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        binding.layoutRoomInput.visibility = View.VISIBLE
        binding.btnAction.text = "スキャン"
        binding.btnAction.isEnabled = true
        binding.btnAction.visibility = View.VISIBLE
        binding.layoutRoomCode.visibility = View.GONE
        binding.layoutControls.visibility = View.GONE
        binding.layoutPlaza.visibility = View.GONE
        binding.toolbar.subtitle = ""
        binding.progressScan.visibility = View.GONE
        updateConnectionStatus()
    }

    private fun goBackToModeSelect() {
        plazaJob?.cancel()
        plazaJob = null
        patternEngine.stop()
        ble.disconnect()
        remoteSession?.stopListening()
        remoteSession = null
        UfoTwForegroundService.stop(this)
        appMode = AppMode.NONE
        showModeSelect()
    }

    private fun enterLocalMode() {
        appMode = AppMode.LOCAL
        val code = RemoteSession.generateRoomCode()
        remoteSession = RemoteSession(code)
        binding.tvRoomCode.text = code
        binding.layoutRoomCode.visibility = View.VISIBLE
        binding.layoutRoomInput.visibility = View.GONE
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateConnectionStatus()
        startScanWithPermission()
    }

    private fun enterRemoteMode(roomCode: String) {
        appMode = AppMode.REMOTE
        remoteRoomCode = roomCode
        remoteSession = RemoteSession(roomCode)
        val sender = FirebaseSender(roomCode).apply {
            onSendFailed = { showSendFailureSnackbar() }
        }
        patternEngine = PatternEngine(this, sender).also {
            it.isMirrorMode = { isMirror }
            it.onPatternChanged = { name -> updatePatternChips(name) }
            it.onStepPlaying = { s1, d1, s2, d2 ->
                val a1 = if (d1 == UfoTwProtocol.CW) "↻" else "↺"
                val a2 = if (d2 == UfoTwProtocol.CW) "↻" else "↺"
                binding.tvSpeed1.text = "$a1 $s1"
                binding.tvSpeed2.text = "$a2 $s2"
            }
        }
        binding.layoutRoomInput.visibility = View.GONE
        binding.btnAction.visibility = View.GONE
        binding.layoutControls.visibility = View.VISIBLE
        binding.toolbar.subtitle = "リモート: $roomCode"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateConnectionStatus()
    }

    // ── メニュー ──────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        this.menu = menu
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { goBackToModeSelect(); true }
            R.id.menuDisconnect -> { patternEngine.stop(); ble.disconnect(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── BLE コールバック ──────────────────────────────────────────────────────

    private fun setupBleCallbacks() {
        ble.onStateChanged = { state ->
            when (state) {
                BleManager.State.DISCONNECTED -> showDisconnected()
                BleManager.State.SCANNING     -> showScanning()
                BleManager.State.CONNECTING   -> showConnecting()
                BleManager.State.CONNECTED    -> showConnected()
            }
            updateConnectionStatus()
        }
        ble.onDeviceFound = { device -> ble.connect(device) }
        ble.onScanTimeout = {
            binding.progressScan.visibility = View.GONE
            showSnackbar("デバイスが見つかりませんでした。電源を確認して再試行してください")
        }
        ble.onWriteFailed = { showSendFailureSnackbar() }
        patternEngine.onPatternChanged = { name ->
            updatePatternChips(name)
            if (name == null) stopPlayhead()
        }
        patternEngine.onStepPlaying = { s1, d1, s2, d2 ->
            val a1 = if (d1 == UfoTwProtocol.CW) "↻" else "↺"
            val a2 = if (d2 == UfoTwProtocol.CW) "↻" else "↺"
            binding.tvSpeed1.text = "$a1 $s1"
            binding.tvSpeed2.text = "$a2 $s2"
        }
    }

    // ── UI セットアップ ───────────────────────────────────────────────────────

    private fun setupUi() {
        for (slider in listOf(binding.slider1, binding.slider2)) {
            slider.valueFrom = 0f
            slider.valueTo = 100f
            slider.stepSize = 1f
            slider.value = 0f
        }

        binding.btnCopyCode.setOnClickListener {
            val code = binding.tvRoomCode.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("room_code", code))
            Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareCode.setOnClickListener {
            val code = binding.tvRoomCode.text.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://ufotwcontrol.web.app?room=$code")
            }
            startActivity(Intent.createChooser(intent, null))
        }

        binding.btnRemoteConnect.setOnClickListener {
            val code = binding.etRoomCode.text?.toString()?.trim()?.uppercase()
            if (code.isNullOrEmpty()) {
                Toast.makeText(this, "ルームコードを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enterRemoteMode(code)
        }

        binding.btnAction.setOnClickListener {
            when {
                appMode == AppMode.NONE                      -> enterLocalMode()
                ble.state == BleManager.State.DISCONNECTED   -> startScanWithPermission()
                ble.state == BleManager.State.SCANNING       -> ble.stopScan()
                else                                         -> {}
            }
        }

        binding.btnStopAll.setOnClickListener {
            // STOP: パターン停止 + 両ロータ停止 + スライダーリセットを一括で行う
            patternEngine.stop()
            speed1 = 0; speed2 = 0
            sendCommand()
            binding.slider1.value = 0f
            binding.slider2.value = 0f
            binding.tvSpeed1.text = "0"
            binding.tvSpeed2.text = "0"
        }

        binding.btnStopPattern.setOnClickListener {
            // パターンのみ停止（回転速度は維持しない＝0送信で停止する）
            patternEngine.stop()
        }

        binding.toggleDir1.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            interruptPatternIfPlaying()
            dir1 = if (checkedId == R.id.btnCw1) UfoTwProtocol.CW else UfoTwProtocol.CCW
            sendCommand()
        }
        binding.slider1.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                interruptPatternIfPlaying()
                speed1 = value.toInt(); binding.tvSpeed1.text = "$speed1"; sendCommand()
            }
        }

        binding.toggleDir2.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            interruptPatternIfPlaying()
            dir2 = if (checkedId == R.id.btnCw2) UfoTwProtocol.CW else UfoTwProtocol.CCW
            sendCommand()
        }
        binding.slider2.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                interruptPatternIfPlaying()
                speed2 = value.toInt(); binding.tvSpeed2.text = "$speed2"; sendCommand()
            }
        }

        binding.toggleLink.addOnButtonCheckedListener { _, _, _ ->
            val alpha = if (isLinked) 0.35f else 1f
            binding.rotor2Card.alpha = alpha
            setRotor2Enabled(!isLinked)
            binding.tvLinkDesc.text = when (binding.toggleLink.checkedButtonId) {
                R.id.btnLinkOff    -> "ロータ 1・2 を個別に操作"
                R.id.btnLinkSync   -> "ロータ 2 がロータ 1 と同じ方向で連動"
                R.id.btnLinkMirror -> "ロータ 2 がロータ 1 と逆方向で連動"
                else -> ""
            }
            sendCommand()
        }
    }

    private fun sendCommand() {
        val (s2, d2) = if (isLinked) speed1 to (if (isMirror) 1 - dir1 else dir1)
                       else           speed2 to dir2
        when (appMode) {
            AppMode.LOCAL  -> if (ble.state == BleManager.State.CONNECTED)
                                  ble.send(UfoTwProtocol.buildCommand(speed1, dir1, s2, d2))
            AppMode.REMOTE -> patternEngine.sender.send(UfoTwProtocol.buildCommand(speed1, dir1, s2, d2))
            AppMode.NONE   -> {}
        }
    }

    /**
     * パターン再生中にスライダー・方向ボタンが操作されたら、
     * 手動操作を優先してパターンを停止する（操作の干渉を防ぐ）。
     */
    private fun interruptPatternIfPlaying() {
        if (patternEngine.currentPattern != null) {
            patternEngine.stop()
            showSnackbar("パターンを停止しました")
        }
    }

    private fun setRotor2Enabled(enabled: Boolean) {
        binding.toggleDir2.isEnabled = enabled
        binding.btnCw2.isEnabled = enabled
        binding.btnCcw2.isEnabled = enabled
        binding.slider2.isEnabled = enabled
    }

    // ── リモート受信インジケーター ─────────────────────────────────────────────

    private fun showRemoteIndicator(s1: Int, d1: Int, s2: Int, d2: Int) {
        val a1 = if (d1 == UfoTwProtocol.CW) "↻" else "↺"
        val a2 = if (d2 == UfoTwProtocol.CW) "↻" else "↺"
        binding.tvSpeed1.text = "$a1 $s1"
        binding.tvSpeed2.text = "$a2 $s2"
        binding.tvRemoteIndicator.visibility = View.VISIBLE
        // 受信が続く間は表示し続け、最後の受信から一定時間後に消える
        binding.tvRemoteIndicator.removeCallbacks(hideIndicatorRunnable)
        binding.tvRemoteIndicator.postDelayed(hideIndicatorRunnable, REMOTE_INDICATOR_TIMEOUT_MS)
        updateConnectionStatus(remoteActive = true)
    }

    // ── Snackbar 通知 ─────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    /** 送信失敗の通知（連続失敗時に連打しないようデバウンスする） */
    private fun showSendFailureSnackbar() {
        if (sendFailureSnackbarShowing) return
        sendFailureSnackbarShowing = true
        Snackbar.make(binding.root, "送信に失敗しました", Snackbar.LENGTH_SHORT)
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    sendFailureSnackbarShowing = false
                }
            })
            .show()
    }

    // ── 接続状態インジケーター ───────────────────────────────────────────────────

    private enum class StatusColor(val color: Int) {
        CONNECTED(0xFF33CC66.toInt()),
        SCANNING(0xFFFFAA00.toInt()),
        REMOTE(0xFF3388FF.toInt()),
        DISCONNECTED(0xFFFF3333.toInt())
    }

    private fun setStatusDot(color: StatusColor) {
        val drawable = (binding.viewStatusDot.background as? GradientDrawable)
            ?: GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                binding.viewStatusDot.background = this
            }
        drawable.setColor(color.color)
    }

    /**
     * 接続状態（接続済み/切断/スキャン中/リモート）を色付きドット＋ラベルで常時表示する。
     * ツールバー直下（AppBarLayout内）に配置しているためスクロールしても隠れない。
     */
    private fun updateConnectionStatus(remoteActive: Boolean = false) {
        if (appMode == AppMode.NONE) {
            binding.layoutConnectionStatus.visibility = View.GONE
            return
        }
        binding.layoutConnectionStatus.visibility = View.VISIBLE

        val (color, label) = when (appMode) {
            AppMode.REMOTE -> {
                if (remoteActive) StatusColor.REMOTE to "リモート操作受信中"
                else StatusColor.REMOTE to "リモート: $remoteRoomCode"
            }
            AppMode.LOCAL -> when (ble.state) {
                BleManager.State.CONNECTED  -> StatusColor.CONNECTED to "接続済み"
                BleManager.State.CONNECTING -> StatusColor.SCANNING to "接続中…"
                BleManager.State.SCANNING   -> StatusColor.SCANNING to "スキャン中…"
                BleManager.State.DISCONNECTED -> StatusColor.DISCONNECTED to "切断"
            }
            AppMode.NONE -> StatusColor.DISCONNECTED to ""
        }
        setStatusDot(color)
        binding.tvConnectionStatus.text = label
    }

    // ── パターンチップ生成 ────────────────────────────────────────────────────

    private val patternChips = mutableMapOf<String, Chip>()
    private var patternMetas = listOf<PatternMeta>()

    private fun buildPatternChips() {
        patternMetas = patternEngine.listPatternMeta()
        binding.chipGroup.removeAllViews()
        patternChips.clear()

        patternMetas.forEach { meta ->
            val chip = Chip(this).apply {
                text = "${meta.emoji} ${meta.name}"
                isCheckable = true
                isChecked = false
                setOnClickListener { togglePattern(meta.name) }
            }
            patternChips[meta.name] = chip
            binding.chipGroup.addView(chip)
        }
    }

    private fun togglePattern(name: String) {
        if (patternEngine.currentPattern == name) patternEngine.stop()
        else patternEngine.play(name)
    }

    private fun updatePatternChips(active: String?) {
        patternChips.forEach { (name, chip) ->
            chip.isChecked = (name == active)
        }
        if (active != null) {
            val meta = patternMetas.find { it.name == active }
            binding.tvPatternStatus.text = "▶  ${meta?.emoji ?: ""} $active  —  ${meta?.description ?: ""}"
            binding.layoutPatternStatus.visibility = View.VISIBLE
        } else {
            binding.layoutPatternStatus.visibility = View.GONE
            binding.tvSpeed1.text = "$speed1"
            binding.tvSpeed2.text = "$speed2"
        }
    }

    // ── 共有広場 ──────────────────────────────────────────────────────────────

    private fun openPlaza() {
        binding.layoutControls.visibility = View.GONE
        binding.layoutPlaza.visibility = View.VISIBLE
        plazaJob = lifecycleScope.launch {
            sharedPlazaRepo.observePatterns().collect { docs ->
                plazaAdapter.submit(docs)
                if (docs.isEmpty()) {
                    binding.tvPlazaEmpty.visibility = View.VISIBLE
                    binding.tvPlazaEmpty.text = "まだ投稿がありません"
                } else {
                    binding.tvPlazaEmpty.visibility = View.GONE
                    binding.tvPlazaEmpty.text = ""
                }
            }
        }
    }

    private fun closePlaza() {
        plazaJob?.cancel()
        plazaJob = null
        stopPlayhead()
        binding.layoutPlaza.visibility = View.GONE
        binding.layoutControls.visibility = View.VISIBLE
    }

    private fun onTrySharedPattern(sp: SharedPattern) {
        patternEngine.playSpec("plaza:${sp.title}", sp.steps, sp.loop)
        playingPattern = sp
        playStartMs = System.currentTimeMillis()
        startPlayheadLoop()
    }

    private fun startPlayheadLoop() {
        playheadJob?.cancel()
        val sp = playingPattern ?: return
        val totalMs = sp.steps.sumOf { it.durationMs }.coerceAtLeast(1L)
        playheadJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - playStartMs
                val progress = if (sp.loop) {
                    (elapsed % totalMs).toFloat() / totalMs
                } else {
                    (elapsed.toFloat() / totalMs).coerceAtMost(1f)
                }
                plazaAdapter.setPlayhead(sp.id, progress)
                if (!sp.loop && elapsed >= totalMs) break
                delay(33)
            }
            plazaAdapter.setPlayhead(null, 0f)
        }
    }

    private fun stopPlayhead() {
        playheadJob?.cancel()
        playheadJob = null
        playingPattern = null
        plazaAdapter.setPlayhead(null, 0f)
    }

    // ── 状態表示 ──────────────────────────────────────────────────────────────

    private fun showDisconnected() {
        remoteSession?.stopListening()
        UfoTwForegroundService.stop(this)
        binding.toolbar.subtitle = "切断"
        binding.btnAction.text = "スキャン"
        binding.btnAction.isEnabled = true
        binding.btnAction.visibility = View.VISIBLE
        binding.progressScan.visibility = View.GONE
        menu?.findItem(R.id.menuDisconnect)?.isVisible = false
        binding.layoutControls.visibility = View.GONE
    }

    private fun showScanning() {
        binding.toolbar.subtitle = "スキャン中…"
        binding.btnAction.text = "キャンセル"
        binding.btnAction.visibility = View.VISIBLE
        binding.progressScan.visibility = View.VISIBLE
        menu?.findItem(R.id.menuDisconnect)?.isVisible = false
    }

    private fun showConnecting() {
        binding.toolbar.subtitle = "接続中…"
        binding.btnAction.text = "接続中…"
        binding.btnAction.isEnabled = false
        binding.btnAction.visibility = View.VISIBLE
        binding.progressScan.visibility = View.GONE
        menu?.findItem(R.id.menuDisconnect)?.isVisible = false
    }

    private fun showConnected() {
        val roomCode = binding.tvRoomCode.text.toString()
        binding.toolbar.subtitle = "接続済み"
        binding.btnAction.visibility = View.GONE
        binding.progressScan.visibility = View.GONE
        menu?.findItem(R.id.menuDisconnect)?.isVisible = true
        binding.layoutControls.visibility = View.VISIBLE

        // フォアグラウンドサービス開始（バックグラウンド時もBLE+Firebase維持）
        UfoTwForegroundService.start(this, roomCode)

        // ローカルモード：Firebaseからコマンドを受け取ってBLEに転送 + UI更新
        remoteSession?.startListening { s1, d1, s2, d2 ->
            ble.send(UfoTwProtocol.buildCommand(s1, d1, s2, d2))
            runOnUiThread { showRemoteIndicator(s1, d1, s2, d2) }
        }

        speed1 = 0; speed2 = 0; dir1 = UfoTwProtocol.CW; dir2 = UfoTwProtocol.CW
        binding.slider1.value = 0f
        binding.slider2.value = 0f
        binding.tvSpeed1.text = "0"
        binding.tvSpeed2.text = "0"
        binding.toggleDir1.check(R.id.btnCw1)
        binding.toggleDir2.check(R.id.btnCw2)
        binding.toggleLink.check(R.id.btnLinkOff)
        binding.rotor2Card.alpha = 1f
        setRotor2Enabled(true)
        binding.tvLinkDesc.text = "ロータ 1・2 を個別に操作"
        binding.layoutPatternStatus.visibility = View.GONE
        binding.tvRemoteIndicator.visibility = View.GONE
    }

    // ── パーミッション ────────────────────────────────────────────────────────

    private fun startScanWithPermission() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ble.startScan()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            ble.startScan()
        } else {
            Toast.makeText(this, "Bluetooth権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        plazaJob?.cancel()
        playheadJob?.cancel()
        remoteSession?.stopListening()
        patternEngine.destroy()
        ble.disconnect()
    }
}
