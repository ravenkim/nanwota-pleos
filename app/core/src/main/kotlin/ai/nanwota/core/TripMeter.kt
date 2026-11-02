package ai.nanwota.core

import kotlin.math.roundToLong

/**
 * 운행 한 번의 구간별 연료 소비와 동승자별 몫을 계산한다.
 *
 * 차량 신호를 그대로 받는다.
 * - [onDistance]: 트립 누적 주행거리(km)
 * - [onEfficiency]: 현재 연비/전비(km per unit — 휘발유·경유는 km/L, 전기는 km/kWh)
 * - [board] / [alight]: 동승자 탑승·하차 (탑승 인원이 바뀌는 지점에서 구간을 끊는다)
 *
 * 주행거리가 늘 때마다 "늘어난 거리 ÷ 그 시점 효율"로 소비량을 적분하므로
 * 오르막·정체 구간처럼 효율이 나쁜 구간에 탄 사람이 그만큼 더 낸다.
 */
class TripMeter(
    /** 단가 (원/L 또는 원/kWh) */
    val unitPrice: Double,
    /** 운전자도 1인분을 내는지 (기본: 포함 — 운전자 몫은 청구하지 않을 뿐 나누는 인원에는 들어감) */
    val includeDriver: Boolean = true,
    /** 효율 신호가 아직 없을 때 쓰는 값 */
    private val fallbackEfficiency: Double = 12.0,
    /** 동승자 청구액 반올림 단위 (원) */
    private val roundTo: Long = 10,
) {
    data class Segment(
        val fromKm: Double,
        var toKm: Double,
        var units: Double,
        val riders: Set<String>,
    ) {
        val distanceKm get() = toKm - fromKm
    }

    data class SegmentShare(val fromKm: Double, val toKm: Double, val sharedBy: Int, val cost: Long)

    data class Bill(
        val riderId: String,
        val distanceKm: Double,
        val amount: Long,
        val segments: List<SegmentShare>,
        /** 하차해서 금액이 확정됐는지 */
        val final: Boolean,
    )

    private val segments = mutableListOf(Segment(0.0, 0.0, 0.0, emptySet()))
    private val onBoard = linkedSetOf<String>()
    private val alighted = mutableSetOf<String>()
    private var lastKm: Double? = null
    private var efficiency: Double? = null

    val totalKm get() = segments.last().toKm
    val totalUnits get() = segments.sumOf { it.units }
    val totalCost get() = (totalUnits * unitPrice).roundToLong()
    val riders: Set<String> get() = onBoard + alighted
    fun isOnBoard(riderId: String) = riderId in onBoard

    fun onEfficiency(kmPerUnit: Double) {
        if (kmPerUnit.isFinite() && kmPerUnit > 0) efficiency = kmPerUnit
    }

    fun onDistance(tripKm: Double) {
        val prev = lastKm
        lastKm = tripKm
        // 첫 값은 기준점. 트립 리셋 등으로 거리가 줄면 기준만 다시 잡는다.
        if (prev == null || tripKm <= prev) {
            if (prev == null && segments.size == 1 && segments[0].distanceKm == 0.0) {
                segments[0] = segments[0].copy(fromKm = tripKm, toKm = tripKm)
            }
            return
        }
        val delta = tripKm - prev
        val seg = segments.last()
        seg.units += delta / (efficiency ?: fallbackEfficiency)
        seg.toKm += delta
    }

    fun board(riderId: String) {
        if (riderId in onBoard || riderId in alighted) return
        cut { onBoard += riderId }
    }

    fun alight(riderId: String) {
        if (riderId !in onBoard) return
        cut {
            onBoard -= riderId
            alighted += riderId
        }
    }

    private fun cut(change: () -> Unit) {
        change()
        val last = segments.last()
        val next = Segment(last.toKm, last.toKm, 0.0, onBoard.toSet())
        // 거리가 0인 구간은 새 구성으로 덮어쓴다 (출발 전 여러 명이 연달아 탈 때)
        if (last.distanceKm == 0.0) segments[segments.lastIndex] = next else segments += next
    }

    fun segments(): List<Segment> = segments.map { it.copy() }

    /** 동승자별 청구서. 운전자 몫은 포함하지 않는다. */
    fun bills(): List<Bill> = riders.map { riderId ->
        val shares = segments.filter { riderId in it.riders && it.distanceKm > 0 }.map { seg ->
            val sharedBy = seg.riders.size + if (includeDriver) 1 else 0
            SegmentShare(seg.fromKm, seg.toKm, sharedBy, (seg.units * unitPrice / sharedBy).roundToLong())
        }
        val raw = shares.sumOf { it.cost }
        Bill(
            riderId = riderId,
            distanceKm = shares.sumOf { it.toKm - it.fromKm },
            amount = roundUnit(raw),
            segments = shares,
            final = riderId in alighted,
        )
    }

    private fun roundUnit(won: Long): Long = ((won + roundTo / 2) / roundTo) * roundTo
}
