package autozone.core

private var passed = 0
private var failed = 0

private fun checkCase(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("[PASS] $name")
    } catch (t: Throwable) {
        failed++
        println("[FAIL] $name -> ${t.message}")
    }
}

fun main() {
    checkCase("심야(23시) 지하주차장 진입 시 스냅샷 저장 및 이탈 시 진입 전 값으로 정확히 복원") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState {
            it.copy(currentHour = 23, etcsVolume = 4, hvacRecirculation = false, hvacAirCleaner = false, ambientBrightness = 70)
        }
        val enterCmds = engine.onLocationUpdated(37.497900, 127.027600)
        check(engine.activeSession?.zone?.id == ZoneId.NIGHT_PARKING) { "활성 존 불일치" }
        check(HardwareCommand.SetEtcsVolume(0) in enterCmds) { "하이패스 무음 누락" }
        check(HardwareCommand.SetHvacRecirculation(true) in enterCmds) { "내기순환 누락" }
        check(HardwareCommand.SetAmbientBrightness(10) in enterCmds) { "무드등 감광 누락" }
        check(engine.state.etcsVolume == 0 && engine.state.ambientBrightness == 10) { "상태 반영 실패" }

        val exitCmds = engine.onLocationUpdated(37.490000, 127.020000)
        check(engine.activeSession == null) { "존 종료 실패" }
        check(HardwareCommand.SetEtcsVolume(4) in exitCmds) { "원래 하이패스 음량(4) 복원 실패" }
        check(HardwareCommand.SetHvacRecirculation(false) in exitCmds) { "외기유입 복원 실패" }
        check(HardwareCommand.SetAmbientBrightness(70) in exitCmds) { "원래 무드등(70) 복원 실패" }
    }

    checkCase("자정 넘김 심야 시간대(22:00~06:00) 경계: 낮 14시 진입 시 오작동 보류, 밤 22시·새벽 2시 정상 작동 및 06시 종료") {
        val engine = AutoZoneEngine(simulation = true)
        // 낮 14시 지하주차장 진입 -> 보류되어야 함
        engine.updateVehicleState { it.copy(currentHour = 14, ambientBrightness = 80) }
        val dayCmds = engine.onLocationUpdated(37.497900, 127.027600)
        check(dayCmds.isEmpty() && engine.activeSession == null) { "낮 14시에 심야 매너존이 켜지면 안 됨" }
        check(engine.lastEventLog.contains("보류")) { "시간대 불일치 보류 로그 누락" }

        // 주차장 안에서 밤 22시가 되면 즉시 자동 실행
        engine.updateVehicleState { it.copy(currentHour = 22) }
        val nightCmds = engine.onContextChanged()
        check(nightCmds.isNotEmpty() && engine.activeSession?.zone?.id == ZoneId.NIGHT_PARKING) { "22시 도달 시 자동 실행 실패" }

        // 새벽 2시(자정 넘김)에도 활성 유지
        engine.updateVehicleState { it.copy(currentHour = 2) }
        val dawnCmds = engine.onContextChanged()
        check(dawnCmds.isEmpty() && engine.activeSession?.zone?.id == ZoneId.NIGHT_PARKING) { "새벽 2시에 조기 해제되면 안 됨" }

        // 아침 6시가 되면 시간대 종료로 원래 설정(무드등 80) 자동 복원
        engine.updateVehicleState { it.copy(currentHour = 6) }
        val morningRestore = engine.onContextChanged()
        check(engine.activeSession == null && HardwareCommand.SetAmbientBrightness(80) in morningRestore) { "06시 종료 복원 실패" }
    }

    checkCase("점심 오피스 마이부스(11:00~14:00 · P단 정차): 주행 진입 시 대기하다가 P단 정차 시 가동, D단 출발 시 즉시 복원") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState {
            it.copy(currentHour = 12, gear = "DRIVE", driverWindowPos = 40, hvacFanSpeed = 4, ambientBrightness = 80)
        }
        // 회사 주차장 반경 진입했지만 아직 DRIVE 기어 -> 대기(보류)
        val driveEnter = engine.onLocationUpdated(37.503400, 127.033400)
        check(driveEnter.isEmpty() && engine.activeSession == null) { "주행 기어(DRIVE)에서 마이부스가 켜지면 안 됨" }

        // P단 정차하는 순간 마이부스(창문 닫힘 0%, 팬 1단, 공기청정 ON, 무드등 30%) 자동 실행
        engine.updateVehicleState { it.copy(gear = "PARK") }
        val parkActivate = engine.onContextChanged()
        check(engine.activeSession?.zone?.id == ZoneId.OFFICE_BOOTH) { "P단 정차 시 마이부스 활성화 실패" }
        check(HardwareCommand.SetDriverWindowPos(0) in parkActivate) { "마이부스 창문 닫힘 누락" }
        check(HardwareCommand.SetHvacFanSpeed(1) in parkActivate) { "마이부스 정숙 팬 1단 누락" }
        check(HardwareCommand.SetAmbientBrightness(30) in parkActivate) { "마이부스 무드등 30% 누락" }

        // 회의/휴식 끝내고 D단으로 변속하는 순간 원래 설정(창문 40%, 팬 4단, 무드등 80%)으로 즉시 복원
        engine.updateVehicleState { it.copy(gear = "DRIVE") }
        val driveRestore = engine.onContextChanged()
        check(engine.activeSession == null) { "D단 변속 시 마이부스 자동 해제 실패" }
        check(HardwareCommand.SetDriverWindowPos(40) in driveRestore) { "원래 창문(40%) 복원 실패" }
        check(HardwareCommand.SetHvacFanSpeed(4) in driveRestore) { "원래 팬(4단) 복원 실패" }
        check(HardwareCommand.SetAmbientBrightness(80) in driveRestore) { "원래 무드등(80%) 복원 실패" }
    }

    checkCase("맑은 날 드라이브스루 진입 시 시간 무관(상시) 운전석 창문 80% 개방 및 팬 1단 감속") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState { it.copy(currentHour = 9, driverWindowPos = 0, hvacFanSpeed = 5, wiperActive = false) }
        val cmds = engine.onLocationUpdated(37.501500, 127.031500)
        check(HardwareCommand.SetDriverWindowPos(80) in cmds) { "창문 개방 명령 누락" }
        check(HardwareCommand.SetHvacFanSpeed(1) in cmds) { "팬 1단 감속 누락" }
        check(engine.activeSession?.safetySkippedNote == null) { "불필요한 우천 경고 발생" }
    }

    checkCase("우천(와이퍼 ON) 시 드라이브스루 진입하면 창문 개방만 보류하고 이탈 시에도 창문 미조작") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState { it.copy(driverWindowPos = 0, hvacFanSpeed = 5, wiperActive = true) }
        val enterCmds = engine.onLocationUpdated(37.501500, 127.031500)
        check(enterCmds.none { it is HardwareCommand.SetDriverWindowPos }) { "우천 시 창문이 열리면 안 됨" }
        check(HardwareCommand.SetHvacFanSpeed(1) in enterCmds) { "팬 1단 감속은 실행되어야 함" }
        check(engine.activeSession?.safetySkippedNote?.contains("우천") == true) { "우천 보류 안내 누락" }

        val exitCmds = engine.exitActiveZone("구역 이탈")
        check(exitCmds.none { it is HardwareCommand.SetDriverWindowPos }) { "변경하지 않은 창문을 복원 호출하면 안 됨" }
        check(HardwareCommand.SetHvacFanSpeed(5) in exitCmds) { "원래 팬 5단 복원 누락" }
    }

    checkCase("주행 제한(CarUxRestrictions ON) 중에는 편집·위치 등록이 차단되고 원터치 되돌리기만 허용") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState { it.copy(currentHour = 15) }
        engine.enterZone(ZoneId.SCHOOL_PICKUP, "테스트 진입")
        engine.updateVehicleState { it.copy(uxRestricted = true, gear = "DRIVE") }

        val model = engine.screenModel()
        check(model.editingLocked) { "주행 중 편집 잠금 미작동" }
        check(!model.canRegisterHere) { "주행 중 현 위치 등록 버튼 비활성화 필요" }
        check(model.canUndo) { "주행 중에도 원터치 되돌리기는 가능해야 함" }

        engine.toggleZone(ZoneId.NIGHT_PARKING)
        check(engine.zones().first { it.id == ZoneId.NIGHT_PARKING }.enabled) { "주행 중 토글 변경 차단 실패" }
        check(!engine.registerCurrentLocationToZone(ZoneId.NIGHT_PARKING)) { "주행 중 좌표 등록 차단 실패" }

        val undoCmds = engine.undoActiveZone()
        check(engine.activeSession == null && undoCmds.isNotEmpty()) { "주행 중 원터치 되돌리기 실패" }
    }

    checkCase("경계선 지터 방지(15m 히스테리시스) 및 정차 중 현 위치 원터치 좌표 등록") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState { it.copy(currentHour = 23, uxRestricted = false, latitude = 35.179600, longitude = 129.075600) }
        check(engine.registerCurrentLocationToZone(ZoneId.NIGHT_PARKING)) { "정차 중 현 위치 등록 실패" }

        val enterCmds = engine.onLocationUpdated(35.179600, 129.075600)
        check(enterCmds.isNotEmpty() && engine.activeSession?.zone?.id == ZoneId.NIGHT_PARKING) { "등록 위치 즉시 진입 실패" }

        // 반경 60m 살짝 밖(약 67m 북쪽)에서는 15m 히스테리시스(75m) 이내라 유지되어야 함
        val jitterCmds = engine.onLocationUpdated(35.180200, 129.075600)
        check(jitterCmds.isEmpty() && engine.activeSession != null) { "히스테리시스 구간에서 조기 이탈 발생" }

        // 90m 밖 이동 시 정상 이탈
        val exitCmds = engine.onLocationUpdated(35.180450, 129.075600)
        check(exitCmds.isNotEmpty() && engine.activeSession == null) { "히스테리시스 초과 이탈 실패" }
    }

    checkCase("차종 미지원 프로퍼티(etcsVolume = -1) 감지 시 명령 생성에서 자동 제외") {
        val engine = AutoZoneEngine(simulation = true)
        engine.updateVehicleState { it.copy(currentHour = 23, etcsVolume = -1) }
        val enterCmds = engine.onLocationUpdated(37.497900, 127.027600)
        check(enterCmds.none { it is HardwareCommand.SetEtcsVolume }) { "차종 미지원 시 하이패스 명령이 생략되어야 함" }
        check(HardwareCommand.SetHvacRecirculation(true) in enterCmds) { "지원되는 공조 명령은 정상 생성되어야 함" }
    }

    println("TOTAL: passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
