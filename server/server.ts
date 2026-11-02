// 나눠타 중계 서버 — 차량 앱과 동승자 폰(모바일 웹) 사이를 잇는다.
//   차량 앱: 운행 생성, 좌석 QR 토큰 발급, 운행 상태(좌석·구간·정산) 업로드, 동승자 이벤트 폴링
//   동승자 웹: QR 스캔 → 체크인/체크아웃, 내 청구서 조회
//   운전자 웹: 송금 링크 등록, 정산 요약·카카오톡 공유
// Node 23.6+ 타입 스트리핑으로 바로 실행: node server.ts
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { extname, join, normalize } from "node:path";

const PORT = Number(process.env.PORT ?? 8787);
const PUBLIC_URL = process.env.PUBLIC_URL ?? `http://localhost:${PORT}`;
const KAKAO_JS_KEY = process.env.KAKAO_JS_KEY ?? "";
const KAKAO_REST_KEY = process.env.KAKAO_REST_KEY ?? "";
const DATA_DIR = join(import.meta.dirname, "data");
const DATA_FILE = join(DATA_DIR, "db.json");
const PUBLIC_DIR = join(import.meta.dirname, "public");

type SeatId = string; // 예: "FRONT_RIGHT", "REAR_LEFT"
interface Rider {
  id: string;
  secret: string; // 동승자 폰만 아는 값 (내 청구서 조회용)
  name: string;
  profileImage?: string;
  kakaoId?: string;
  seat: SeatId;
  boardedAt: number;
  alightedAt?: number;
  checkoutRequested?: boolean;
}
interface RiderEvent {
  seq: number;
  type: "checkin" | "checkout";
  riderId: string;
  seat: SeatId;
  at: number;
}
interface Bill {
  riderId: string;
  distanceKm: number;
  amount: number; // 원
  segments: { fromKm: number; toKm: number; sharedBy: number; cost: number }[];
  final: boolean;
  alightPlace?: string; // 하차 지점 지역명 (예: "원주")
}
interface Trip {
  id: string;
  carToken: string; // 차량 앱만 아는 값
  vehicleId: string;
  createdAt: number;
  endedAt?: number;
  fuelType: string;
  unitPrice: number;
  unitLabel: string; // "원/L" | "원/kWh"
  seatTokens: Record<SeatId, string>; // 좌석별 QR 토큰 (탑승 시마다 갱신)
  riders: Rider[];
  events: RiderEvent[];
  bills: Bill[];
  totalKm: number;
  totalCost: number;
}
interface Vehicle {
  id: string;
  driverName: string;
  payLink: string; // 운전자 송금 링크 (간편결제 송금 QR/링크)
  setupToken: string;
}
interface Db {
  trips: Record<string, Trip>;
  vehicles: Record<string, Vehicle>;
}

let db: Db = { trips: {}, vehicles: {} };
if (existsSync(DATA_FILE)) db = JSON.parse(await readFile(DATA_FILE, "utf8"));
let saveTimer: NodeJS.Timeout | undefined;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    await mkdir(DATA_DIR, { recursive: true });
    await writeFile(DATA_FILE, JSON.stringify(db));
  }, 300);
}

const id = (n = 8) => randomBytes(n).toString("base64url");

// --- HTTP 유틸 ---
function send(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" });
  res.end(JSON.stringify(body));
}
async function body(req: IncomingMessage): Promise<any> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const raw = Buffer.concat(chunks).toString("utf8");
  return raw ? JSON.parse(raw) : {};
}
class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}
function trip(tripId: string): Trip {
  const t = db.trips[tripId];
  if (!t) throw new HttpError(404, "운행을 찾을 수 없어요");
  return t;
}
function carAuth(req: IncomingMessage, t: Trip) {
  if (req.headers["x-car-token"] !== t.carToken) throw new HttpError(403, "차량 인증 실패");
}
function rider(t: Trip, riderId: string, secret: unknown): Rider {
  const r = t.riders.find((x) => x.id === riderId);
  if (!r || r.secret !== secret) throw new HttpError(403, "동승자 인증 실패");
  return r;
}
function pushEvent(t: Trip, type: RiderEvent["type"], r: Rider) {
  t.events.push({ seq: t.events.length + 1, type, riderId: r.id, seat: r.seat, at: Date.now() });
}
const seatUrl = (t: Trip, seat: SeatId, mode: "in" | "out") =>
  `${PUBLIC_URL}/r/${t.id}/${seat}?t=${t.seatTokens[seat]}${mode === "out" ? "&out=1" : ""}`;

// 동승자에게 보여 줄 공개 정보 (토큰·다른 사람 secret 제외)
function publicTrip(t: Trip) {
  const v = db.vehicles[t.vehicleId];
  return {
    id: t.id,
    ended: Boolean(t.endedAt),
    fuelType: t.fuelType,
    unitPrice: t.unitPrice,
    unitLabel: t.unitLabel,
    totalKm: t.totalKm,
    totalCost: t.totalCost,
    driverName: v?.driverName ?? "운전자",
    payLink: v?.payLink ?? "",
    riders: t.riders.map(({ id, name, profileImage, seat, alightedAt }) => ({ id, name, profileImage, seat, alighted: Boolean(alightedAt) })),
  };
}

// --- 라우팅 ---
type Handler = (req: IncomingMessage, res: ServerResponse, p: Record<string, string>, q: URLSearchParams) => Promise<void> | void;
const routes: [string, RegExp, string[], Handler][] = [];
function route(method: string, path: string, h: Handler) {
  const keys: string[] = [];
  const re = new RegExp("^" + path.replace(/:(\w+)/g, (_, k) => (keys.push(k), "([^/]+)")) + "$");
  routes.push([method, re, keys, h]);
}

// 차량: 운행 시작
route("POST", "/api/trips", async (req, res) => {
  const b = await body(req);
  const vehicleId: string = b.vehicleId || id();
  db.vehicles[vehicleId] ??= { id: vehicleId, driverName: "운전자", payLink: "", setupToken: id() };
  const t: Trip = {
    id: id(6),
    carToken: id(16),
    vehicleId,
    createdAt: Date.now(),
    fuelType: b.fuelType ?? "GASOLINE",
    unitPrice: Number(b.unitPrice ?? 1700),
    unitLabel: b.unitLabel ?? "원/L",
    seatTokens: {},
    riders: [],
    events: [],
    bills: [],
    totalKm: 0,
    totalCost: 0,
  };
  db.trips[t.id] = t;
  save();
  const v = db.vehicles[vehicleId];
  send(res, 201, { tripId: t.id, carToken: t.carToken, vehicleId, driverSetupUrl: `${PUBLIC_URL}/d/${vehicleId}?t=${v.setupToken}` });
});

// 차량: 좌석 QR 발급 (착석 감지 시 호출, 토큰은 매번 새로)
route("POST", "/api/trips/:tripId/seats/:seat/qr", async (req, res, p) => {
  const t = trip(p.tripId);
  carAuth(req, t);
  t.seatTokens[p.seat] = id(6);
  save();
  send(res, 200, { checkinUrl: seatUrl(t, p.seat, "in"), checkoutUrl: seatUrl(t, p.seat, "out") });
});

// 차량: 동승자 이벤트 폴링 (since 이후)
route("GET", "/api/trips/:tripId/events", (req, res, p, q) => {
  const t = trip(p.tripId);
  carAuth(req, t);
  const since = Number(q.get("since") ?? 0);
  send(res, 200, {
    events: t.events.filter((e) => e.seq > since),
    riders: t.riders.map(({ id, name, profileImage, seat, alightedAt }) => ({ id, name, profileImage, seat, alighted: Boolean(alightedAt) })),
  });
});

// 차량: 하차 확정 (좌석·도어 신호 또는 체크아웃 요청 처리 후)
route("POST", "/api/trips/:tripId/riders/:riderId/alight", async (req, res, p) => {
  const t = trip(p.tripId);
  carAuth(req, t);
  const r = t.riders.find((x) => x.id === p.riderId);
  if (!r) throw new HttpError(404, "동승자 없음");
  if (!r.alightedAt) {
    r.alightedAt = Date.now();
    delete t.seatTokens[r.seat];
  }
  save();
  send(res, 200, { ok: true });
});

// 차량: 정산 상태 업로드 (구간·청구서는 차량 앱이 계산)
route("PUT", "/api/trips/:tripId/bills", async (req, res, p) => {
  const t = trip(p.tripId);
  carAuth(req, t);
  const b = await body(req);
  t.bills = b.bills ?? [];
  t.totalKm = Number(b.totalKm ?? 0);
  t.totalCost = Number(b.totalCost ?? 0);
  if (b.ended) t.endedAt ??= Date.now();
  save();
  send(res, 200, { ok: true, summaryUrl: `${PUBLIC_URL}/s/${t.id}` });
});

// 동승자: 체크인 (QR 스캔 → 로그인 후)
route("POST", "/api/trips/:tripId/seats/:seat/checkin", async (req, res, p) => {
  const t = trip(p.tripId);
  const b = await body(req);
  if (!b.token || t.seatTokens[p.seat] !== b.token) throw new HttpError(410, "만료된 QR이에요. 차 화면의 새 QR을 찍어 주세요");
  if (b.kakaoCode) Object.assign(b, await kakaoProfile(String(b.kakaoCode), String(b.redirectUri)));
  const name = String(b.name ?? "").trim().slice(0, 20);
  if (!name) throw new HttpError(400, "이름이 필요해요");
  // 같은 좌석에 이미 탑승 중인 사람이 있으면 교체하지 않는다
  const occupied = t.riders.find((x) => x.seat === p.seat && !x.alightedAt);
  if (occupied && occupied.kakaoId && occupied.kakaoId === b.kakaoId) {
    return send(res, 200, { riderId: occupied.id, secret: occupied.secret });
  }
  if (occupied) throw new HttpError(409, "이 좌석에는 이미 다른 분이 등록돼 있어요");
  const r: Rider = {
    id: id(6),
    secret: id(12),
    name,
    profileImage: b.profileImage,
    kakaoId: b.kakaoId ? String(b.kakaoId) : undefined,
    seat: p.seat,
    boardedAt: Date.now(),
  };
  t.riders.push(r);
  pushEvent(t, "checkin", r);
  save();
  send(res, 201, { riderId: r.id, secret: r.secret });
});

// 카카오 로그인: 인가 코드 → 토큰 → 닉네임·프로필 이미지 (토큰은 저장하지 않는다)
async function kakaoProfile(code: string, redirectUri: string) {
  if (!KAKAO_REST_KEY) throw new HttpError(501, "카카오 로그인이 설정되지 않았어요. 이름으로 탑승해 주세요");
  const tok = await fetch("https://kauth.kakao.com/oauth/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded;charset=utf-8" },
    body: new URLSearchParams({ grant_type: "authorization_code", client_id: KAKAO_REST_KEY, redirect_uri: redirectUri, code }),
  }).then((r) => r.json());
  if (!tok.access_token) throw new HttpError(401, "카카오 로그인에 실패했어요. 다시 시도해 주세요");
  const u = await fetch("https://kapi.kakao.com/v2/user/me", { headers: { authorization: `Bearer ${tok.access_token}` } }).then((r) => r.json());
  const profile = u.kakao_account?.profile ?? {};
  return { kakaoId: String(u.id), name: profile.nickname ?? "카카오 사용자", profileImage: profile.thumbnail_image_url };
}

// 동승자: 하차 QR (체크아웃 요청 → 차량 앱이 확정)
route("POST", "/api/trips/:tripId/riders/:riderId/checkout", async (req, res, p) => {
  const t = trip(p.tripId);
  const b = await body(req);
  const r = rider(t, p.riderId, b.secret);
  if (!r.alightedAt && !r.checkoutRequested) {
    r.checkoutRequested = true;
    pushEvent(t, "checkout", r);
    save();
  }
  send(res, 200, { ok: true });
});

// 청구서에 이름·좌석을 붙인 공개 형태 (같은 차에 탄 사람끼리 보는 정산표)
function namedBills(t: Trip) {
  const byId = Object.fromEntries(t.riders.map((r) => [r.id, r]));
  return t.bills.map((b) => ({ ...b, name: byId[b.riderId]?.name ?? "동승자", seat: byId[b.riderId]?.seat ?? "" }));
}

// 동승자: 내 청구서
route("GET", "/api/trips/:tripId/riders/:riderId", (req, res, p, q) => {
  const t = trip(p.tripId);
  const r = rider(t, p.riderId, q.get("secret"));
  const bills = namedBills(t);
  send(res, 200, {
    trip: publicTrip(t),
    me: { id: r.id, name: r.name, seat: r.seat, alighted: Boolean(r.alightedAt) },
    bill: bills.find((x) => x.riderId === r.id) ?? null,
    bills,
  });
});

// 운행 요약 (정산표 공유용)
route("GET", "/api/trips/:tripId/summary", (req, res, p) => {
  const t = trip(p.tripId);
  send(res, 200, { trip: publicTrip(t), bills: namedBills(t) });
});

// 운전자: 송금 링크·이름 설정 (차 화면의 운전자 QR로 진입)
route("GET", "/api/vehicles/:vehicleId", (req, res, p, q) => {
  const v = db.vehicles[p.vehicleId];
  if (!v || v.setupToken !== q.get("t")) throw new HttpError(403, "운전자 인증 실패");
  send(res, 200, { driverName: v.driverName, payLink: v.payLink });
});
route("PUT", "/api/vehicles/:vehicleId", async (req, res, p) => {
  const v = db.vehicles[p.vehicleId];
  const b = await body(req);
  if (!v || v.setupToken !== b.t) throw new HttpError(403, "운전자 인증 실패");
  v.driverName = String(b.driverName ?? v.driverName).slice(0, 20);
  const link = String(b.payLink ?? "").trim();
  if (link && !/^https:\/\//.test(link)) throw new HttpError(400, "https 링크만 등록할 수 있어요");
  v.payLink = link;
  save();
  send(res, 200, { ok: true });
});

route("GET", "/api/config", (req, res) => send(res, 200, { kakaoJsKey: KAKAO_JS_KEY }));

// --- 정적 페이지 ---
const pages: [RegExp, string][] = [
  [/^\/r\/[^/]+\/[^/]+$/, "join.html"], // 좌석 QR → 체크인/체크아웃
  [/^\/t\/[^/]+$/, "me.html"], // 내 청구서
  [/^\/s\/[^/]+$/, "summary.html"], // 정산 요약
  [/^\/d\/[^/]+$/, "driver.html"], // 운전자 설정
];
const types: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".woff2": "font/woff2",
  ".png": "image/png",
};
async function serveStatic(path: string, res: ServerResponse) {
  const page = pages.find(([re]) => re.test(path))?.[1];
  const file = normalize(join(PUBLIC_DIR, page ?? path));
  if (!file.startsWith(PUBLIC_DIR) || !existsSync(file)) return send(res, 404, { error: "not found" });
  res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
  res.end(await readFile(file));
}

// --- 시연용 운행 (DEMO=1) ---
// 합정 → 강릉, 원주에서 하늘 하차. 지금 171.0km 지점을 달리는 중.
// 구간 비용 = 거리 ÷ 연비 × 단가, 그 구간 탑승 인원(운전자 포함)으로 나눔 — 차량 앱 TripMeter와 같은 식.
function seedDemo() {
  const price = 1680;
  const legs = [
    { from: 0, to: 92.4, kmPerL: 14.2, riders: ["sy", "ms", "hn"] },
    { from: 92.4, to: 171.0, kmPerL: 13.1, riders: ["sy", "ms"] },
  ];
  const people = [
    { id: "sy", name: "수연", seat: "FRONT_RIGHT" },
    { id: "ms", name: "민수", seat: "REAR_LEFT" },
    { id: "hn", name: "하늘", seat: "REAR_RIGHT" },
  ];
  const finalIds = new Set(["hn"]);
  const vehicleId = "demo-car";
  db.vehicles[vehicleId] = { id: vehicleId, driverName: "지훈", payLink: "https://example.com/pay/jihoon", setupToken: "demo" };
  const now = Date.now();
  const bills: Bill[] = people.map((pp) => {
    const segments = legs
      .filter((l) => l.riders.includes(pp.id))
      .map((l) => {
        const sharedBy = l.riders.length + 1;
        return { fromKm: l.from, toKm: l.to, sharedBy, cost: Math.round(((l.to - l.from) / l.kmPerL) * price / sharedBy) };
      });
    const raw = segments.reduce((a, x) => a + x.cost, 0);
    return {
      riderId: pp.id,
      distanceKm: Math.round(segments.reduce((a, x) => a + x.toKm - x.fromKm, 0) * 10) / 10,
      amount: Math.round(raw / 10) * 10,
      segments,
      final: finalIds.has(pp.id),
      alightPlace: finalIds.has(pp.id) ? "원주" : undefined,
    };
  });
  const totalCost = Math.round(legs.reduce((a, l) => a + ((l.to - l.from) / l.kmPerL) * price, 0));
  db.trips.demo = {
    id: "demo",
    carToken: "demo",
    vehicleId,
    createdAt: now - 2 * 3600_000,
    fuelType: "GASOLINE",
    unitPrice: price,
    unitLabel: "원/L",
    seatTokens: { FRONT_RIGHT: "demo", REAR_LEFT: "demo", REAR_RIGHT: "demo", REAR_CENTER: "demo" },
    riders: people.map((pp, i) => ({
      ...pp,
      secret: "demo",
      boardedAt: now - 2 * 3600_000 + i * 20_000,
      alightedAt: finalIds.has(pp.id) ? now - 50 * 60_000 : undefined,
    })),
    events: [],
    bills,
    totalKm: 171.0,
    totalCost,
  };
  // 금액 검산: 동승자 몫의 합은 총 기름값을 넘지 않는다
  console.assert(bills.reduce((a, b) => a + b.amount, 0) <= totalCost, "demo bills exceed total");
}
if (process.env.DEMO === "1") seedDemo();

createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://x");
  try {
    if (req.method === "OPTIONS") {
      res.writeHead(204, { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type, x-car-token", "access-control-allow-methods": "GET, POST, PUT" });
      return res.end();
    }
    for (const [method, re, keys, h] of routes) {
      const m = req.method === method && url.pathname.match(re);
      if (m) return await h(req, res, Object.fromEntries(keys.map((k, i) => [k, decodeURIComponent(m[i + 1])])), url.searchParams);
    }
    if (req.method === "GET" && url.pathname === "/") {
      res.writeHead(302, { location: "/s/demo" });
      return res.end();
    }
    if (req.method === "GET") return await serveStatic(url.pathname, res);
    send(res, 404, { error: "not found" });
  } catch (e) {
    const status = e instanceof HttpError ? e.status : 500;
    if (status === 500) console.error(e);
    send(res, status, { error: e instanceof Error ? e.message : String(e) });
  }
}).listen(PORT, () => console.log(`나눠타 서버 ${PUBLIC_URL} (port ${PORT})`));
