package ai.nanwota.car

import ai.nanwota.core.TripMeter
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 운행 하나를 끝까지 책임진다.
 *
 * 탑승: 정차 중 빈 좌석에 사람이 앉음 → 서버에서 그 좌석 QR 발급 → 동승자가 폰으로 찍고 로그인 → 이벤트 폴링으로 탑승 확정
 * 하차: 하차 QR(체크아웃 요청) 또는 "좌석 비움 + 그 좌석 문 열림"이 [ALIGHT_WINDOW_MS] 안에 함께 일어남
 * 정산: [TripMeter]가 주행거리·연비로 구간별 몫을 계산하고, 바뀔 때마다 서버에 올려 동승자 폰에 보인다.
 */
class TripController(context: Context, private val server: ServerClient, private val speaker: Speaker?) : VehicleSignals.Sink {

    data class Rider(val id: String, val name: String)

    data class SeatUi(
        val pos: SeatPos,
        val occupied: Boolean = false,
        val rider: Rider? = null,
        /** 탑승 등록을 기다리는 QR 주소 */
        val checkinUrl: String? = null,
        val amount: Long? = null,
        val distanceKm: Double? = null,
    )

    data class Alighted(val rider: Rider, val seat: SeatPos, val distanceKm: Double, val amount: Long)

    enum class Status { READY, RUNNING, ENDED }

    data class Ui(
        val status: Status = Status.READY,
        val seats: List<SeatUi> = SeatPos.passengers.map { SeatUi(it) },
        val alighted: List<Alighted> = emptyList(),
        val totalKm: Double = 0.0,
        val totalCost: Long = 0,
        val ridersShare: Long = 0,
        val kmPerUnit: Double? = null,
        val efficiencyFromCar: Boolean = false,
        val electric: Boolean = false,
        val unitPrice: Double = 0.0,
        /** 주행 중: QR 같은 설정 화면 금지 */
        val driving: Boolean = false,
        val driverSetupUrl: String? = null,
        val summaryUrl: String? = null,
        val notice: String? = null,
        val unsupported: Set<String> = emptySet(),
    )

    private val prefs = Settings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(Ui(unitPrice = prefs.unitPrice(false)))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var meter: TripMeter? = null
    private var trip: ServerClient.TripHandle? = null
    private var eventSeq = 0
    private val riderSeat = mutableMapOf<String, SeatPos>()
    private val riders = mutableMapOf<String, Rider>()
    private val seatEmptiedAt = mutableMapOf<SeatPos, Long>()
    private val doorOpenedAt = mutableMapOf<SeatPos, Long>()
    private var lastOdometerKm: Double? = null
    private var pushJob: Job? = null
    private var pollJob: Job? = null

    // ---------- 차량 신호 ----------

    override fun onSeat(seat: SeatPos, occupied: Boolean) = onMain {
        val before = seatUi(seat)
        setSeat(seat) { it.copy(occupied = occupied) }
        if (occupied && before.rider == null) {
            if (_ui.value.status != Status.RUNNING) startTrip()
            requestQr(seat)
        } else if (!occupied) {
            seatEmptiedAt[seat] = now()
            if (before.rider == null) setSeat(seat) { it.copy(checkinUrl = null) }
            checkAutoAlight(seat)
        }
    }

    override fun onDoor(seat: SeatPos, open: Boolean) = onMain {
        if (open) {
            doorOpenedAt[seat] = now()
            checkAutoAlight(seat)
        }
    }

    override fun onOdometerKm(km: Double) = onMain {
        lastOdometerKm = km
        meter?.let {
            it.onDistance(km)
            refresh()
        }
    }

    override fun onEfficiency(kmPerUnit: Double, electric: Boolean) = onMain {
        meter?.onEfficiency(kmPerUnit)
        _ui.update {
            it.copy(
                kmPerUnit = kmPerUnit,
                efficiencyFromCar = true,
                electric = electric,
                unitPrice = if (it.status == Status.RUNNING) it.unitPrice else prefs.unitPrice(electric),
            )
        }
    }

    override fun onBrake(pressed: Boolean) = Unit

    override fun onUnsupported(what: String) = onMain {
        _ui.update { it.copy(unsupported = it.unsupported + what.substringBefore(' ')) }
    }

    fun onDrivingChanged(noSetup: Boolean) = onMain {
        _ui.update { it.copy(driving = noSetup) }
    }

    // ---------- 운행 ----------

    fun startTrip() = onMain {
        if (_ui.value.status == Status.RUNNING) return@onMain
        val electric = _ui.value.electric
        val price = prefs.unitPrice(electric)
        val m = TripMeter(unitPrice = price, includeDriver = true, fallbackEfficiency = prefs.fallbackEfficiency(electric))
        _ui.value.kmPerUnit?.let(m::onEfficiency)
        lastOdometerKm?.let(m::onDistance)
        meter = m
        eventSeq = 0
        riders.clear()
        riderSeat.clear()
        _ui.update {
            Ui(
                status = Status.RUNNING,
                seats = it.seats.map { s -> SeatUi(s.pos, occupied = s.occupied) },
                kmPerUnit = it.kmPerUnit ?: prefs.fallbackEfficiency(electric),
                efficiencyFromCar = it.efficiencyFromCar,
                electric = electric,
                unitPrice = price,
                driving = it.driving,
                unsupported = it.unsupported,
            )
        }
        scope.launch {
            try {
                val t = server.startTrip(prefs.vehicleId, if (electric) "ELECTRIC" else "GASOLINE", price, if (electric) "원/kWh" else "원/L")
                prefs.vehicleId = t.vehicleId
                trip = t
                _ui.update { it.copy(driverSetupUrl = t.driverSetupUrl, notice = null) }
                _ui.value.seats.filter { it.occupied && it.rider == null }.forEach { requestQr(it.pos) }
                startPolling()
            } catch (e: Exception) {
                notice("서버에 연결하지 못했어요. 정산은 차에서 계속 기록해요. (${e.message})")
            }
        }
    }

    /** 도착: 남은 동승자를 모두 하차 처리하고 정산을 확정한다. */
    fun endTrip() = onMain {
        val m = meter ?: return@onMain
        for ((id, seat) in riderSeat.toMap()) {
            if (m.isOnBoard(id)) alight(id, seat, announce = false)
        }
        _ui.update { it.copy(status = Status.ENDED) }
        speaker?.say("도착했어요. 정산표를 단톡방에 보낼 수 있어요.")
        pushNow(ended = true)
        pollJob?.cancel()
    }

    fun reset() = onMain {
        meter = null
        trip = null
        pollJob?.cancel()
        _ui.update { Ui(seats = it.seats.map { s -> SeatUi(s.pos, occupied = s.occupied) }, kmPerUnit = it.kmPerUnit, efficiencyFromCar = it.efficiencyFromCar, electric = it.electric, unitPrice = prefs.unitPrice(it.electric), driving = it.driving, unsupported = it.unsupported) }
    }

    private fun requestQr(seat: SeatPos) {
        val t = trip ?: return
        scope.launch {
            runCatching { server.seatQr(t, seat) }
                .onSuccess { qr -> if (seatUi(seat).rider == null && seatUi(seat).occupied) setSeat(seat) { it.copy(checkinUrl = qr.checkinUrl) } }
                .onFailure { notice("QR을 만들지 못했어요: ${it.message}") }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val t = trip ?: break
                runCatching { server.events(t, eventSeq) }
                    .onSuccess { res ->
                        for (ev in res.events) {
                            eventSeq = maxOf(eventSeq, ev.seq)
                            val info = res.riders.firstOrNull { it.id == ev.riderId } ?: continue
                            val seat = SeatPos.of(ev.seat) ?: continue
                            when (ev.type) {
                                "checkin" -> board(Rider(info.id, info.name), seat)
                                "checkout" -> if (meter?.isOnBoard(info.id) == true) alight(info.id, seat, announce = true)
                            }
                        }
                    }
                    .onFailure { Log.w(TAG, "poll: ${it.message}") }
                delay(2_000)
            }
        }
    }

    private fun board(rider: Rider, seat: SeatPos) {
        val m = meter ?: return
        riders[rider.id] = rider
        riderSeat[rider.id] = seat
        m.board(rider.id)
        setSeat(seat) { it.copy(rider = rider, checkinUrl = null) }
        speaker?.say("${rider.name}님 탑승했어요.")
        refresh()
    }

    private fun checkAutoAlight(seat: SeatPos) {
        val s = seatUi(seat)
        val rider = s.rider ?: return
        if (s.occupied) return
        val emptied = seatEmptiedAt[seat] ?: return
        val doorKnown = "door" !in _ui.value.unsupported
        val opened = doorOpenedAt[seat]
        // 좌석이 비고 그 좌석 문이 열렸다(순서 무관). 문 신호가 없는 차는 좌석만으로 판정
        if (!doorKnown || (opened != null && kotlin.math.abs(opened - emptied) <= ALIGHT_WINDOW_MS)) {
            alight(rider.id, seat, announce = true)
        }
    }

    private fun alight(riderId: String, seat: SeatPos, announce: Boolean) {
        val m = meter ?: return
        m.alight(riderId)
        val rider = riders[riderId] ?: return
        val bill = m.bills().firstOrNull { it.riderId == riderId }
        setSeat(seat) { it.copy(rider = null, checkinUrl = null, amount = null, distanceKm = null) }
        if (bill != null) {
            _ui.update { it.copy(alighted = it.alighted + Alighted(rider, seat, bill.distanceKm, bill.amount)) }
            if (announce) speaker?.say("${rider.name}님 ${Format.won(bill.amount)}, 폰으로 보냈어요.")
        }
        trip?.let { t -> scope.launch { runCatching { server.alight(t, riderId) } } }
        refresh()
        pushNow(ended = false)
    }

    // ---------- 화면·서버 반영 ----------

    private fun refresh() {
        val m = meter ?: return
        val bills = m.bills().associateBy { it.riderId }
        val ridersShare = bills.values.sumOf { it.amount }
        _ui.update { ui ->
            ui.copy(
                seats = ui.seats.map { s ->
                    val b = s.rider?.let { bills[it.id] }
                    s.copy(amount = b?.amount, distanceKm = b?.distanceKm)
                },
                totalKm = m.totalKm - (m.segments().firstOrNull()?.fromKm ?: 0.0),
                totalCost = m.totalCost,
                ridersShare = ridersShare,
            )
        }
        schedulePush()
    }

    private fun schedulePush() {
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            delay(3_000)
            push(ended = false)
        }
    }

    private fun pushNow(ended: Boolean) {
        pushJob?.cancel()
        scope.launch { push(ended) }
    }

    private suspend fun push(ended: Boolean) {
        val m = meter ?: return
        val t = trip ?: return
        val start = m.segments().firstOrNull()?.fromKm ?: 0.0
        // 서버·동승자 화면은 출발 지점을 0km로 본다
        val bills = m.bills().map { b ->
            b.copy(segments = b.segments.map { it.copy(fromKm = round1(it.fromKm - start), toKm = round1(it.toKm - start)) })
        }
        runCatching { server.putBills(t, bills, emptyMap(), round1(m.totalKm - start), m.totalCost, ended) }
            .onSuccess { url -> if (ended) _ui.update { it.copy(summaryUrl = url) } }
            .onFailure { Log.w(TAG, "push: ${it.message}") }
    }

    // ---------- 시연 패널 (에뮬레이터에서 주입할 수 없는 값 대신) ----------

    /** 에뮬레이터 Virtual Console에 연비·트립 거리 카드가 없어 시연에선 여기서 넣는다. */
    fun demoDrive(km: Double, kmPerUnit: Double) {
        onEfficiency(kmPerUnit, _ui.value.electric)
        onOdometerKm((lastOdometerKm ?: 0.0) + km)
    }

    private fun seatUi(seat: SeatPos) = _ui.value.seats.first { it.pos == seat }
    private fun setSeat(seat: SeatPos, f: (SeatUi) -> SeatUi) = _ui.update { ui -> ui.copy(seats = ui.seats.map { if (it.pos == seat) f(it) else it }) }
    private fun notice(text: String) = _ui.update { it.copy(notice = text) }
    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }
    private fun now() = System.currentTimeMillis()
    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    fun release() {
        scope.launch { pollJob?.cancel() }
    }

    private companion object {
        const val TAG = "TripController"
        const val ALIGHT_WINDOW_MS = 90_000L
    }
}
