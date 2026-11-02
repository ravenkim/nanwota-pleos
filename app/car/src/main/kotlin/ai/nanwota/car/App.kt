package ai.nanwota.car

import android.app.Application
import android.content.Context
import java.text.NumberFormat
import java.util.Locale

class NanwotaApp : Application() {
    lateinit var controller: TripController
        private set
    private lateinit var signals: VehicleSignals
    private var driving: DrivingState? = null
    private var speaker: Speaker? = null

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this)
        speaker = Speaker(this)
        controller = TripController(this, ServerClient(settings.serverUrl), speaker)
        signals = VehicleSignals(this, controller).also { runCatching { it.start() }.onFailure { e -> controller.onUnsupported("vehicle ${e.message}") } }
        driving = DrivingState(this, controller::onDrivingChanged)
    }

    override fun onTerminate() {
        signals.stop()
        driving?.release()
        speaker?.release()
        controller.release()
        super.onTerminate()
    }
}

/** 운전자 설정: 단가와, 차량이 연비를 주지 않을 때 쓸 평균 연비 */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("nanwota", Context.MODE_PRIVATE)

    val serverUrl: String get() = p.getString("server", null) ?: BuildConfig.SERVER_URL
    var vehicleId: String?
        get() = p.getString("vehicleId", null)
        set(v) = p.edit().putString("vehicleId", v).apply()

    /** 휘발유 원/L, 전기 원/kWh */
    fun unitPrice(electric: Boolean) = p.getFloat(if (electric) "priceEv" else "priceFuel", if (electric) 324f else 1680f).toDouble()
    /** km/L 또는 km/kWh */
    fun fallbackEfficiency(electric: Boolean) = p.getFloat(if (electric) "effEv" else "effFuel", if (electric) 5.2f else 12.5f).toDouble()

    fun setUnitPrice(electric: Boolean, v: Double) = p.edit().putFloat(if (electric) "priceEv" else "priceFuel", v.toFloat()).apply()
}

object Format {
    private val nf = NumberFormat.getIntegerInstance(Locale.KOREA)
    fun won(v: Long) = "${nf.format(v)}원"
    fun km(v: Double) = String.format(Locale.KOREA, "%,.1fkm", v)
}
