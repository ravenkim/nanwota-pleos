package ai.nanwota.car.ui

import ai.nanwota.car.Format
import ai.nanwota.car.R
import ai.nanwota.car.SeatPos
import ai.nanwota.car.TripController
import ai.nanwota.car.TripController.Status
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 센터 디스플레이 한 화면.
 * 화면의 질문: "지금 누가 어느 자리에 타서 얼마째인가?" → 가장 큰 블록은 좌석 배치도.
 * 오른쪽은 지금 할 일 하나: 탑승 QR(정차 중) / 기름값 / 도착 후 정산표.
 */
@Composable
fun CarScreen(ui: TripController.Ui, actions: Actions) {
    var demo by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(T.ground).padding(horizontal = 32.dp, vertical = 20.dp)) {
        TopBar(ui, actions, onToggleDemo = { demo = !demo })
        Spacer(Modifier.height(20.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(T.gap)) {
            SeatMap(ui, Modifier.weight(1.2f).fillMaxHeight())
            SidePanel(ui, actions, Modifier.weight(1f).fillMaxHeight())
        }
        if (demo) {
            Spacer(Modifier.height(16.dp))
            DemoPanel(ui, actions)
        }
        ui.notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = T.small.copy(color = T.warnInk), modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(T.warnBg).padding(12.dp).fillMaxWidth())
        }
    }
}

class Actions(
    val start: () -> Unit,
    val end: () -> Unit,
    val reset: () -> Unit,
    val seat: (SeatPos, Boolean) -> Unit,
    val door: (SeatPos, Boolean) -> Unit,
    val drive: (Double, Double) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(ui: TripController.Ui, actions: Actions, onToggleDemo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        // 로고를 길게 누르면 시연 패널 (에뮬레이터에서 넣을 수 없는 연비·주행 값)
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = {}, onLongClick = onToggleDemo).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Text("나눠타", style = T.title)
        }
        Spacer(Modifier.width(24.dp))
        Text(
            when (ui.status) {
                Status.READY -> "동승자가 앉으면 좌석마다 QR이 떠요"
                Status.RUNNING -> "운행 중 · ${Format.km(ui.totalKm)} · 기름값 ${Format.won(ui.totalCost)}"
                Status.ENDED -> "도착 · ${Format.km(ui.totalKm)} · 기름값 ${Format.won(ui.totalCost)}"
            },
            style = T.body.copy(color = T.muted),
            modifier = Modifier.weight(1f),
        )
        when (ui.status) {
            Status.READY -> Button("운행 시작", primary = true, onClick = actions.start)
            Status.RUNNING -> Button("도착 · 정산 확정", primary = true, enabled = !ui.driving, onClick = actions.end)
            Status.ENDED -> Button("새 운행", primary = false, onClick = actions.reset)
        }
    }
}

// ---------- 좌석 배치도 ----------

@Composable
private fun SeatMap(ui: TripController.Ui, modifier: Modifier) {
    Panel(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("좌석", style = T.section, modifier = Modifier.weight(1f))
            val onBoard = ui.seats.count { it.rider != null }
            Text(if (onBoard == 0) "동승자 없음" else "동승자 ${onBoard}명 탑승 중", style = T.small)
        }
        Spacer(Modifier.height(16.dp))
        // 위에서 본 차: 앞줄 운전석·조수석, 뒷줄 세 자리
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(T.raised).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DriverTile(ui, Modifier.weight(1f))
                Spacer(Modifier.weight(0.5f))
                SeatTile(ui.seats.first { it.pos == SeatPos.FRONT_RIGHT }, ui, Modifier.weight(1f))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(SeatPos.REAR_LEFT, SeatPos.REAR_CENTER, SeatPos.REAR_RIGHT).forEach { p ->
                    SeatTile(ui.seats.first { it.pos == p }, ui, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DriverTile(ui: TripController.Ui, modifier: Modifier) {
    val h = T.hue(SeatPos.DRIVER)
    Tile(modifier, h.bg) {
        Text("운전석", style = T.small.copy(color = h.ink))
        Spacer(Modifier.weight(1f))
        Text("운전자 몫", style = T.body.copy(color = h.ink))
        Text(Format.won((ui.totalCost - ui.ridersShare).coerceAtLeast(0)), style = T.section.copy(color = h.ink))
    }
}

@Composable
private fun SeatTile(s: TripController.SeatUi, ui: TripController.Ui, modifier: Modifier) {
    val h = T.hue(s.pos)
    when {
        s.rider != null -> Tile(modifier, h.bg) {
            Text(s.pos.label, style = T.small.copy(color = h.ink))
            Spacer(Modifier.weight(1f))
            Text(s.rider.name, style = T.body.copy(color = h.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Format.won(s.amount ?: 0), style = T.section.copy(color = h.ink))
            Text(Format.km(s.distanceKm ?: 0.0), style = T.small.copy(color = h.ink))
        }
        s.occupied -> Tile(modifier, T.surface, border = h.mark) {
            Text(s.pos.label, style = T.small.copy(color = h.ink))
            Spacer(Modifier.weight(1f))
            Text(if (ui.driving) "정차하면\nQR 등록" else "QR로\n탑승 등록", style = T.body.copy(color = h.ink, fontWeight = FontWeight.Medium))
        }
        else -> Tile(modifier, T.surface) {
            Text(s.pos.label, style = T.small)
            Spacer(Modifier.weight(1f))
            Text("빈자리", style = T.body.copy(color = T.muted))
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, bg: Color, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier.fillMaxHeight().clip(shape).background(bg)
            .then(if (border != null) Modifier.border(2.dp, border, shape) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

// ---------- 오른쪽: 지금 할 일 ----------

@Composable
private fun SidePanel(ui: TripController.Ui, actions: Actions, modifier: Modifier) {
    val waiting = ui.seats.filter { it.occupied && it.rider == null }
    when {
        ui.status == Status.ENDED -> SettledPanel(ui, modifier)
        waiting.isNotEmpty() -> CheckinPanel(waiting, ui.driving, modifier)
        else -> FarePanel(ui, modifier)
    }
}

@Composable
private fun CheckinPanel(waiting: List<TripController.SeatUi>, driving: Boolean, modifier: Modifier) {
    val s = waiting.first()
    val h = T.hue(s.pos)
    Panel(modifier) {
        Text("${s.pos.label}에 타신 분", style = T.section)
        Spacer(Modifier.height(4.dp))
        Text(
            if (driving) "주행 중에는 QR을 띄우지 않아요. 정차하면 나타나요." else "폰 카메라로 찍으면 탑승이 등록돼요. 앱 설치는 필요 없어요.",
            style = T.small,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val url = s.checkinUrl
            when {
                driving -> Text("정차 후 표시", style = T.body.copy(color = T.muted))
                url == null -> Text("QR 준비 중", style = T.body.copy(color = T.muted))
                else -> Box(Modifier.clip(RoundedCornerShape(T.radiusInner)).background(h.bg).padding(16.dp)) {
                    QrCode(url, Modifier.size(260.dp).clip(RoundedCornerShape(6.dp)), ink = h.ink.toArgb())
                }
            }
        }
        if (waiting.size > 1) {
            Spacer(Modifier.height(12.dp))
            Text("다음: " + waiting.drop(1).joinToString(" · ") { it.pos.label }, style = T.small)
        }
    }
}

@Composable
private fun FarePanel(ui: TripController.Ui, modifier: Modifier) {
    Panel(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("기름값", style = T.section, modifier = Modifier.weight(1f))
            Text(
                "${if (ui.electric) "전기" else "휘발유"} ${Format.won(ui.unitPrice.toLong())}/${if (ui.electric) "kWh" else "L"}",
                style = T.small,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(Format.won(ui.totalCost), style = T.figure)
        val eff = ui.kmPerUnit?.let { String.format("%.1f", it) + if (ui.electric) "km/kWh" else "km/L" } ?: "—"
        Text("${Format.km(ui.totalKm)} · 연비 $eff ${if (ui.efficiencyFromCar) "(차량 측정)" else "(설정값)"}", style = T.small)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val rows = ui.seats.filter { it.rider != null }.map { Triple(it.pos, it.rider!!.name, it) }
            if (rows.isEmpty() && ui.alighted.isEmpty()) {
                Text("동승자가 등록하면 여기에 각자 몫이 쌓여요.", style = T.small, modifier = Modifier.dashedEmpty())
            }
            rows.forEach { (pos, name, s) -> FareRow(pos, name, "${Format.km(s.distanceKm ?: 0.0)} · 탑승 중", s.amount ?: 0, final = false) }
            ui.alighted.forEach { a -> FareRow(a.seat, a.rider.name, "${Format.km(a.distanceKm)} · 하차", a.amount, final = true) }
        }
    }
}

@Composable
private fun FareRow(pos: SeatPos, name: String, sub: String, amount: Long, final: Boolean) {
    val h = T.hue(pos)
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(h.bg), contentAlignment = Alignment.Center) {
            Text(name.take(1), style = T.body.copy(color = h.ink, fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = T.body.copy(fontWeight = FontWeight.Medium))
            Text(sub, style = T.small)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.won(amount), style = T.body.copy(fontWeight = FontWeight.SemiBold))
            Text(if (final) "확정" else "지금까지", style = T.small)
        }
    }
}

@Composable
private fun SettledPanel(ui: TripController.Ui, modifier: Modifier) {
    Panel(modifier) {
        Text("정산 확정", style = T.section)
        Spacer(Modifier.height(4.dp))
        Text("동승자 폰에 각자 몫과 송금 버튼이 갔어요. 정산표를 단톡방에 보내려면 운전자 폰으로 찍으세요.", style = T.small)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(Format.won(ui.ridersShare), style = T.figure)
                Text("동승자 합계 · 운전자 몫 ${Format.won((ui.totalCost - ui.ridersShare).coerceAtLeast(0))}", style = T.small)
                Spacer(Modifier.height(12.dp))
                ui.alighted.forEach { a -> FareRow(a.seat, a.rider.name, Format.km(a.distanceKm), a.amount, final = true) }
            }
            ui.summaryUrl?.takeIf { !ui.driving }?.let { QrCode(it, Modifier.size(200.dp)) }
        }
    }
}

// ---------- 시연 패널 ----------

@Composable
private fun DemoPanel(ui: TripController.Ui, actions: Actions) {
    Panel(Modifier.fillMaxWidth()) {
        Text("시연 신호 — 에뮬레이터 Virtual Console에 없는 값", style = T.small)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SeatPos.passengers.forEach { p ->
                val s = ui.seats.first { it.pos == p }
                Button(if (s.occupied) "${p.label} 일어남" else "${p.label} 착석", onClick = { actions.seat(p, !s.occupied) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeatPos.passengers.distinctBy { it.door }.forEach { p -> Button("${p.label.removeSuffix(" 왼쪽").removeSuffix(" 오른쪽")} 문 ${if (p.door.name.endsWith("LEFT")) "왼쪽" else "오른쪽"} 열기", onClick = { actions.door(p, true) }) }
            Button("+20km 고속도로 (14.2km/L)", onClick = { actions.drive(20.0, 14.2) })
            Button("+10km 시내 (9.8km/L)", onClick = { actions.drive(10.0, 9.8) })
        }
    }
}

// ---------- 공통 ----------

@Composable
private fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(T.radiusCard)).background(T.surface).border(1.dp, T.border, RoundedCornerShape(T.radiusCard)).padding(T.pad),
        content = content,
    )
}

@Composable
private fun Button(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    ButtonBox(label, primary, enabled, onClick)

@Composable
private fun ButtonBox(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        !enabled -> T.active
        primary -> T.primary
        else -> T.surface
    }
    Box(
        Modifier.heightIn(min = 56.dp).clip(shape).background(bg)
            .then(if (!primary && enabled) Modifier.border(1.dp, T.control, shape) else Modifier)
            .combinedClickableOrNot(enabled, onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = T.body.copy(fontWeight = FontWeight.SemiBold, color = if (primary && enabled) Color.White else if (enabled) T.text else T.muted))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableOrNot(enabled: Boolean, onClick: () -> Unit) =
    combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)

private fun Modifier.dashedEmpty() = clip(RoundedCornerShape(10.dp)).border(1.dp, T.control, RoundedCornerShape(10.dp)).padding(16.dp).fillMaxWidth()
