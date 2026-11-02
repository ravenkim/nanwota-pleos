package ai.nanwota.car

import ai.nanwota.core.TripMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 나눠타 중계 서버 (server/server.ts) 클라이언트. 차량 토큰은 헤더로만 보낸다. */
class ServerClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class TripHandle(val tripId: String, val carToken: String, val vehicleId: String, val driverSetupUrl: String)
    data class SeatQr(val checkinUrl: String, val checkoutUrl: String)
    data class RiderEvent(val seq: Int, val type: String, val riderId: String, val seat: String)
    data class RiderInfo(val id: String, val name: String, val seat: String, val alighted: Boolean)
    data class Events(val events: List<RiderEvent>, val riders: List<RiderInfo>)

    private suspend fun call(method: String, path: String, body: JSONObject? = null, carToken: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .method(method, body?.toString()?.toRequestBody(json))
                .apply { if (carToken != null) header("x-car-token", carToken) }
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val obj = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (!res.isSuccessful) throw IOException(obj.optString("error", "HTTP ${res.code}"))
                obj
            }
        }

    suspend fun startTrip(vehicleId: String?, fuelType: String, unitPrice: Double, unitLabel: String): TripHandle {
        val o = call("POST", "/api/trips", JSONObject().apply {
            if (vehicleId != null) put("vehicleId", vehicleId)
            put("fuelType", fuelType)
            put("unitPrice", unitPrice)
            put("unitLabel", unitLabel)
        })
        return TripHandle(o.getString("tripId"), o.getString("carToken"), o.getString("vehicleId"), o.getString("driverSetupUrl"))
    }

    suspend fun seatQr(t: TripHandle, seat: SeatPos): SeatQr {
        val o = call("POST", "/api/trips/${t.tripId}/seats/${seat.id}/qr", carToken = t.carToken)
        return SeatQr(o.getString("checkinUrl"), o.getString("checkoutUrl"))
    }

    suspend fun events(t: TripHandle, since: Int): Events {
        val o = call("GET", "/api/trips/${t.tripId}/events?since=$since", carToken = t.carToken)
        val ev = o.getJSONArray("events").objects().map {
            RiderEvent(it.getInt("seq"), it.getString("type"), it.getString("riderId"), it.getString("seat"))
        }
        val riders = o.getJSONArray("riders").objects().map {
            RiderInfo(it.getString("id"), it.getString("name"), it.getString("seat"), it.optBoolean("alighted"))
        }
        return Events(ev, riders)
    }

    suspend fun alight(t: TripHandle, riderId: String) {
        call("POST", "/api/trips/${t.tripId}/riders/$riderId/alight", carToken = t.carToken)
    }

    suspend fun putBills(t: TripHandle, bills: List<TripMeter.Bill>, places: Map<String, String>, totalKm: Double, totalCost: Long, ended: Boolean): String {
        val arr = JSONArray()
        for (b in bills) {
            arr.put(JSONObject().apply {
                put("riderId", b.riderId)
                put("distanceKm", Math.round(b.distanceKm * 10) / 10.0)
                put("amount", b.amount)
                put("final", b.final)
                places[b.riderId]?.let { put("alightPlace", it) }
                put("segments", JSONArray().apply {
                    b.segments.forEach { s ->
                        put(JSONObject().put("fromKm", s.fromKm).put("toKm", s.toKm).put("sharedBy", s.sharedBy).put("cost", s.cost))
                    }
                })
            })
        }
        val o = call("PUT", "/api/trips/${t.tripId}/bills", JSONObject().apply {
            put("bills", arr)
            put("totalKm", totalKm)
            put("totalCost", totalCost)
            put("ended", ended)
        }, carToken = t.carToken)
        return o.optString("summaryUrl")
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
