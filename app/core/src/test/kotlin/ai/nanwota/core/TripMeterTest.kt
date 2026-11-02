package ai.nanwota.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripMeterTest {
    private fun drive(m: TripMeter, fromKm: Double, toKm: Double, step: Double = 0.5) {
        var km = fromKm
        while (km < toKm) {
            km = minOf(km + step, toKm)
            m.onDistance(km)
        }
    }

    @Test
    fun `기획서 예시 - 3명 출발, 30km에서 C 하차, 50km 도착`() {
        // 연비 10km/L, 1,700원/L → 1km당 170원
        val m = TripMeter(unitPrice = 1700.0, roundTo = 1)
        m.onEfficiency(10.0)
        m.onDistance(0.0)
        m.board("B")
        m.board("C")
        drive(m, 0.0, 30.0)
        m.alight("C")
        drive(m, 30.0, 50.0)
        m.alight("B")

        val bills = m.bills().associateBy { it.riderId }
        // 0~30km: 30 × 170 = 5,100원을 3명(운전자 포함)이 → 1,700원
        assertEquals(1700, bills.getValue("C").amount)
        // B: 1,700 + 20 × 170 / 2 = 1,700 + 1,700
        assertEquals(3400, bills.getValue("B").amount)
        assertEquals(30.0, bills.getValue("C").distanceKm, 1e-9)
        assertEquals(50.0, bills.getValue("B").distanceKm, 1e-9)
        assertEquals(8500, m.totalCost)
        assertTrue(bills.values.all { it.final })
    }

    @Test
    fun `운전자 제외면 동승자끼리만 나눈다`() {
        val m = TripMeter(unitPrice = 1000.0, includeDriver = false, roundTo = 1)
        m.onEfficiency(10.0)
        m.onDistance(0.0)
        m.board("A")
        m.board("B")
        drive(m, 0.0, 10.0)
        assertEquals(listOf(500L, 500L), m.bills().map { it.amount })
        assertTrue(m.bills().none { it.final })
    }

    @Test
    fun `효율이 나쁜 구간에 탄 사람이 더 낸다`() {
        val m = TripMeter(unitPrice = 1000.0, roundTo = 1)
        m.onDistance(0.0)
        m.onEfficiency(20.0)
        m.board("고속도로")
        drive(m, 0.0, 20.0) // 1L
        m.alight("고속도로")
        m.onEfficiency(5.0)
        m.board("시내")
        drive(m, 20.0, 40.0) // 4L
        m.alight("시내")
        val bills = m.bills().associateBy { it.riderId }
        assertEquals(500, bills.getValue("고속도로").amount)
        assertEquals(2000, bills.getValue("시내").amount)
    }

    @Test
    fun `트립 거리가 0이 아닌 값에서 시작해도 기준점으로 잡는다`() {
        val m = TripMeter(unitPrice = 1000.0, roundTo = 1)
        m.onEfficiency(10.0)
        m.onDistance(123.4)
        m.board("A")
        drive(m, 123.4, 133.4)
        assertEquals(10.0, m.totalKm - 123.4, 1e-9)
        assertEquals(500, m.bills().single().amount)
    }

    @Test
    fun `청구액은 10원 단위로 반올림`() {
        val m = TripMeter(unitPrice = 1733.0)
        m.onEfficiency(13.0)
        m.onDistance(0.0)
        m.board("A")
        drive(m, 0.0, 7.3)
        assertEquals(0L, m.bills().single().amount % 10)
    }
}
