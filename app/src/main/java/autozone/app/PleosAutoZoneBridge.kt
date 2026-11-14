package autozone.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.callback.exception.a as VehicleException
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import android.annotation.SuppressLint
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import autozone.core.AutoZoneEngine
import autozone.core.GeoZone
import autozone.core.VehicleState
import autozone.core.HardwareCommand
import autozone.core.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class PleosAutoZoneBridge(
    private val context: Context,
    private val engine: AutoZoneEngine,
    private val onStateUpdated: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val ioThread = HandlerThread("AutoZonePleosIo").apply { start() }
    private val io = Handler(ioThread.looper)
    private val vehicle = Vehicle(context)
    private val fusedLocation = FusedLocation(context)

    // checkXXXCapabilities로 차량 VHAL이 실제 지원하는 Area를 동적으로 치환
    @Volatile private var recircArea: SeatArea? = null
    @Volatile private var airCleanerArea: SeatArea? = null
    @Volatile private var fanArea: SeatArea? = null
    @Volatile private var ambientArea: SeatArea? = null
    @Volatile private var driverWindow: WindowArea = WindowArea(WindowValue.ROW_1_LEFT)
    @Volatile private var winMin: Int = 0
    @Volatile private var winMax: Int = 100
    @Volatile private var ambMin: Int = 0
    @Volatile private var ambMax: Int = 100
    @Volatile private var rearWindow: WindowArea = WindowArea(WindowValue.ROW_2_LEFT)
    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    @Volatile private var etcsSupported: Boolean = false
    @Volatile private var capabilitiesChecked: Boolean = false

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var locationManager: LocationManager? = null
    private var pleosTts: TextToSpeech? = null
    @Volatile private var pleosTtsReady = false
    private var androidTts: android.speech.tts.TextToSpeech? = null
    @Volatile private var androidTtsReady = false
    @Volatile private var running = false

    private val alertIdToZone = ConcurrentHashMap<String, ZoneId>()
    // Bumped on every geofence refresh; callbacks from older registrations are dropped.
    @Volatile private var geofenceGeneration = 0
    private var okWrites = 0
    private var skippedUnsupported = 0
    private var fallbackWrites = 0
    private var lastFallbackCode: String? = null
    var overrideHour: Int? = null
    var lastSpokenTts: String? = null
        private set

    fun syncHourAndEvaluate(fixedHour: Int? = overrideHour) {
        overrideHour = fixedHour
        val h = fixedHour ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val prev = engine.state.currentHour
        engine.updateVehicleState { it.copy(currentHour = h) }
        if (prev != h || fixedHour != null) {
            val cmds = engine.onContextChanged()
            executeCommands(cmds)
            onStateUpdated()
        }
    }

    fun writeFeedbackSummary(): String? {
        if (okWrites == 0 && fallbackWrites == 0 && skippedUnsupported == 0) return null
        return buildString {
            append("SDK 제어 성공 ${okWrites}건")
            if (skippedUnsupported > 0) append(" · 차종 미지원 항목 자동 생략 ${skippedUnsupported}건")
            if (fallbackWrites > 0) append(" · 제한 ${fallbackWrites}건(${lastFallbackCode ?: "권한"})")
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (!capabilitiesChecked) {
                discoverCapabilities()
            }
            postMain { syncHourAndEvaluate() }
            pollVehicleState()
            pollUxRestrictions()
            if (running) {
                io.postDelayed(this, 1_500L)
            }
        }
    }

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            postMain {
                val cmds = engine.onLocationUpdated(data.latitude, data.longitude)
                if (cmds.isNotEmpty()) {
                    Log.i(TAG, "FusedLocation triggered ${cmds.size} cmds at (${data.latitude}, ${data.longitude})")
                }
                executeCommands(cmds)
                onStateUpdated()
            }
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "FusedLocation onError: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val androidLocListener = LocationListener { loc: Location ->
        postMain {
            val cmds = engine.onLocationUpdated(loc.latitude, loc.longitude)
            if (cmds.isNotEmpty()) {
                Log.i(TAG, "LocationManager triggered ${cmds.size} cmds at (${loc.latitude}, ${loc.longitude})")
            }
            executeCommands(cmds)
            onStateUpdated()
        }
    }

    fun start() {
        if (running) return
        running = true
        postIo {
            runCatching { vehicle.initialize() }
                .onFailure { Log.w(TAG, "Vehicle init failed: ${it.message}") }
            startUxRestrictions()
            startLocationSources()
            startTts()
            if (running) {
                io.postDelayed({ if (running) discoverCapabilities() }, 350L)
                io.postDelayed(pollRunnable, 900L)
            }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        io.removeCallbacks(pollRunnable)
        io.post {
            runCatching { uxManager?.unregisterListener() }
            runCatching { car?.disconnect() }
            runCatching { locationManager?.removeUpdates(androidLocListener) }
            runCatching {
                alertIdToZone.keys.forEach { fusedLocation.removeProximityAlert(it) }
                fusedLocation.unregisterFusedLocationCallback(fusedListener)
            }
            runCatching { fusedLocation.release() }
            runCatching { pleosTts?.release() }
            runCatching { androidTts?.shutdown() }
            runCatching { vehicle.release() }
            runCatching { ioThread.quitSafely() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverCapabilities() {
        val hvac = vehicle.getHVAC()
        val light = vehicle.getLight()
        val window = vehicle.getWindow()
        val toll = vehicle.getToll()

        hvac.checkHvacRecirculationCapabilities(
            { areas ->
                recircArea = pickDriverOrFirstSeat(areas)
                capabilitiesChecked = true
                Log.i(TAG, "CAP recircArea=${recircArea?.values?.contentToString()}")
            },
            { Log.d(TAG, "CAP recirc unsupported: ${it.message}") },
        )
        hvac.checkHvacAirCleanerCapabilities(
            { areas ->
                airCleanerArea = pickDriverOrFirstSeat(areas)
                Log.i(TAG, "CAP airCleanerArea=${airCleanerArea?.values?.contentToString()}")
            },
            { Log.d(TAG, "CAP airCleaner unsupported: ${it.message}") },
        )
        hvac.checkHvacFanSpeedCapabilities(
            { map ->
                fanArea = pickDriverOrFirstSeat(map.keys.toList())
                capabilitiesChecked = true
                Log.i(TAG, "CAP fanArea=${fanArea?.values?.contentToString()} bounds=${map[fanArea]}")
            },
            { Log.d(TAG, "CAP fanSpeed unsupported: ${it.message}") },
        )
        light.checkAmbientLightBrightnessCapabilities(
            { cfg, map ->
                ambientArea = pickDriverOrFirstSeat(map.keys.toList())
                map[ambientArea]?.let { b ->
                    b.minValue?.let { ambMin = it }
                    b.maxValue?.let { if (it > ambMin) ambMax = it }
                }
                Log.i(TAG, "CAP ambientArea=${ambientArea?.values?.contentToString()} access=${cfg.accessType} bounds=$ambMin..$ambMax")
            },
            { Log.d(TAG, "CAP ambient unsupported: ${it.message}") },
        )
                window.checkWindowPositionCapabilities(
            { map ->
                map.keys.firstOrNull { WindowValue.ROW_1_LEFT in it.values }?.let { driverWindow = it }
                map[driverWindow]?.let { b ->
                    b.minValue?.let { winMin = it }
                    b.maxValue?.let { if (it > 0) winMax = it }
                }
                Log.i(TAG, "CAP driverWindow=${driverWindow.values.contentToString()} bounds=$winMin..$winMax")
            },
            { Log.d(TAG, "CAP winPos err: ${it.message}") },
        )
        window.checkWindowLockCapabilities(
            { areas ->
                areas.firstOrNull { WindowValue.ROW_2_LEFT in it.values }?.let { rearWindow = it }
                    ?: areas.firstOrNull()?.let { rearWindow = it }
                Log.i(TAG, "CAP rearWindowLock=${rearWindow.values.contentToString()}")
            },
            { Log.d(TAG, "CAP winLock err: ${it.message}") },
        )
        toll.checkEtcsVoiceGuidanceVolumeCapabilities(
            { cfg, bounds ->
                etcsSupported = true
                Log.i(TAG, "CAP etcsSupported=true access=${cfg.accessType} bounds=$bounds")
            },
            {
                etcsSupported = false
                postMain {
                    engine.updateVehicleState { st -> if (st.etcsVolume == null) st.copy(etcsVolume = -1) else st }
                    onStateUpdated()
                }
                Log.i(TAG, "CAP etcsSupported=false (${it.message})")
            },
        )
    }

    private fun pickDriverOrFirstSeat(areas: List<SeatArea>): SeatArea? =
        areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()

    fun refreshGeofences() {
        if (!running) return
        val enabledZones = engine.zones().filter { it.enabled }
        postIo {
            runCatching {
                geofenceGeneration++
                alertIdToZone.keys().toList().forEach { id ->
                    runCatching { fusedLocation.removeProximityAlert(id) }
                }
                alertIdToZone.clear()
                enabledZones.forEach { registerProximityForZone(it) }
            }.onFailure {
                Log.w(TAG, "refreshGeofences skipped: ${it.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationSources() {
        val initialZones = engine.zones().filter { it.enabled }
        runCatching {
            val supported = fusedLocation.isSupported()
            Log.i(TAG, "FusedLocation.isSupported=$supported")
            fusedLocation.initialize()
            fusedLocation.getLastKnownLocation(fusedListener)
            fusedLocation.registerFusedLocationCallback(fusedListener)
            initialZones.forEach { registerProximityForZone(it) }
        }.onFailure {
            Log.w(TAG, "Pleos FusedLocation setup failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager = lm
            if (lm != null) {
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
                    if (runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) {
                        runCatching { lm.requestLocationUpdates(provider, 500L, 0f, androidLocListener, ioThread.looper) }
                        // Ignore cached fixes older than 2 minutes; they may be from a different place.
                        runCatching { lm.getLastKnownLocation(provider) }?.getOrNull()
                            ?.takeIf { System.currentTimeMillis() - it.time <= 120_000L }
                            ?.let { last ->
                            postMain {
                                val cmds = engine.onLocationUpdated(last.latitude, last.longitude)
                                executeCommands(cmds)
                                onStateUpdated()
                            }
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Android LocationManager fallback failed: ${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerProximityForZone(zone: GeoZone) {
        val req = ProximityAlertRequest(
            zone.latitude,
            zone.longitude,
            zone.radiusMeters,
            setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
        )
        val generation = geofenceGeneration
        fusedLocation.addProximityAlert(req, object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                postMain {
                    if (generation != geofenceGeneration) {
                        // Registered after a refresh replaced it (e.g. new location saved): drop the stale alert.
                        postIo { runCatching { fusedLocation.removeProximityAlert(alertId) } }
                        return@postMain
                    }
                    alertIdToZone[alertId] = zone.id
                    Log.i(TAG, "ProximityAlert registered: zone=${zone.id} alertId=$alertId")
                }
            }

            override fun onTransition(event: ProximityAlertEvent) {
                postMain {
                    if (generation != geofenceGeneration) return@postMain
                    val isEnter = event.transition == ProximityTransition.ENTER
                    Log.i(TAG, "ProximityAlert transition: zone=${zone.id} transition=${event.transition}")
                    val cmds = engine.onSdkProximityTransition(zone.id, isEnter)
                    executeCommands(cmds)
                    onStateUpdated()
                }
            }

            override fun onError(e: Exception) {
                Log.w(TAG, "ProximityAlert error (${zone.id}): ${e.javaClass.simpleName}: ${e.message}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startTts() {
        runCatching {
            val tts = TextToSpeech(context, Mode.ON_DEVICE)
            pleosTts = tts
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    pleosTtsReady = true
                    Log.i(TAG, "Pleos TextToSpeech(ON_DEVICE) initialized")
                }

                override fun onFailure(errorType: ErrorType) {
                    pleosTtsReady = false
                    Log.w(TAG, "Pleos TextToSpeech init failure: $errorType")
                }
            })
        }.onFailure {
            Log.w(TAG, "Pleos TextToSpeech unavailable: ${it.message}")
        }

        runCatching {
            androidTts = android.speech.tts.TextToSpeech(context) { status ->
                androidTtsReady = (status == android.speech.tts.TextToSpeech.SUCCESS)
            }
        }
    }

    private fun startUxRestrictions() {
        try {
            val c = Car.createCar(context).also { car = it }
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = m
            m.registerListener { r ->
                postMain {
                    Log.i(TAG, "CarUx changed: do=${r.isRequiresDistractionOptimization} active=${r.activeRestrictions}")
                    engine.updateVehicleState { it.copy(uxRestricted = r.isRequiresDistractionOptimization) }
                    onStateUpdated()
                }
            }
            pollUxRestrictions()
        } catch (e: Exception) {
            Log.w(TAG, "CarUx init failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pollUxRestrictions() {
        runCatching {
            val r = if (car?.isConnected == true) uxManager?.currentCarUxRestrictions else null
            if (r != null) {
                val restricted = r.isRequiresDistractionOptimization
                postMain {
                    if (engine.state.uxRestricted != restricted) {
                        engine.updateVehicleState { it.copy(uxRestricted = restricted) }
                        onStateUpdated()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollOtherVehicleState() {
        val toll = vehicle.getToll()
        val hvac = vehicle.getHVAC()
        val window = vehicle.getWindow()
        val wiper = vehicle.getWiper()

        if (etcsSupported) {
            toll.getCurrentEtcsVoiceGuidanceVolume(
                { v -> postMain { engine.updateVehicleState { it.copy(etcsVolume = v) }; onStateUpdated() } },
                { logReadErr("ETCS", it) },
            )
        }
        recircArea?.let { area ->
            hvac.getHvacRecirculationState(
                area,
                { v -> postMain { engine.updateVehicleState { it.copy(hvacRecirculation = v) }; onStateUpdated() } },
                { logReadErr("Recirc", it) },
            )
        }
        window.getCurrentWindowLockState(
            rearWindow,
            { v -> postMain { engine.updateVehicleState { it.copy(rearWindowLock = v) }; onStateUpdated() } },
            { logReadErr("WinLock", it) },
        )
        wiper.getCurrentWiperState(
            frontWindshield,
            { st ->
                val active = st == WiperState.ON || st == WiperState.SERVICE
                postMain { engine.updateVehicleState { it.copy(wiperActive = active) }; onStateUpdated() }
            },
            { logReadErr("Wiper", it) },
        )
    }

    // Serialize observations so new-gear hardware cannot overwrite the snapshot
    // before that gear transition is evaluated on the main thread.
    private var hardwarePollInFlight = false

    @SuppressLint("MissingPermission")
    private fun readGear(onResult: (String?) -> Unit) {
        vehicle.getGear().getCurrentGearSelectionState(
            { gear ->
                val name = when (gear) {
                    VehicleGear.GEAR_PARK -> "PARK"
                    VehicleGear.GEAR_REVERSE -> "REVERSE"
                    VehicleGear.GEAR_NEUTRAL -> "NEUTRAL"
                    null, VehicleGear.GEAR_UNKNOWN -> null
                    else -> "DRIVE"
                }
                Log.i(TAG, "READBACK gear=$name")
                postMain { onResult(name) }
            },
            { error ->
                logReadErr("Gear", error)
                postMain { onResult(null) }
            },
        )
    }

    private fun observeGear(name: String?) {
        val previous = engine.state.gear
        engine.updateVehicleState { it.copy(gear = name) }
        if (name != null && previous != name) {
            executeCommands(engine.onContextChanged())
        }
        onStateUpdated()
    }

    @SuppressLint("MissingPermission")
    private fun pollVehicleState() {
        pollOtherVehicleState()
        postMain {
            if (hardwarePollInFlight) return@postMain
            hardwarePollInFlight = true
            val timeout = Runnable { hardwarePollInFlight = false }
            main.postDelayed(timeout, 4_000)
            pollHardwareSample(timeout)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollHardwareSample(timeout: Runnable) {
        // Token stays local to this batch so delayed callbacks cannot publish after timeout.
        val started = android.os.SystemClock.uptimeMillis()
        fun current() = android.os.SystemClock.uptimeMillis() - started < 4_000

        postIo { readGear gearStart@{ initialGear ->
            if (!current()) return@gearStart
            observeGear(initialGear)
            val updates = mutableListOf<(VehicleState) -> VehicleState>()
            var remaining = 4
            fun complete(update: ((VehicleState) -> VehicleState)? = null) {
                postMain {
                    if (!current()) return@postMain
                    if (update != null) updates += update
                    remaining--
                    if (remaining == 0) {
                        postIo {
                            readGear gearEnd@{ finalGear ->
                                if (!current()) return@gearEnd
                                // Use the previous coherent sample when entering PARK.
                                observeGear(finalGear)
                                if (initialGear == finalGear && finalGear != null) {
                                    engine.updateVehicleState { state ->
                                        updates.fold(state) { value, transform -> transform(value) }
                                    }
                                    // A routine may have been waiting for these values before it could snapshot them.
                                    if (engine.activeSession == null) executeCommands(engine.onContextChanged())
                                    onStateUpdated()
                                }
                                main.removeCallbacks(timeout)
                                hardwarePollInFlight = false
                            }
                        }
                    }
                }
            }
            postIo {
                fun failed(label: String, error: Exception) {
                    logReadErr(label, error)
                    complete()
                }
                val hvac = vehicle.getHVAC()
                val airArea = airCleanerArea
                if (airArea == null) complete()
                else hvac.getHvacAirCleanerState(airArea, { value ->
                    Log.i(TAG, "READBACK air=$value")
                    complete { it.copy(hvacAirCleaner = value) }
                }, { failed("AirCleaner", it) })
                val fanReadArea = fanArea
                if (fanReadArea == null) complete()
                else hvac.getHvacFanSpeed(fanReadArea, { value ->
                    Log.i(TAG, "READBACK fan=$value")
                    complete { it.copy(hvacFanSpeed = value) }
                }, { failed("FanSpeed", it) })
                val lightArea = ambientArea
                if (lightArea == null) complete()
                else vehicle.getLight().getAmbientLightBrightness(lightArea, { raw ->
                    val value = rawToPercent(raw, ambMin, ambMax)
                    Log.i(TAG, "READBACK light=$value")
                    complete { it.copy(ambientBrightness = value) }
                }, { failed("Ambient", it) })
                vehicle.getWindow().getCurrentWindowPosition(driverWindow, { raw ->
                    val value = rawToPercent(raw, winMin, winMax)
                    Log.i(TAG, "READBACK window=$value")
                    complete { it.copy(driverWindowPos = value) }
                }, { failed("WinPos", it) })
            }
        } }
    }

    @SuppressLint("MissingPermission")
    fun executeCommands(commands: List<HardwareCommand>) {
        if (commands.isEmpty()) return
        for (cmd in commands) {
            if (cmd is HardwareCommand.SpeakTts) {
                lastSpokenTts = cmd.text
            }
        }
        postIo {
            executeCommandsOnIo(commands)
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeCommandsOnIo(commands: List<HardwareCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is HardwareCommand.SetEtcsVolume -> {
                    if (!etcsSupported) {
                        markUnsupportedSkip("ETCS")
                    } else {
                        vehicle.getToll().setEtcsVoiceGuidanceVolume(
                            cmd.volume,
                            { markWriteOk("ETCS=${cmd.volume}") { it.copy(etcsVolume = cmd.volume) } },
                            { markWriteFallback("ETCS", it) },
                        )
                    }
                }
                is HardwareCommand.SetHvacRecirculation -> {
                    val area = recircArea
                    if (area == null) {
                        markUnsupportedSkip("Recirc")
                    } else {
                        vehicle.getHVAC().setHvacRecirculation(
                            area,
                            cmd.enabled,
                            { _, v -> markWriteOk("Recirc=$v") { it.copy(hvacRecirculation = v) } },
                            { markWriteFallback("Recirc", it) },
                        )
                    }
                }
                is HardwareCommand.SetHvacAirCleaner -> {
                    val area = airCleanerArea
                    if (area == null) {
                        markUnsupportedSkip("AirCleaner")
                    } else {
                        vehicle.getHVAC().setHvacAirCleaner(
                            area,
                            cmd.enabled,
                            { _, v -> markWriteOk("AirCleaner=$v") { it.copy(hvacAirCleaner = v) } },
                            { markWriteFallback("AirCleaner", it) },
                        )
                    }
                }
                is HardwareCommand.SetHvacFanSpeed -> {
                    val area = fanArea
                    if (area == null) {
                        markUnsupportedSkip("FanSpeed")
                    } else {
                        vehicle.getHVAC().setHvacFanSpeed(
                            area,
                            cmd.speed,
                            { _, v -> markWriteOk("FanSpeed=$v") { it.copy(hvacFanSpeed = v) } },
                            { markWriteFallback("FanSpeed", it) },
                        )
                    }
                }
                is HardwareCommand.SetAmbientBrightness -> {
                    val area = ambientArea
                    if (area == null) {
                        markUnsupportedSkip("Ambient")
                    } else {
                        val raw = percentToRaw(cmd.brightness, ambMin, ambMax)
                        vehicle.getLight().setAmbientLightBrightness(
                            area,
                            raw,
                            { _, v ->
                                val pct = rawToPercent(v, ambMin, ambMax)
                                markWriteOk("Ambient=${pct}% (raw=$v)") { it.copy(ambientBrightness = pct) }
                            },
                            { markWriteFallback("Ambient", it) },
                        )
                    }
                }
                is HardwareCommand.SetDriverWindowPos -> {
                    val raw = percentToRaw(cmd.percent, winMin, winMax)
                    vehicle.getWindow().setWindowPosition(
                        driverWindow,
                        raw,
                        { _, v ->
                            val pct = rawToPercent(v, winMin, winMax)
                            markWriteOk("WinPos=${pct}% (raw=$v)") { it.copy(driverWindowPos = pct) }
                        },
                        { markWriteFallback("WinPos", it) },
                    )
                }
                is HardwareCommand.SetRearWindowLock -> {
                    vehicle.getWindow().setWindowLock(
                        rearWindow,
                        cmd.locked,
                        { _, v -> markWriteOk("WinLock=$v") { it.copy(rearWindowLock = v) } },
                        { markWriteFallback("WinLock", it) },
                    )
                }
                is HardwareCommand.SpeakTts -> {
                    lastSpokenTts = cmd.text
                    Log.i(TAG, "[TTS] ${cmd.text}")
                    if (pleosTtsReady) {
                        runCatching { pleosTts?.speak(cmd.text, true) }
                    } else if (androidTtsReady) {
                        runCatching {
                            androidTts?.speak(cmd.text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "autozone_tts")
                        }
                    }
                }
            }
        }
    }

    private fun markWriteOk(tag: String, optimisticUpdate: (autozone.core.VehicleState) -> autozone.core.VehicleState) {
        postMain {
            okWrites++
            engine.updateVehicleState(optimisticUpdate)
            Log.i(TAG, "WRITE_OK $tag (totalOk=$okWrites)")
            onStateUpdated()
        }
    }

    private fun markUnsupportedSkip(domain: String) {
        postMain {
            skippedUnsupported++
            Log.i(TAG, "SKIP_UNSUPPORTED domain=$domain (totalSkipped=$skippedUnsupported)")
            onStateUpdated()
        }
    }

    private fun markWriteFallback(domain: String, e: Exception) {
        val code = (e as? VehicleException)?.a()?.name ?: e.javaClass.simpleName
        postMain {
            fallbackWrites++
            lastFallbackCode = "$domain:$code"
            Log.w(TAG, "WRITE_FALLBACK domain=$domain code=$code msg=${e.message}")
            onStateUpdated()
        }
    }

    private fun logReadErr(domain: String, e: Exception) {
        val code = (e as? VehicleException)?.a()?.name ?: e.javaClass.simpleName
        Log.d(TAG, "READ_ERR domain=$domain code=$code msg=${e.message}")
    }

    private fun percentToRaw(percent: Int, minVal: Int, maxVal: Int): Int {
        val baseMin = minVal.coerceAtLeast(0)
        if (maxVal <= baseMin) return baseMin
        val clamped = percent.coerceIn(0, 100)
        return (baseMin + (maxVal - baseMin) * (clamped / 100.0)).roundToInt().coerceIn(baseMin, maxVal)
    }

    private fun rawToPercent(raw: Int?, minVal: Int, maxVal: Int): Int? {
        if (raw == null) return null
        val baseMin = minVal.coerceAtLeast(0)
        if (maxVal <= baseMin) return 0
        return (((raw - baseMin).toDouble() / (maxVal - baseMin)) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun postIo(block: () -> Unit) {
        if (!running) return
        if (Looper.myLooper() == ioThread.looper) {
            block()
        } else {
            io.post { if (running) block() }
        }
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (running) block()
        } else {
            main.post { if (running) block() }
        }
    }

    companion object {
        private const val TAG = "AutoZone"
    }
}


