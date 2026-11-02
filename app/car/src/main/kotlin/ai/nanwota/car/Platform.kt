package ai.nanwota.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 주행 중 제한. QR 로그인은 모든 지역에서 주행 중 금지(UX_RESTRICTIONS_NO_SETUP)이므로
 * 이 값이 켜져 있으면 QR을 숨긴다. AAOS가 아닌 환경에서는 정차로 본다.
 */
class DrivingState(context: Context, private val onChange: (noSetup: Boolean) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    init {
        runCatching {
            car = Car.createCar(context)
            manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            manager?.let { m ->
                onChange(m.currentCarUxRestrictions.blocksSetup())
                m.registerListener { r -> onChange(r.blocksSetup()) }
            }
        }.onFailure { Log.w("DrivingState", "CarUxRestrictions 사용 불가: ${it.message}") }
    }

    private fun CarUxRestrictions.blocksSetup() =
        activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP != 0

    fun release() {
        runCatching {
            manager?.unregisterListener()
            car?.disconnect()
        }
    }
}

/** 탑승·하차 음성 안내. ON_DEVICE 모드는 앱 등록 없이 쓸 수 있다. */
class Speaker(context: Context) {
    private val tts = runCatching {
        TextToSpeech(context, Mode.ON_DEVICE).apply {
            initialize()
            setLocale(Locale.KOREA)
        }
    }.onFailure { Log.w("Speaker", "TTS 사용 불가: ${it.message}") }.getOrNull()

    fun say(text: String) {
        runCatching { tts?.speak(text) }
    }

    fun release() {
        runCatching { tts?.release() }
    }
}
