// 나눠타 동승자 웹 공통
export const SEAT_LABEL = {
  DRIVER: "운전석",
  FRONT_RIGHT: "조수석",
  REAR_LEFT: "뒷좌석 왼쪽",
  REAR_CENTER: "뒷좌석 가운데",
  REAR_RIGHT: "뒷좌석 오른쪽",
};

export const won = (n) => `${Math.round(n).toLocaleString("ko-KR")}원`;
export const km = (n) => `${(Math.round(n * 10) / 10).toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 })}km`;
export const initial = (name) => [...(name || "?")][0];
export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
export const $ = (sel, root = document) => root.querySelector(sel);

// 브랜드 마크: 나란히 놓인 두 좌석 — 하나는 운전석(잉크), 하나는 동승석(액센트)
// 브랜드 마크: 옆에서 본 좌석 둘 — 앞(운전석, 잉크)과 뒤(동승석, 액센트)
export const MARK = `<svg viewBox="0 0 28 28" aria-hidden="true"><path d="M4 4.5c0-1.1 1.2-1.8 2.2-1.2l1 .6c.5.3.8.8.8 1.4V15h5.5a2 2 0 0 1 2 2v2.5a1.5 1.5 0 0 1-1.5 1.5H6a2 2 0 0 1-2-2z" fill="#1d5fd6"/><path d="M12 8.5c0-1.1 1.2-1.8 2.2-1.2l1 .6c.5.3.8.8.8 1.4V19h5.5a2 2 0 0 1 2 2v2.5a1.5 1.5 0 0 1-1.5 1.5H14a2 2 0 0 1-2-2z" fill="#1b1d1f"/></svg>`;

export function bar(scope = "") {
  return `<header class="bar"><a class="brand" href="/" aria-label="나눠타">${MARK}<span>나눠타</span></a><span class="scope">${esc(scope)}</span></header>`;
}

export async function api(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: { "content-type": "application/json", ...(opts.headers || {}) },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `요청 실패 (${res.status})`);
  return data;
}

// 동승자 자격 증명은 이 폰에만 저장 (운행별)
export const me = {
  get(tripId) {
    try { return JSON.parse(localStorage.getItem(`nanwota:${tripId}`) || "null"); } catch { return null; }
  },
  set(tripId, v) {
    try { localStorage.setItem(`nanwota:${tripId}`, JSON.stringify(v)); } catch {}
  },
};

/**
 * 탑승 구간 레인. people: [{ name, seat, fromKm, toKm, open }], cuts: [{ km, label }]
 * totalKm까지 축을 그리고, 사람마다 한 줄에 탄 구간을 좌석 색으로.
 */
export function lanes(people, totalKm, cuts = []) {
  const end = Math.max(totalKm, 1);
  const pct = (v) => `${(v / end) * 100}%`;
  const rows = people
    .map(
      (p) => `<div class="lane" data-seat="${p.seat}">
        <span class="label">${esc(p.name)}</span>
        <div class="track"><div class="ride${p.open ? " open" : ""}" style="left:${pct(p.fromKm)};width:${pct(p.toKm - p.fromKm)}"><span>${km(p.toKm - p.fromKm)}</span></div></div>
      </div>`,
    )
    .join("");
  const ticks = [0, ...cuts.map((c) => c.km), end]
    .map((v, i, a) => {
      // 좁은 화면에서도 겹치지 않게 짧게: 출발 · 지명 · 끝 거리
      const label = i === 0 ? "출발" : i === a.length - 1 ? km(v) : cuts[i - 1].label;
      return `<span style="left:${pct(v)}">${esc(label)}</span>`;
    })
    .join("");
  const cutLines = cuts.map((c) => `<i class="cut" style="left:${pct(c.km)}"></i>`).join("");
  return `<div class="lanes-wrap"><div class="lanes">${rows}</div><div class="cuts">${cutLines}</div><div class="axis">${ticks}</div></div>`;
}

/** 서버 요약 → 레인용 사람 목록 (운전자는 전체 구간) */
export function lanePeople(trip, bills) {
  const people = [{ name: `${trip.driverName} (운전)`, seat: "DRIVER", fromKm: 0, toKm: trip.totalKm, open: !trip.ended }];
  for (const b of bills) {
    const segs = b.segments || [];
    if (!segs.length) continue;
    people.push({
      name: b.name,
      seat: b.seat,
      fromKm: segs[0].fromKm,
      toKm: segs[segs.length - 1].toKm,
      open: !b.final,
    });
  }
  return people;
}

/** 하차 지점: 확정된 청구서의 끝 km (운행 끝 제외) */
export function alightCuts(trip, bills) {
  return bills
    .filter((b) => b.final && b.segments?.length)
    .map((b) => ({ km: b.segments[b.segments.length - 1].toKm, label: b.alightPlace || `${b.name} 하차` }))
    .filter((c) => c.km < trip.totalKm - 0.05)
    .sort((a, b) => a.km - b.km);
}

export function poll(fn, ms = 3000) {
  let stopped = false;
  const tick = async () => {
    if (stopped) return;
    try { await fn(); } catch (e) { console.warn(e); }
    if (!stopped && document.visibilityState !== "hidden") setTimeout(tick, ms);
    else if (!stopped) document.addEventListener("visibilitychange", () => tick(), { once: true });
  };
  tick();
  return () => (stopped = true);
}
