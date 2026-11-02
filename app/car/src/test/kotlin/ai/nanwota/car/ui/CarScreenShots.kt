package ai.nanwota.car.ui

import ai.nanwota.car.SeatPos
import ai.nanwota.car.TripController.Alighted
import ai.nanwota.car.TripController.Rider
import ai.nanwota.car.TripController.SeatUi
import ai.nanwota.car.TripController.Status
import ai.nanwota.car.TripController.Ui
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/**
 * 시연 흐름의 차 화면 5장 (기획서·영상용). 결과: car/src/test/snapshots/images/
 * 서버 DEMO=1과 같은 운행: 합정 → 강릉 171km, 하늘은 원주(92.4km)에서 하차. 휘발유 1,680원/L.
 * 0~92.4km 14.2km/L 4명, 92.4~171km 13.1km/L 3명(운전자 포함) — TripMeter 식으로 계산한 값.
 */
class CarScreenShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 1920, screenHeight = 1080, xdpi = 240, ydpi = 240,
            density = Density.HIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        showSystemUi = false,
    )

    private val none = Actions({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> })
    private val suyeon = Rider("sy", "수연")
    private val minsu = Rider("ms", "민수")
    private val haneul = Rider("hn", "하늘")
    private val qr = "http://192.168.0.10:8787/r/demo/REAR_LEFT"

    private fun seats(vararg s: SeatUi) = SeatPos.passengers.map { p -> s.firstOrNull { it.pos == p } ?: SeatUi(p) }

    @Test fun s1_boarding() = shot(Ui(
        seats = seats(
            SeatUi(SeatPos.FRONT_RIGHT, occupied = true, rider = suyeon, amount = 0, distanceKm = 0.0),
            SeatUi(SeatPos.REAR_LEFT, occupied = true, checkinUrl = qr),
            SeatUi(SeatPos.REAR_RIGHT, occupied = true, checkinUrl = qr),
        ),
        unitPrice = 1680.0,
    ))

    @Test fun s2_driving_qr_hidden() = shot(Ui(
        status = Status.RUNNING, driving = true,
        seats = seats(
            SeatUi(SeatPos.FRONT_RIGHT, occupied = true, rider = suyeon, amount = 10, distanceKm = 0.3),
            SeatUi(SeatPos.REAR_LEFT, occupied = true, rider = minsu, amount = 10, distanceKm = 0.3),
            SeatUi(SeatPos.REAR_RIGHT, occupied = true),
        ),
        totalKm = 0.3, totalCost = 35, ridersShare = 20, kmPerUnit = 14.2, efficiencyFromCar = true, unitPrice = 1680.0,
    ))

    @Test fun s3_driving() = shot(Ui(
        status = Status.RUNNING, driving = true,
        seats = seats(
            SeatUi(SeatPos.FRONT_RIGHT, occupied = true, rider = suyeon, amount = 1330, distanceKm = 45.0),
            SeatUi(SeatPos.REAR_LEFT, occupied = true, rider = minsu, amount = 1330, distanceKm = 45.0),
            SeatUi(SeatPos.REAR_RIGHT, occupied = true, rider = haneul, amount = 1330, distanceKm = 45.0),
        ),
        totalKm = 45.0, totalCost = 5324, ridersShare = 3990, kmPerUnit = 14.2, efficiencyFromCar = true, unitPrice = 1680.0,
    ))

    @Test fun s4_alighted() = shot(Ui(
        status = Status.RUNNING,
        seats = seats(
            SeatUi(SeatPos.FRONT_RIGHT, occupied = true, rider = suyeon, amount = 3910, distanceKm = 120.0),
            SeatUi(SeatPos.REAR_LEFT, occupied = true, rider = minsu, amount = 3910, distanceKm = 120.0),
        ),
        alighted = listOf(Alighted(haneul, SeatPos.REAR_RIGHT, 92.4, 2730)),
        totalKm = 120.0, totalCost = 14472, ridersShare = 10550, kmPerUnit = 13.9, efficiencyFromCar = true, unitPrice = 1680.0,
    ))

    @Test fun s5_settled() = shot(Ui(
        status = Status.ENDED,
        alighted = listOf(
            Alighted(haneul, SeatPos.REAR_RIGHT, 92.4, 2730),
            Alighted(suyeon, SeatPos.FRONT_RIGHT, 171.0, 6090),
            Alighted(minsu, SeatPos.REAR_LEFT, 171.0, 6090),
        ),
        totalKm = 171.0, totalCost = 21012, ridersShare = 14910, kmPerUnit = 13.7, efficiencyFromCar = true, unitPrice = 1680.0,
        summaryUrl = "http://192.168.0.10:8787/s/demo",
    ))

    private fun shot(ui: Ui) = paparazzi.snapshot { CarScreen(ui, none) }
}
