package ai.nanwota.car.ui

import ai.nanwota.car.NanwotaApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as NanwotaApp).controller
        val actions = Actions(
            start = c::startTrip,
            end = c::endTrip,
            reset = c::reset,
            seat = c::onSeat,
            door = c::onDoor,
            drive = c::demoDrive,
        )
        setContent {
            val ui by c.ui.collectAsStateWithLifecycle()
            CarScreen(ui, actions)
        }
    }
}
