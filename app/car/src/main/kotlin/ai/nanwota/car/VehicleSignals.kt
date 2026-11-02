package ai.nanwota.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.util.Log

/**
 * Pleos Vehicle SDK에서 정산에 필요한 신호만 받아 [Sink]로 넘긴다. 전부 조회(CAR_*) 퍼미션.
 *
 * - 좌석 착석 (Seat) → 탑승/하차 판정
 * - 문 열림 (Door) → 하차 확인
 * - 총 주행거리 km (Odometer) → 구간 거리
 * - 연비 L/100km (Fuel) 또는 전비 km/kWh (EvBattery) → 구간 연료 소비
 *
 * 지원하지 않는 신호는 capability로 걸러 [Sink.onUnsupported]로 알린다 (QR 전용 모드, 설정 연비로 대체).
 */
class VehicleSignals(context: Context, private val sink: Sink) {
    interface Sink {
        fun onSeat(seat: SeatPos, occupied: Boolean)
        fun onDoor(seat: SeatPos, open: Boolean)
        fun onOdometerKm(km: Double)
        /** km per unit (km/L 또는 km/kWh) */
        fun onEfficiency(kmPerUnit: Double, electric: Boolean)
        fun onBrake(pressed: Boolean)
        fun onUnsupported(what: String)
    }

    private val vehicle = Vehicle(context)
    private val seatListeners = mutableMapOf<SeatPos, SeatOccupancyListener>()
    private val doorListeners = mutableMapOf<SeatPos, DoorPositionListener>()
    private var odometer: DistanceDrivenListener? = null
    private var fuel: FuelEconomyListener? = null
    private var ev: EvBatteryEfficiencyListener? = null
    private var brake: BrakePedalStatusListener? = null

    private fun fail(what: String) = { e: Exception ->
        Log.w(TAG, "$what: ${e.message}")
        sink.onUnsupported(what)
    }

    fun start() {
        vehicle.initialize()
        val seat = vehicle.getSeat()
        val door = vehicle.getDoor()

        seat.checkSeatOccupancyCapabilities({ cfg, areas ->
            if (cfg.accessType == AccessType.NONE) return@checkSeatOccupancyCapabilities sink.onUnsupported("seat")
            for (pos in SeatPos.passengers) {
                if (areas.isNotEmpty() && areas.none { it == pos.seatArea }) continue
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                        if (occupancy != null) sink.onSeat(pos, occupancy)
                    }
                    override fun onFailed(e: Exception) = fail("seat ${pos.id}")(e)
                }
                seatListeners[pos] = l
                seat.registerSeatOccupancy(pos.seatArea, l)
            }
        }, fail("seat"))

        door.checkDoorPositionCapabilities({ cfg, bounds ->
            if (cfg.accessType == AccessType.NONE) return@checkDoorPositionCapabilities sink.onUnsupported("door")
            for (pos in SeatPos.passengers.distinctBy { it.door }) {
                val closed = bounds[pos.doorArea]?.minValue ?: 0
                val l = object : DoorPositionListener {
                    override fun onPositionUpdated(pos2: Int?) {
                        if (pos2 != null) SeatPos.passengers.filter { it.door == pos.door }.forEach { sink.onDoor(it, pos2 > closed) }
                    }
                    override fun onFailed(e: Exception) = fail("door ${pos.id}")(e)
                }
                doorListeners[pos] = l
                door.registerDoorPosition(pos.doorArea, l)
            }
        }, fail("door"))

        val odo = vehicle.getOdometer()
        odo.checkCurrentDistanceDrivenCapabilities({ cfg ->
            if (cfg.accessType == AccessType.NONE) return@checkCurrentDistanceDrivenCapabilities sink.onUnsupported("odometer")
            odo.getCurrentDistanceDriven({ v -> v?.let { sink.onOdometerKm(it.toDouble()) } }, fail("odometer"))
            odometer = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) {
                    value?.let { sink.onOdometerKm(it.toDouble()) }
                }
                override fun onFailed(e: Exception) = fail("odometer")(e)
            }.also { odo.registerDistanceDriven(it) }
        }, fail("odometer"))

        // 연료 종류는 API가 없어 연비/전비 capability로 판정한다
        val f = vehicle.getFuel()
        f.checkCurrentFuelEconomyCapabilities({ cfg ->
            if (cfg.accessType == AccessType.NONE) return@checkCurrentFuelEconomyCapabilities
            fuel = object : FuelEconomyListener {
                override fun onFuelEconomyUpdated(efficiency: Float?) {
                    // L/100km → km/L
                    if (efficiency != null && efficiency > 0f) sink.onEfficiency(100.0 / efficiency, electric = false)
                }
                override fun onFailed(e: Exception) = fail("fuel")(e)
            }.also { f.registerCurrentFuelEconomy(it) }
        }, fail("fuel"))

        val b = vehicle.getEvBattery()
        b.checkCurrentEvEfficiencyCapabilities({ cfg ->
            if (cfg.accessType == AccessType.NONE) return@checkCurrentEvEfficiencyCapabilities
            ev = object : EvBatteryEfficiencyListener {
                override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) {
                    if (efficiency != null && efficiency > 0f) sink.onEfficiency(efficiency.toDouble(), electric = true)
                }
                override fun onFailed(e: Exception) = fail("ev")(e)
            }.also { b.registerCurrentEvEfficiency(it) }
        }, fail("ev"))

        val br = vehicle.getBrake()
        brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                status?.let(sink::onBrake)
            }
            override fun onFailed(e: Exception) = fail("brake")(e)
        }.also { br.registerBrakePedalStatus(it) }
    }

    fun stop() {
        runCatching {
            val seat = vehicle.getSeat()
            seatListeners.forEach { (pos, l) -> seat.unregisterSeatOccupancy(pos.seatArea, l) }
            val door = vehicle.getDoor()
            doorListeners.forEach { (pos, l) -> door.unregisterDoorPosition(pos.doorArea, l) }
            odometer?.let { vehicle.getOdometer().unregisterDistanceDriven(it) }
            fuel?.let { vehicle.getFuel().unregisterCurrentFuelEconomy(it) }
            ev?.let { vehicle.getEvBattery().unregisterCurrentEvEfficiency(it) }
            brake?.let { vehicle.getBrake().unregisterBrakePedalStatus(it) }
            vehicle.release()
        }.onFailure { Log.w(TAG, "release: ${it.message}") }
    }

    private companion object {
        const val TAG = "VehicleSignals"
    }
}
