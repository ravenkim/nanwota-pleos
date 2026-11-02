package ai.nanwota.car

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue

/**
 * 좌석 = 그 좌석의 문. 좌핸들 기준 운전석은 1열 왼쪽.
 * id는 서버·동승자 웹과 같은 이름을 쓴다.
 */
enum class SeatPos(val id: String, val label: String, val seat: SeatValue, val door: DoorValue) {
    DRIVER("DRIVER", "운전석", SeatValue.ROW_1_LEFT, DoorValue.ROW_1_LEFT),
    FRONT_RIGHT("FRONT_RIGHT", "조수석", SeatValue.ROW_1_RIGHT, DoorValue.ROW_1_RIGHT),
    REAR_LEFT("REAR_LEFT", "뒷좌석 왼쪽", SeatValue.ROW_2_LEFT, DoorValue.ROW_2_LEFT),
    REAR_CENTER("REAR_CENTER", "뒷좌석 가운데", SeatValue.ROW_2_CENTER, DoorValue.ROW_2_LEFT),
    REAR_RIGHT("REAR_RIGHT", "뒷좌석 오른쪽", SeatValue.ROW_2_RIGHT, DoorValue.ROW_2_RIGHT);

    val seatArea get() = SeatArea(seat)
    val doorArea get() = DoorArea(door)

    companion object {
        val passengers = listOf(FRONT_RIGHT, REAR_LEFT, REAR_CENTER, REAR_RIGHT)
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}
