// منطق إرسال الإشعارات عبر FCM HTTP v1. JavaScript نقي (WebCrypto + fetch) يعمل في Deno وNode معاً ليُختبر محلياً.
export const TOPIC = "news";
const enc = new TextEncoder();

export function b64url(input) {
  const bytes = typeof input === "string" ? enc.encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem) {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

/** يوقّع JWT (RS256) لحساب الخدمة. */
export async function makeJwt(sa, nowSec) {
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: nowSec,
    exp: nowSec + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claim}`));
  return `${header}.${claim}.${b64url(sig)}`;
}

export async function getAccessToken(sa, fetchFn, nowSec) {
  const jwt = await makeJwt(sa, nowSec);
  const r = await fetchFn("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${encodeURIComponent(jwt)}`,
  });
  if (!r.ok) throw new Error(`oauth ${r.status}`);
  const j = await r.json();
  if (!j.access_token) throw new Error("oauth: no token");
  return j.access_token;
}

/** رسالة بيانات فقط (data) كي يعرضها التطبيق بنفسه ويتفادى التكرار مع الاستطلاع. */
export function buildMessage(rec) {
  const title = String(rec.title ?? "").trim().slice(0, 120);
  if (!title || !Number.isSafeInteger(Number(rec.id))) return null;
  const data = { id: String(rec.id), title, body: String(rec.body ?? "").trim().slice(0, 500) };
  if (rec.news_id != null) data.news_id = String(rec.news_id);
  return { topic: TOPIC, data, android: { priority: "high" } };
}

export function safeEqual(a, b) {
  const x = enc.encode(String(a ?? "")), y = enc.encode(String(b ?? ""));
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return d === 0;
}

const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

/** env: { PUSH_WEBHOOK_SECRET, FCM_SERVICE_ACCOUNT (JSON نصي) }، deps: { fetch, now } */
export async function handle(req, env, deps = {}) {
  const fetchFn = deps.fetch ?? fetch;
  const nowSec = Math.floor((deps.now ?? Date.now)() / 1000);
  if (req.method !== "POST") return json(405, { error: "method" });
  if (!env.PUSH_WEBHOOK_SECRET || !safeEqual(req.headers.get("x-webhook-secret"), env.PUSH_WEBHOOK_SECRET)) {
    return json(401, { error: "unauthorized" });
  }
  let payload;
  try { payload = await req.json(); } catch { return json(400, { error: "bad json" }); }
  if (payload?.type !== "INSERT" || payload?.table !== "notifications" || !payload.record) return json(200, { skipped: "not a notification insert" });
  if (payload.record.user_id != null) return json(200, { skipped: "per-user notification" }); // نبث العام فقط
  const msg = buildMessage(payload.record);
  if (!msg) return json(200, { skipped: "invalid record" });
  let sa;
  try { sa = JSON.parse(env.FCM_SERVICE_ACCOUNT); } catch { return json(500, { error: "service account not configured" }); }
  if (!sa.client_email || !sa.private_key || !sa.project_id) return json(500, { error: "service account incomplete" });
  try {
    const token = await getAccessToken(sa, fetchFn, nowSec);
    const r = await fetchFn(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(sa.project_id)}/messages:send`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
      body: JSON.stringify({ message: msg }),
    });
    if (!r.ok) return json(502, { error: `fcm ${r.status}` });
    return json(200, { sent: true, id: msg.data.id });
  } catch (e) {
    return json(502, { error: "send failed" });
  }
}
