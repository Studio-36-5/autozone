package autozone.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import autozone.core.AutoZoneScreenModel
import autozone.core.ZoneId
import kotlin.math.roundToInt
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import autozone.core.AutoZoneEngine
import autozone.core.ZoneViewItem

/**
 * 오토존 메인 화면 (위치 반경 × 시간대 × 정차 기어 복합 트리거).
 * - 기본 실행: 시뮬레이션 모드 (상단 배지 표시 + 하단 시뮬레이션 테스트 바 제공)
 * - 실제 Pleos SDK 연동: adb shell am start -n autozone.app/.MainActivity --ez pleos true
 */
class MainActivity : Activity() {
    private lateinit var engine: AutoZoneEngine
    private var bridge: PleosAutoZoneBridge? = null
    private lateinit var contentBox: LinearLayout
    private var lastRenderKey: Any? = null
    private var lastDiag = ""
    private enum class Page { HOME, ZONES, DETAIL, DIAGNOSTICS }
    private var page = Page.HOME
    private var selectedZone = ZoneId.OFFICE_BOOTH
    private lateinit var scroll: ScrollView
    private var renderedPage = Page.HOME
    private var feedback = ""

    private val ctrlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                handleCommandIntent(intent)
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.WHITE)

        val usePleos = intent.getBooleanExtra("pleos", false)
        engine = AutoZoneEngine(simulation = !usePleos)

        contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(40), dp(28), dp(40), dp(32))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
            addView(contentBox)
        }
        setContentView(scroll)

        if (usePleos) {
            bridge = PleosAutoZoneBridge(this, engine) { render() }.also {
                val initHour = if (demoInputAllowed && intent.hasExtra("hour")) intent.getIntExtra("hour", 23) else null
                it.overrideHour = initHour
                if (initHour != null) {
                    engine.updateVehicleState { st -> st.copy(currentHour = initHour) }
                }
                it.start()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ctrlReceiver, IntentFilter("autozone.app.CTRL"), Context.RECEIVER_EXPORTED)
        } else {
            registerLegacyControlReceiver()
        }
        handleCommandIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleCommandIntent(intent)
            render()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(ctrlReceiver) }
        bridge?.stop()
        super.onDestroy()
    }

    // Export flags were added in API 33. This branch is only used on older runtimes.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacyControlReceiver() {
        check(Build.VERSION.SDK_INT < 33)
        registerReceiver(ctrlReceiver, IntentFilter("autozone.app.CTRL"))
    }

    // Demo time/location injection (adb intent or broadcast) is accepted only in debuggable builds,
    // so other apps cannot drive vehicle writes through the exported control receiver in a release build.
    private val demoInputAllowed: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private fun handleCommandIntent(intent: Intent) {
        if (!demoInputAllowed) return
        if (intent.hasExtra("hour")) {
            val h = intent.getIntExtra("hour", 23)
            Log.i("AutoZone", "Intent hour=$h")
            if (bridge != null) {
                bridge?.syncHourAndEvaluate(h)
            } else {
                engine.updateVehicleState { it.copy(currentHour = h) }
                engine.onContextChanged()
            }
        }
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i("AutoZone", "Intent cmd=$cmd")
        val hwCmds = when (cmd) {
            "enter_night" -> engine.onLocationUpdated(37.497900, 127.027600)
            "enter_dt" -> engine.onLocationUpdated(37.501500, 127.031500)
            "enter_office" -> engine.onLocationUpdated(37.503400, 127.033400)
            "enter_school" -> engine.onLocationUpdated(37.506800, 127.036800)
            "exit_zone" -> engine.onLocationUpdated(37.488000, 127.018000)
            "undo" -> engine.undoActiveZone()
            else -> emptyList()
        }
        bridge?.executeCommands(hwCmds)
    }

    private fun canManage(model: AutoZoneScreenModel) = !model.editingLocked && engine.state.gear == "PARK"

    private fun navigate(next: Page, zone: ZoneId = selectedZone) {
        if (next != Page.HOME && !canManage(engine.screenModel())) return
        selectedZone = zone
        page = next
        feedback = ""
        render()
    }

    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        when (page) {
            Page.DETAIL, Page.DIAGNOSTICS -> navigate(Page.ZONES)
            Page.ZONES -> navigate(Page.HOME)
            Page.HOME -> super.onBackPressed()
        }
    }

    private fun render() {
        val model = engine.screenModel(bridge?.writeFeedbackSummary())
        val st = engine.state
        val diag = "mode=${if (model.simulation) "SIM" else "PLEOS"} hour=${st.currentHour} active=${engine.activeSession?.zone?.id} lock=${model.editingLocked} recirc=${st.hvacRecirculation} fan=${st.hvacFanSpeed} win=${st.driverWindowPos} amb=${st.ambientBrightness} wiper=${st.wiperActive} gear=${st.gear}"
        if (diag != lastDiag) {
            lastDiag = diag
            Log.i("AutoZone", diag)
        }
        // A restriction arriving while a secondary page is open closes it immediately.
        if (!canManage(model)) page = Page.HOME
        val key = when (page) {
            Page.HOME -> listOf(page, model, st.gear)
            Page.ZONES, Page.DETAIL -> listOf(page, selectedZone, model.editingLocked,
                model.zones.map { listOf(it.id, it.enabled, it.active, it.radiusMeters, it.conditionBadge) },
                model.canRegisterHere, feedback, st.gear, st.etcsVolume)
            Page.DIAGNOSTICS -> listOf(page, model)
        }
        if (key == lastRenderKey) return
        lastRenderKey = key
        val keepPosition = page == renderedPage
        val previousY = if (keepPosition) scroll.scrollY else 0
        val focusedTag = if (keepPosition) contentBox.findFocus()?.tag else null
        contentBox.removeAllViews()
        buildHeader(model)
        when (page) {
            Page.HOME -> buildHome(model)
            Page.ZONES -> buildZoneList(model)
            Page.DETAIL -> buildZoneDetails(model)
            Page.DIAGNOSTICS -> buildDiagnostics(model)
        }
        renderedPage = page
        scroll.post {
            if (focusedTag != null) contentBox.findViewWithTag<View>(focusedTag)?.requestFocus()
            scroll.scrollTo(0, previousY)
        }
    }

    private fun buildHeader(model: AutoZoneScreenModel) {
        val row = horizontal().apply { layoutParams = margin(bottom = 28) }
        if (page != Page.HOME) {
            row.addView(button("뒤로", "back", false) {
                navigate(if (page == Page.ZONES) Page.HOME else Page.ZONES)
            }.apply { layoutParams = LinearLayout.LayoutParams(dp(140), dp(84)).apply { marginEnd = dp(24) } })
        }
        val name = when (page) {
            Page.HOME -> "AutoZone"
            Page.ZONES -> "내 루틴"
            Page.DETAIL -> shortName(selectedZone)
            Page.DIAGNOSTICS -> "연결 및 진단"
        }
        row.addView(label(name, 40f, BRAND, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val gear = when (engine.state.gear) { "PARK" -> "P단"; "DRIVE" -> "D단"; "REVERSE" -> "R단"; "NEUTRAL" -> "N단"; else -> "차량 확인 중" }
        row.addView(label(gear, 28f, INK, true).apply { setPadding(dp(20), 0, dp(24), 0) })
        if (page == Page.HOME && canManage(model)) {
            row.addView(button("루틴 관리", "manage", false) { navigate(Page.ZONES) })
        }
        contentBox.addView(row)
    }

    private fun buildHome(model: AutoZoneScreenModel) {
        val active = engine.activeSession
        val office = engine.zones().first { it.id == ZoneId.OFFICE_BOOTH }
        val officeView = model.zones.first { it.id == ZoneId.OFFICE_BOOTH }
        val insideOffice = office.enabled && officeView.distanceMeters?.let { it <= officeView.radiusMeters } == true
        val panel = vertical().apply {
            background = surface(BRAND, 22)
            setPadding(dp(36), dp(30), dp(36), dp(30))
            layoutParams = margin(bottom = 34)
        }
        val headline = when {
            active != null -> shortName(active.zone.id)
            insideOffice -> "오피스 마이부스 대기"
            else -> "자동화 대기 중"
        }
        val title = horizontal()
        title.addView(label(headline, 54f, Color.WHITE, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (active != null) title.addView(label("실행 중", 30f, Color.WHITE, true))
        panel.addView(title)
        val description = when {
            active?.zone?.id == ZoneId.OFFICE_BOOTH -> "조용한 통화와 휴식을 위한 설정"
            active?.zone?.id == ZoneId.NIGHT_PARKING -> "늦은 밤, 조용하게 주차해요"
            active?.zone?.id == ZoneId.DRIVE_THRU -> "주문할 때 창문과 풍량을 함께"
            active?.zone?.id == ZoneId.SCHOOL_PICKUP -> "아이를 기다리는 동안 쾌적하게"
            insideOffice && office.timeWindow?.matches(engine.state.currentHour) == false -> "11:00–14:00에 작동해요"
            insideOffice && engine.state.gear != "PARK" -> "P단 전환을 기다리고 있어요"
            insideOffice -> "실행 조건을 확인하고 있어요"
            else -> "등록한 장소와 시간에 맞춰 준비해요"
        }
        panel.addView(label(description, 32f, Color.WHITE).apply { layoutParams = margin(top = 14) })
        val condition = if (active != null) active.zone.conditionBadge() else if (insideOffice) "회사 주차장 · 11:00–14:00 · P단" else "장소 · 시간 · 차량 상태에 맞춘 루틴"
        panel.addView(label(condition, 26f, PALE_TEXT).apply { layoutParams = margin(top = 20) })
        if (model.safetyNotice != null) {
            val notice = if (model.safetyNotice.contains("우천")) "와이퍼 작동 · 창문 개방 보류" else model.safetyNotice
            panel.addView(label(notice, 30f, BRAND, true).apply {
                background = surface(Color.WHITE, 10)
                setPadding(dp(20), dp(14), dp(20), dp(14))
                layoutParams = margin(top = 20)
            })
        }
        if (bridge?.writeFeedbackSummary()?.contains("제한") == true) {
            panel.addView(label("일부 설정 적용 제한 · 정차 후 진단 확인", 28f, Color.WHITE, true).apply { layoutParams = margin(top = 18) })
        }
        contentBox.addView(panel)
        contentBox.addView(label("현재 설정", 28f, MUTED, true).apply { layoutParams = margin(bottom = 18) })
        val state = engine.state
        val values = listOf(
            Triple("공조 풍량", state.hvacFanSpeed?.let { "${it}단" } ?: "확인 중", "fan"),
            Triple("무드등", state.ambientBrightness?.let { "${it}%" } ?: "확인 중", "light"),
            Triple("공기청정", when (state.hvacAirCleaner) { true -> "켜짐"; false -> "꺼짐"; null -> "확인 중" }, "air"),
            Triple("운전석 창문", state.driverWindowPos?.let { if (it == 0) "닫힘" else "${it}%" } ?: "확인 중", "window"),
        )
        val columns = if (resources.configuration.screenWidthDp < 1100) 2 else 4
        values.chunked(columns).forEach { group ->
            val row = horizontal().apply { layoutParams = margin(bottom = 28) }
            group.forEachIndexed { index, (name, value, symbol) ->
                if (index > 0) row.addView(View(this).apply {
                    setBackgroundColor(LINE)
                    layoutParams = LinearLayout.LayoutParams(dp(1), dp(146))
                })
                val metric = vertical().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(12), dp(4), dp(12), dp(14))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val caption = horizontal().apply { gravity = Gravity.CENTER }
                caption.addView(VehicleIconView(this, symbol, BRAND).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) } })
                caption.addView(label(name, 28f, MUTED))
                metric.addView(caption)
                metric.addView(label(value, if (value == "확인 중") 40f else 64f, BRAND, true).apply {
                    gravity = Gravity.CENTER
                    layoutParams = margin(top = 18)
                })
                metric.contentDescription = "$name $value"
                metric.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                row.addView(metric)
            }
            contentBox.addView(row)
        }
        if (model.canUndo) {
            contentBox.addView(button("원래 설정으로 복원", "restore", true) {
                val commands = engine.undoActiveZone()
                bridge?.executeCommands(commands)
                render()
            }.apply { textSize = 38f; layoutParams = LinearLayout.LayoutParams(-1, dp(116)) })
            val exit = if (active?.zone?.requireParkGear == true) "P단을 벗어나면 이전 설정으로 자동 복원" else "구역 이탈·시간대 종료 시 이전 설정으로 복원"
            contentBox.addView(label(exit, 28f, MUTED).apply { gravity = Gravity.CENTER; layoutParams = margin(top = 18) })
        } else {
            contentBox.addView(label(if (canManage(model)) "장소 등록과 루틴 설정은 ‘루틴 관리’에서" else "루틴 관리는 P단에서 이용할 수 있어요", 28f, MUTED).apply {
                background = surface(SURFACE, 12)
                setPadding(dp(28), dp(25), dp(28), dp(25))
            })
        }
        val mode = if (model.simulation) "시뮬레이션" else "차량 SDK 모드"
        val clock = if (bridge?.overrideHour != null || model.simulation) "시연 시각 ${model.currentTimeLabel}" else model.currentTimeLabel
        contentBox.addView(label("$mode  ·  $clock", 24f, MUTED).apply { layoutParams = margin(top = 28) })
    }

    private fun buildZoneList(model: AutoZoneScreenModel) {
        contentBox.addView(label("사용할 루틴을 선택하세요", 32f, MUTED).apply { layoutParams = margin(bottom = 22) })
        val order = listOf(ZoneId.OFFICE_BOOTH, ZoneId.NIGHT_PARKING, ZoneId.DRIVE_THRU, ZoneId.SCHOOL_PICKUP)
        order.forEach { id ->
            val zone = model.zones.first { it.id == id }
            val row = horizontal().apply {
                minimumHeight = dp(138)
                setPadding(dp(28), dp(20), dp(24), dp(20))
                background = surface(if (zone.active) SURFACE else Color.WHITE, 12, LINE)
                layoutParams = margin(bottom = 20)
            }
            val texts = vertical().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            texts.addView(label(shortName(id), 36f, BRAND, true))
            texts.addView(label(zone.conditionBadge, 28f, MUTED).apply { layoutParams = margin(top = 8) })
            row.addView(texts)
            row.addView(label(if (zone.active) "실행 중" else if (zone.enabled) "켜짐" else "꺼짐", 28f, INK, true).apply { setPadding(0, 0, dp(28), 0) })
            row.addView(button("설정", "zone-${id.name}", false) { navigate(Page.DETAIL, id) })
            contentBox.addView(row)
        }
        contentBox.addView(button("연결 및 진단", "diagnostics", false) { navigate(Page.DIAGNOSTICS) }.apply { layoutParams = margin(top = 12) })
    }

    private fun buildZoneDetails(model: AutoZoneScreenModel) {
        val zone = model.zones.first { it.id == selectedZone }
        contentBox.addView(label(zone.conditionBadge + "  ·  반경 ${zone.radiusMeters}m", 32f, MUTED).apply { layoutParams = margin(bottom = 30) })
        contentBox.addView(label("이 루틴이 바꾸는 설정", 36f, BRAND, true).apply { layoutParams = margin(bottom = 22) })
        val effects = when (selectedZone) {
            ZoneId.OFFICE_BOOTH -> listOf("창문 닫기 · 풍량 1단", "공기청정 켜기 · 무드등 30%")
            ZoneId.NIGHT_PARKING -> listOf("내기순환 · 공기청정 켜기", "무드등 10% · 하이패스 음량 0")
            ZoneId.DRIVE_THRU -> listOf("운전석 창문 80% 개방 · 풍량 1단", "와이퍼 작동·미확인 시 창문 개방 보류")
            ZoneId.SCHOOL_PICKUP -> listOf("2열 창문 잠금 · 공기청정 켜기", "풍량 2단")
        }
        effects.forEach { contentBox.addView(label(it, 32f, INK).apply { layoutParams = margin(bottom = 16) }) }
        if (selectedZone == ZoneId.DRIVE_THRU) {
            contentBox.addView(label("P단 정차에서만 창문을 열고, P단을 벗어나면 원래대로 닫습니다.", 28f, BRAND, true).apply { layoutParams = margin(top = 10, bottom = 20) })
        }
        if (selectedZone == ZoneId.NIGHT_PARKING && (engine.state.etcsVolume ?: 0) < 0) {
            contentBox.addView(label("이 차량은 하이패스 음량 제어를 지원하지 않아요.", 28f, MUTED).apply { layoutParams = margin(bottom = 20) })
        }
        contentBox.addView(label("차량이 지원하는 항목만 제어합니다.", 26f, MUTED).apply { layoutParams = margin(bottom = 32) })
        contentBox.addView(button(if (zone.enabled) "자동 실행 끄기" else "자동 실행 켜기", "toggle-${zone.id}", zone.enabled) {
            if (canManage(engine.screenModel())) {
                val commands = engine.toggleZone(zone.id)
                bridge?.executeCommands(commands)
                bridge?.refreshGeofences()
                feedback = if (engine.zones().first { it.id == zone.id }.enabled) "자동 실행을 켰어요" else "자동 실행을 껐어요"
                render()
            }
        }.apply { layoutParams = margin(bottom = 24) })
        contentBox.addView(button("현재 위치를 이 루틴에 등록", "register-${zone.id}", false) {
            if (canManage(engine.screenModel())) {
                val ok = engine.registerCurrentLocationToZone(zone.id)
                if (ok) bridge?.refreshGeofences()
                feedback = if (ok) "현재 위치를 등록했어요" else "위치 수신 후 다시 시도해 주세요"
                render()
            }
        }.apply {
            isEnabled = model.canRegisterHere
            if (!isEnabled) alpha = 0.5f
            layoutParams = margin(bottom = 22)
        })
        if (!model.canRegisterHere) contentBox.addView(label("현재 위치를 수신하고 있어요", 28f, MUTED))
        if (feedback.isNotBlank()) contentBox.addView(label(feedback, 30f, BRAND, true))
        contentBox.addView(label("등록 위치와 켜짐 상태는 현재 실행 세션에 적용됩니다.", 26f, MUTED).apply { layoutParams = margin(top = 26) })
    }

    private fun buildDiagnostics(model: AutoZoneScreenModel) {
        contentBox.addView(label(if (model.simulation) "시뮬레이션 · 실제 차량 제어 아님" else "공개 Pleos SDK 연결 상태", 32f, BRAND, true).apply { layoutParams = margin(bottom = 24) })
        listOf(model.hardwareSummaryLine1, model.hardwareSummaryLine2, model.lastEventLog).forEach {
            contentBox.addView(label(it, 28f, INK).apply { layoutParams = margin(bottom = 26) })
        }
        if (model.simulation) {
            contentBox.addView(label("개발용 시나리오", 32f, BRAND, true).apply { layoutParams = margin(top = 22, bottom = 20) })
            contentBox.addView(button("와이퍼 " + if (engine.state.wiperActive == true) "끄기" else "켜기", "sim-wiper", false) {
                if (canManage(engine.screenModel())) {
                    engine.updateVehicleState { it.copy(wiperActive = it.wiperActive != true) }
                    render()
                }
            }.apply { layoutParams = margin(bottom = 24) })
            contentBox.addView(button("시연 시각 전환 (14시 / 23시)", "sim-hour", false) {
                if (canManage(engine.screenModel())) {
                    engine.updateVehicleState { it.copy(currentHour = if (it.currentHour == 14) 23 else 14) }
                    engine.onContextChanged()
                    render()
                }
            }.apply { layoutParams = margin(bottom = 24) })
            listOf("마이부스" to "enter_office", "심야 매너존" to "enter_night", "드라이브스루" to "enter_dt", "구역 이탈" to "exit_zone").forEach { (title, command) ->
                contentBox.addView(button(title, "sim-$command", false) {
                    if (!canManage(engine.screenModel())) return@button
                    engine.updateVehicleState { it.copy(currentHour = if (command == "enter_night") 23 else 12) }
                    handleCommandIntent(Intent().putExtra("cmd", command))
                    navigate(Page.HOME)
                }.apply { layoutParams = margin(bottom = 24) })
            }
        }
    }

    private fun shortName(id: ZoneId) = when (id) {
        ZoneId.OFFICE_BOOTH -> "오피스 마이부스"
        ZoneId.NIGHT_PARKING -> "심야 매너존"
        ZoneId.DRIVE_THRU -> "드라이브스루"
        ZoneId.SCHOOL_PICKUP -> "하교 픽업존"
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(5).toFloat(), 1.02f)
    }
    private fun button(value: String, key: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = value; tag = key; textSize = 30f; isAllCaps = false
        minHeight = dp(84); minimumWidth = dp(132)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else BRAND)
        setPadding(dp(28), dp(18), dp(28), dp(18))
        val fill = surface(if (primary) BRAND else SURFACE, 12)
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x55FFFFFF else 0x22002C5F), fill, null)
        setOnFocusChangeListener { _, focused ->
            fill.setStroke(dp(if (focused) 3 else 0), if (primary) Color.rgb(101, 159, 229) else BRAND)
        }
        setOnClickListener { click() }
    }
    private fun surface(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    companion object {
        private val BRAND = Color.rgb(0, 44, 95)
        private val INK = Color.rgb(20, 40, 63)
        private val MUTED = Color.rgb(68, 89, 111)
        private val SURFACE = Color.rgb(238, 243, 249)
        private val LINE = Color.rgb(198, 210, 225)
        private val PALE_TEXT = Color.rgb(214, 228, 246)
    }
}
