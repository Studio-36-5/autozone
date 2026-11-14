package autozone.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ZoneId {
    NIGHT_PARKING,
    DRIVE_THRU,
    OFFICE_BOOTH,
    SCHOOL_PICKUP,
}

/**
 * 작동 시간대 조건 (24시간제).
 * - 일반 구간: startHour=11, endHour=14 -> 11:00 ~ 13:59 활성
 * - 자정 넘김 심야 구간: startHour=22, endHour=6 -> 22:00 ~ 익일 05:59 활성
 */
data class TimeWindow(
    val startHour: Int,
    val endHour: Int,
) {
    fun matches(hourOfDay: Int): Boolean {
        val h = ((hourOfDay % 24) + 24) % 24
        if (startHour == endHour) return true
        return if (startHour < endHour) {
            h in startHour until endHour
        } else {
            h >= startHour || h < endHour
        }
    }

    fun label(): String = "${pad2(startHour)}:00~${pad2(endHour)}:00"

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()
}

data class GeoZone(
    val id: ZoneId,
    val title: String,
    val summary: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val enabled: Boolean = true,
    val timeWindow: TimeWindow? = null,
    val requireParkGear: Boolean = false,
    val targetEtcsVolume: Int? = null,
    val targetRecirculation: Boolean? = null,
    val targetAirCleaner: Boolean? = null,
    val targetAmbientBrightness: Int? = null,
    val targetDriverWindowPos: Int? = null,
    val targetFanSpeed: Int? = null,
    val targetRearWindowLock: Boolean? = null,
) {
    fun conditionBadge(): String {
        val timeText = timeWindow?.label() ?: "상시 작동"
        return if (requireParkGear) "$timeText · P단 정차" else timeText
    }
}

data class VehicleState(
    val etcsVolume: Int? = null,
    val hvacRecirculation: Boolean? = null,
    val hvacAirCleaner: Boolean? = null,
    val hvacFanSpeed: Int? = null,
    val ambientBrightness: Int? = null,
    val driverWindowPos: Int? = null,
    val rearWindowLock: Boolean? = null,
    val wiperActive: Boolean? = null,
    val gear: String? = null,
    val uxRestricted: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val currentHour: Int = 23,
)

data class HardwareSnapshot(
    val etcsVolume: Int?,
    val hvacRecirculation: Boolean?,
    val hvacAirCleaner: Boolean?,
    val hvacFanSpeed: Int?,
    val ambientBrightness: Int?,
    val driverWindowPos: Int?,
    val rearWindowLock: Boolean?,
)

sealed interface HardwareCommand {
    val label: String

    data class SetEtcsVolume(val volume: Int) : HardwareCommand {
        override val label: String get() = "하이패스 음량 $volume"
    }

    data class SetHvacRecirculation(val enabled: Boolean) : HardwareCommand {
        override val label: String get() = if (enabled) "공조 내기순환 켜짐" else "공조 외기유입 복귀"
    }

    data class SetHvacAirCleaner(val enabled: Boolean) : HardwareCommand {
        override val label: String get() = if (enabled) "공기청정 가동" else "공기청정 해제"
    }

    data class SetHvacFanSpeed(val speed: Int) : HardwareCommand {
        override val label: String get() = "공조 풍량 ${speed}단"
    }

    data class SetAmbientBrightness(val brightness: Int) : HardwareCommand {
        override val label: String get() = "무드등 밝기 ${brightness}%"
    }

    data class SetDriverWindowPos(val percent: Int) : HardwareCommand {
        override val label: String get() = if (percent == 0) "운전석 창문 닫힘(0%)" else "운전석 창문 ${percent}% 개방"
    }

    data class SetRearWindowLock(val locked: Boolean) : HardwareCommand {
        override val label: String get() = if (locked) "2열 창문 어린이 잠금" else "2열 창문 잠금 해제"
    }

    data class SpeakTts(val text: String) : HardwareCommand {
        override val label: String get() = "음성 브리핑"
    }
}

enum class ChangedField {
    ETCS_VOLUME,
    HVAC_RECIRCULATION,
    HVAC_AIR_CLEANER,
    HVAC_FAN_SPEED,
    AMBIENT_BRIGHTNESS,
    DRIVER_WINDOW_POS,
    REAR_WINDOW_LOCK,
}

data class ActiveRoutineSession(
    val zone: GeoZone,
    val triggerSource: String,
    val snapshot: HardwareSnapshot,
    val appliedLabels: List<String>,
    val changedFields: Set<ChangedField>,
    val safetySkippedNote: String?,
)

data class ZoneViewItem(
    val id: ZoneId,
    val title: String,
    val conditionBadge: String,
    val summary: String,
    val enabled: Boolean,
    val active: Boolean,
    val distanceMeters: Int?,
    val radiusMeters: Int,
)

data class AutoZoneScreenModel(
    val simulation: Boolean,
    val editingLocked: Boolean,
    val headerBadge: String,
    val currentTimeLabel: String,
    val title: String,
    val statusHeadline: String,
    val activeZoneTitle: String?,
    val activeConditionBadge: String?,
    val activeAppliedSummary: List<String>,
    val safetyNotice: String?,
    val lastEventLog: String,
    val hardwareSummaryLine1: String,
    val hardwareSummaryLine2: String,
    val zones: List<ZoneViewItem>,
    val canUndo: Boolean,
    val canRegisterHere: Boolean,
)

class AutoZoneEngine(
    val simulation: Boolean = false,
    initialZones: List<GeoZone> = defaultZones(),
) {
    private val zoneMap = LinkedHashMap<ZoneId, GeoZone>().apply {
        initialZones.forEach { put(it.id, it) }
    }

    var state: VehicleState = if (simulation) {
        VehicleState(
            etcsVolume = 3,
            hvacRecirculation = false,
            hvacAirCleaner = false,
            hvacFanSpeed = 4,
            ambientBrightness = 80,
            driverWindowPos = 0,
            rearWindowLock = false,
            wiperActive = false,
            gear = "PARK",
            uxRestricted = false,
            latitude = 37.496500,
            longitude = 127.026000,
            currentHour = 23,
        )
    } else {
        VehicleState()
    }
        private set

    var activeSession: ActiveRoutineSession? = null
        private set

    var lastEventLog: String = "대기 중 · 등록된 위치 반경과 시간대 조건이 맞으면 차량 설정을 자동 전환합니다"
        private set

    val isEditingLocked: Boolean
        get() = state.uxRestricted == true

    fun zones(): List<GeoZone> = zoneMap.values.toList()

    fun updateVehicleState(transform: (VehicleState) -> VehicleState) {
        state = transform(state)
    }

    /**
     * 시각(currentHour) 또는 기어(gear) 변경 시 호출하여:
     * 1) 활성 세션의 시간대 종료 또는 P단 정차 해제(예: 오피스 마이부스 중 D단 출발) 시 즉시 원상복구
     * 2) 반경 안에 이미 들어와 있는 상태에서 시간대·정차 조건이 충족되는 순간 자동 실행
     */
    fun onContextChanged(): List<HardwareCommand> {
        val curActive = activeSession
        if (curActive != null) {
            val z = zoneMap[curActive.zone.id] ?: curActive.zone
            if (z.requireParkGear && state.gear != null && state.gear != "PARK") {
                return exitActiveZone("주행 변속(${state.gear}) 감지로 즉시 복원")
            }
            if (z.timeWindow != null && !z.timeWindow.matches(state.currentHour)) {
                return exitActiveZone("설정 시간대 종료(${padHour(state.currentHour)})로 자동 복원")
            }
            return emptyList()
        }

        val lat = state.latitude
        val lng = state.longitude
        if (lat != null && lng != null) {
            return evaluateCandidateAt(lat, lng, "위치 + 시간·기어 조건 충족")
        }
        return emptyList()
    }

    fun toggleZone(id: ZoneId): List<HardwareCommand> {
        if (isEditingLocked) {
            lastEventLog = "주행 중에는 루틴 편집이 잠겨 있습니다 (정차 후 변경 가능)"
            return emptyList()
        }
        val cur = zoneMap[id] ?: return emptyList()
        val nextEnabled = !cur.enabled
        zoneMap[id] = cur.copy(enabled = nextEnabled)
        if (!nextEnabled && activeSession?.zone?.id == id) {
            return exitActiveZone("루틴 비활성화로 즉시 복원")
        }
        lastEventLog = "${cur.title}: ${if (nextEnabled) "자동 실행 켜짐" else "자동 실행 꺼짐"}"
        return emptyList()
    }

    fun registerCurrentLocationToZone(id: ZoneId): Boolean {
        if (isEditingLocked) {
            lastEventLog = "주행 중에는 새 위치 등록이 제한됩니다"
            return false
        }
        val lat = state.latitude
        val lng = state.longitude
        if (lat == null || lng == null) {
            lastEventLog = "현재 차량 GPS 좌표를 수신한 뒤 등록할 수 있습니다"
            return false
        }
        val cur = zoneMap[id] ?: return false
        zoneMap[id] = cur.copy(latitude = lat, longitude = lng, enabled = true)
        lastEventLog = "${cur.title} 중심 좌표를 현재 위치(${formatCoord(lat)}, ${formatCoord(lng)})로 저장했습니다"
        return true
    }

    fun onLocationUpdated(latitude: Double, longitude: Double): List<HardwareCommand> {
        state = state.copy(latitude = latitude, longitude = longitude)

        val currentActive = activeSession
        if (currentActive != null) {
            val z = zoneMap[currentActive.zone.id] ?: currentActive.zone
            val d = distanceMeters(latitude, longitude, z.latitude, z.longitude)
            if (d > z.radiusMeters + EXIT_HYSTERESIS_METERS) {
                return exitActiveZone("GPS 반경 이탈 (${d.roundToInt()}m)")
            }
            return emptyList()
        }

        return evaluateCandidateAt(latitude, longitude, null)
    }

    private fun evaluateCandidateAt(latitude: Double, longitude: Double, customSource: String?): List<HardwareCommand> {
        val inRange = zoneMap.values
            .filter { it.enabled }
            .map { it to distanceMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (z, dist) -> dist <= z.radiusMeters }
            .sortedBy { (_, dist) -> dist }

        if (inRange.isEmpty()) return emptyList()

        for ((z, dist) in inRange) {
            val blockReason = checkScheduleOrGearBlock(z)
            if (blockReason == null) {
                val src = customSource ?: "GPS 반경 진입 (${dist.roundToInt()}m · ${padHour(state.currentHour)})"
                return enterZone(z.id, src)
            } else {
                lastEventLog = "${z.title} 보류: $blockReason"
            }
        }
        return emptyList()
    }

    private fun checkScheduleOrGearBlock(zone: GeoZone): String? {
        val tw = zone.timeWindow
        if (tw != null && !tw.matches(state.currentHour)) {
            return "현재 시각(${padHour(state.currentHour)})이 설정 시간대(${tw.label()}) 밖입니다"
        }
        if (zone.requireParkGear && state.gear != "PARK") {
            return "반경 진입 확인 · P단 정차 시 자동 실행됩니다"
        }
        return null
    }

    fun onSdkProximityTransition(id: ZoneId, isEnter: Boolean): List<HardwareCommand> {
        return if (isEnter) {
            if (activeSession?.zone?.id == id) emptyList()
            else enterZone(id, "FusedLocation 지오펜스 진입 (${padHour(state.currentHour)})")
        } else {
            if (activeSession?.zone?.id == id) exitActiveZone("FusedLocation 지오펜스 이탈")
            else emptyList()
        }
    }

    fun enterZone(id: ZoneId, triggerSource: String, bypassConditionCheck: Boolean = false): List<HardwareCommand> {
        val zone = zoneMap[id] ?: return emptyList()
        if (!zone.enabled) return emptyList()

        if (!bypassConditionCheck) {
            val blockReason = checkScheduleOrGearBlock(zone)
            if (blockReason != null) {
                lastEventLog = "${zone.title} 보류: $blockReason"
                return emptyList()
            }
        }

        val restoreFirst = if (activeSession != null && activeSession?.zone?.id != id) {
            buildRestoreCommands(activeSession!!, speak = false)
        } else {
            emptyList()
        }

        val snap = HardwareSnapshot(
            etcsVolume = state.etcsVolume,
            hvacRecirculation = state.hvacRecirculation,
            hvacAirCleaner = state.hvacAirCleaner,
            hvacFanSpeed = state.hvacFanSpeed,
            ambientBrightness = state.ambientBrightness,
            driverWindowPos = state.driverWindowPos,
            rearWindowLock = state.rearWindowLock,
        )

        val commands = mutableListOf<HardwareCommand>()
        val changed = linkedSetOf<ChangedField>()
        var safetySkip: String? = null

        zone.targetEtcsVolume?.let { target ->
            val curEtcs = state.etcsVolume
            if (curEtcs == null || curEtcs >= 0) {
                commands += HardwareCommand.SetEtcsVolume(target)
                changed += ChangedField.ETCS_VOLUME
            }
        }
        zone.targetRecirculation?.let { target ->
            commands += HardwareCommand.SetHvacRecirculation(target)
            changed += ChangedField.HVAC_RECIRCULATION
        }
        zone.targetAirCleaner?.let { target ->
            commands += HardwareCommand.SetHvacAirCleaner(target)
            changed += ChangedField.HVAC_AIR_CLEANER
        }
        zone.targetAmbientBrightness?.let { target ->
            commands += HardwareCommand.SetAmbientBrightness(target)
            changed += ChangedField.AMBIENT_BRIGHTNESS
        }
        zone.targetDriverWindowPos?.let { target ->
            val curWin = state.driverWindowPos ?: 0
            if (target > curWin && state.wiperActive == true) {
                safetySkip = "우천 감지(와이퍼 작동 중)로 운전석 창문 자동 개방을 보류했습니다"
            } else {
                commands += HardwareCommand.SetDriverWindowPos(target)
                changed += ChangedField.DRIVER_WINDOW_POS
            }
        }
        zone.targetFanSpeed?.let { target ->
            commands += HardwareCommand.SetHvacFanSpeed(target)
            changed += ChangedField.HVAC_FAN_SPEED
        }
        zone.targetRearWindowLock?.let { target ->
            commands += HardwareCommand.SetRearWindowLock(target)
            changed += ChangedField.REAR_WINDOW_LOCK
        }

        val appliedLabels = commands.map { it.label }
        val ttsText = if (safetySkip != null) {
            "${zone.title} 진입. 비가 오고 있어 창문 자동 개방은 건너뛰고 ${appliedLabels.joinToString(", ")} 설정만 적용했습니다."
        } else {
            "${zone.title} 활성화. ${appliedLabels.joinToString(", ")} 설정을 자동 적용했습니다."
        }
        commands += HardwareCommand.SpeakTts(ttsText)

        if (simulation) {
            applyCommandsToSimulationState(commands)
        }

        activeSession = ActiveRoutineSession(
            zone = zone,
            triggerSource = triggerSource,
            snapshot = snap,
            appliedLabels = appliedLabels,
            changedFields = changed,
            safetySkippedNote = safetySkip,
        )
        lastEventLog = "${zone.title} 실행 완료 ($triggerSource)"
        return restoreFirst + commands
    }

    fun undoActiveZone(): List<HardwareCommand> = exitActiveZone("운전자 원터치 되돌리기")

    fun exitActiveZone(reason: String): List<HardwareCommand> {
        val session = activeSession ?: return emptyList()
        val restoreCmds = buildRestoreCommands(session, speak = true, reason = reason)
        if (simulation) {
            applyCommandsToSimulationState(restoreCmds)
        }
        activeSession = null
        lastEventLog = "${session.zone.title} 종료 · 진입 전 설정 복원 완료 ($reason)"
        return restoreCmds
    }

    private fun buildRestoreCommands(
        session: ActiveRoutineSession,
        speak: Boolean,
        reason: String = "",
    ): List<HardwareCommand> {
        val cmds = mutableListOf<HardwareCommand>()
        val snap = session.snapshot
        if (ChangedField.DRIVER_WINDOW_POS in session.changedFields) {
            cmds += HardwareCommand.SetDriverWindowPos(snap.driverWindowPos ?: 0)
        }
        if (ChangedField.ETCS_VOLUME in session.changedFields) {
            cmds += HardwareCommand.SetEtcsVolume(snap.etcsVolume ?: 3)
        }
        if (ChangedField.HVAC_RECIRCULATION in session.changedFields) {
            cmds += HardwareCommand.SetHvacRecirculation(snap.hvacRecirculation ?: false)
        }
        if (ChangedField.HVAC_AIR_CLEANER in session.changedFields) {
            cmds += HardwareCommand.SetHvacAirCleaner(snap.hvacAirCleaner ?: false)
        }
        if (ChangedField.HVAC_FAN_SPEED in session.changedFields) {
            cmds += HardwareCommand.SetHvacFanSpeed(snap.hvacFanSpeed ?: 3)
        }
        if (ChangedField.AMBIENT_BRIGHTNESS in session.changedFields) {
            cmds += HardwareCommand.SetAmbientBrightness(snap.ambientBrightness ?: 80)
        }
        if (ChangedField.REAR_WINDOW_LOCK in session.changedFields) {
            cmds += HardwareCommand.SetRearWindowLock(snap.rearWindowLock ?: false)
        }
        if (speak) {
            val msg = if (reason.contains("원터치")) {
                "원래 차량 설정으로 즉시 되돌렸습니다."
            } else {
                "${session.zone.title} 조건이 해제되어 진입 전 차량 설정으로 복원했습니다."
            }
            cmds += HardwareCommand.SpeakTts(msg)
        }
        return cmds
    }

    private fun applyCommandsToSimulationState(commands: List<HardwareCommand>) {
        var s = state
        for (c in commands) {
            s = when (c) {
                is HardwareCommand.SetEtcsVolume -> s.copy(etcsVolume = c.volume)
                is HardwareCommand.SetHvacRecirculation -> s.copy(hvacRecirculation = c.enabled)
                is HardwareCommand.SetHvacAirCleaner -> s.copy(hvacAirCleaner = c.enabled)
                is HardwareCommand.SetHvacFanSpeed -> s.copy(hvacFanSpeed = c.speed)
                is HardwareCommand.SetAmbientBrightness -> s.copy(ambientBrightness = c.brightness)
                is HardwareCommand.SetDriverWindowPos -> s.copy(driverWindowPos = c.percent)
                is HardwareCommand.SetRearWindowLock -> s.copy(rearWindowLock = c.locked)
                is HardwareCommand.SpeakTts -> s
            }
        }
        state = s
    }

    fun screenModel(writeFeedbackSummary: String? = null): AutoZoneScreenModel {
        val active = activeSession
        val lat = state.latitude
        val lng = state.longitude

        val zoneItems = zoneMap.values.map { z ->
            val dist = if (lat != null && lng != null) {
                distanceMeters(lat, lng, z.latitude, z.longitude).roundToInt()
            } else {
                null
            }
            ZoneViewItem(
                id = z.id,
                title = z.title,
                conditionBadge = z.conditionBadge(),
                summary = z.summary,
                enabled = z.enabled,
                active = active?.zone?.id == z.id,
                distanceMeters = dist,
                radiusMeters = z.radiusMeters.roundToInt(),
            )
        }

        val curEtcs = state.etcsVolume
        val etcsText = when {
            curEtcs == null -> "확인 중"
            curEtcs < 0 -> "차종 미지원"
            else -> curEtcs.toString()
        }
        val hwLine1 = buildString {
            append("조건 시각: ${padHour(state.currentHour)}")
            append("  ·  공조: ${when (state.hvacRecirculation) { true -> "내기순환"; false -> "외기유입"; null -> "확인 중" }}")
            append(" (풍량 ${state.hvacFanSpeed?.let { "${it}단" } ?: "-"})")
            append("  ·  공기청정: ${when (state.hvacAirCleaner) { true -> "ON"; false -> "OFF"; null -> "-" }}")
            append("  ·  하이패스: $etcsText")
        }

        val hwLine2 = buildString {
            append("운전석 창문: ${state.driverWindowPos?.let { if (it == 0) "닫힘(0%)" else "열림(${it}%)" } ?: "확인 중"}")
            append("  ·  2열 잠금: ${when (state.rearWindowLock) { true -> "잠금"; false -> "해제"; null -> "-" }}")
            append("  ·  무드등: ${state.ambientBrightness?.let { "${it}%" } ?: "-"}")
            append("  ·  기어: ${state.gear ?: "-"}")
            append("  ·  와이퍼: ${when (state.wiperActive) { true -> "작동(우천)"; false -> "정지"; null -> "-" }}")
        }

        val badge = when {
            simulation -> "시뮬레이션 모드"
            isEditingLocked -> "주행 안전 모드 (편집 잠금)"
            else -> "Pleos SDK 실시간 연동 (정차)"
        }

        val headline = if (active != null) {
            "활성 존: ${active.zone.title} (${active.triggerSource})"
        } else if (isEditingLocked) {
            "주행 중 위치 × 시간대 감시 활성 · 조건 충족 시 무조작 자동 실행됩니다"
        } else {
            "정차 상태 · 위치 반경과 시간대 조건을 조합한 오토존 루틴이 대기 중입니다"
        }

        val eventText = if (!writeFeedbackSummary.isNullOrBlank()) {
            "$lastEventLog  |  $writeFeedbackSummary"
        } else {
            lastEventLog
        }

        return AutoZoneScreenModel(
            simulation = simulation,
            editingLocked = isEditingLocked,
            headerBadge = badge,
            currentTimeLabel = padHour(state.currentHour),
            title = "오토존 AutoZone",
            statusHeadline = headline,
            activeZoneTitle = active?.zone?.title,
            activeConditionBadge = active?.zone?.conditionBadge(),
            activeAppliedSummary = active?.appliedLabels.orEmpty(),
            safetyNotice = active?.safetySkippedNote,
            lastEventLog = eventText,
            hardwareSummaryLine1 = hwLine1,
            hardwareSummaryLine2 = hwLine2,
            zones = zoneItems,
            canUndo = active != null,
            canRegisterHere = !isEditingLocked && lat != null && lng != null,
        )
    }

    companion object {
        const val EXIT_HYSTERESIS_METERS = 15.0

        fun defaultZones(): List<GeoZone> = listOf(
            GeoZone(
                id = ZoneId.NIGHT_PARKING,
                title = "심야 지하주차장 매너존",
                summary = "반경 60m + 심야 진입 → 하이패스 무음 · 내기순환 · 공기청정 · 무드등 10% 감광",
                latitude = 37.497900,
                longitude = 127.027600,
                radiusMeters = 60f,
                timeWindow = TimeWindow(22, 6),
                targetEtcsVolume = 0,
                targetRecirculation = true,
                targetAirCleaner = true,
                targetAmbientBrightness = 10,
            ),
            GeoZone(
                id = ZoneId.DRIVE_THRU,
                title = "드라이브스루 · 주차권 패스존",
                summary = "반경 45m 진입 → 운전석 창문 80% 개방(우천 시 보류) · 주문용 팬 1단 감속",
                latitude = 37.501500,
                longitude = 127.031500,
                radiusMeters = 45f,
                timeWindow = null,
                targetDriverWindowPos = 80,
                targetFanSpeed = 1,
            ),
            GeoZone(
                id = ZoneId.OFFICE_BOOTH,
                title = "점심 오피스 마이부스 (회의·휴식)",
                summary = "반경 70m + 점심 P단 정차 → 창문 닫힘 · 정숙 팬 1단 · 공기청정 · 무드등 30% 집중 조명",
                latitude = 37.503400,
                longitude = 127.033400,
                radiusMeters = 70f,
                timeWindow = TimeWindow(11, 14),
                requireParkGear = true,
                targetDriverWindowPos = 0,
                targetFanSpeed = 1,
                targetAirCleaner = true,
                targetAmbientBrightness = 30,
            ),
            GeoZone(
                id = ZoneId.SCHOOL_PICKUP,
                title = "하교 스쿨존 · 어린이 픽업존",
                summary = "반경 70m + 하교 시간 진입 → 2열 어린이 창문 잠금 · 공기청정 ON · 팬 2단 유지",
                latitude = 37.506800,
                longitude = 127.036800,
                radiusMeters = 70f,
                timeWindow = TimeWindow(13, 19),
                targetRearWindowLock = true,
                targetAirCleaner = true,
                targetFanSpeed = 2,
            ),
        )

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
            return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
        }

        private fun padHour(hour: Int): String {
            val h = ((hour % 24) + 24) % 24
            return if (h < 10) "0$h:00" else "$h:00"
        }

        private fun formatCoord(v: Double): String {
            val scaled = (v * 10_000.0).roundToInt() / 10_000.0
            return scaled.toString()
        }
    }
}
