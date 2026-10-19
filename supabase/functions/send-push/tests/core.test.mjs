import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, createVerify } from "node:crypto";
import { handle, buildMessage, makeJwt, safeEqual, b64url } from "../core.mjs";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const sa = {
  client_email: "x@dreams.iam.gserviceaccount.com", project_id: "dreams-3e774",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
};
const env = { PUSH_WEBHOOK_SECRET: "s3cret", FCM_SERVICE_ACCOUNT: JSON.stringify(sa) };
const rec = { id: 7, user_id: null, title: "عنوان", body: "نص", news_id: 3 };
const req = (body, secret = "s3cret", method = "POST") =>
  new Request("http://x/", { method, headers: { "x-webhook-secret": secret ?? "" }, body: method === "POST" ? JSON.stringify(body) : undefined });
const ins = (r) => ({ type: "INSERT", table: "notifications", record: r });
function fakeFetch(calls, fcmStatus = 200) {
  return async (url, init) => {
    calls.push({ url, init });
    if (url.includes("oauth2")) return new Response(JSON.stringify({ access_token: "tok" }), { status: 200 });
    return new Response("{}", { status: fcmStatus });
  };
}

test("JWT موقّع RS256 صحيح", async () => {
  const jwt = await makeJwt(sa, 1000);
  const [h, c, s] = jwt.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(h, "base64url")), { alg: "RS256", typ: "JWT" });
  const claim = JSON.parse(Buffer.from(c, "base64url"));
  assert.equal(claim.iss, sa.client_email); assert.equal(claim.exp, 4600);
  assert.match(claim.scope, /firebase\.messaging/);
  const v = createVerify("RSA-SHA256"); v.update(`${h}.${c}`);
  assert.ok(v.verify(publicKey, Buffer.from(s, "base64url")));
});

test("بث ناجح لموضوع news ببيانات فقط", async () => {
  const calls = [];
  const r = await handle(req(ins(rec)), env, { fetch: fakeFetch(calls), now: () => 1000000 });
  assert.equal(r.status, 200); assert.equal((await r.json()).sent, true);
  assert.equal(calls.length, 2);
  assert.ok(calls[0].init.body.includes("jwt-bearer"));
  assert.equal(calls[1].url, "https://fcm.googleapis.com/v1/projects/dreams-3e774/messages:send");
  assert.equal(calls[1].init.headers.authorization, "Bearer tok");
  const m = JSON.parse(calls[1].init.body).message;
  assert.equal(m.topic, "news"); assert.equal(m.notification, undefined);
  assert.deepEqual(m.data, { id: "7", title: "عنوان", body: "نص", news_id: "3" });
});

test("رفض بلا سر أو بسر خاطئ أو بطريقة GET", async () => {
  const calls = [];
  for (const rq of [req(ins(rec), "bad"), req(ins(rec), null), req(null, "s3cret", "GET")]) {
    const r = await handle(rq, env, { fetch: fakeFetch(calls) });
    assert.ok([401, 405].includes(r.status));
  }
  assert.equal((await handle(req(ins(rec)), { ...env, PUSH_WEBHOOK_SECRET: "" }, { fetch: fakeFetch(calls) })).status, 401);
  assert.equal(calls.length, 0);
});

test("تخطي إشعارات المستخدم الفردية والأحداث غير INSERT والسجلات الفاسدة", async () => {
  const calls = [];
  for (const body of [ins({ ...rec, user_id: "u1" }), { ...ins(rec), type: "UPDATE" }, { ...ins(rec), table: "news" }, ins({ ...rec, title: "  " }), ins({ ...rec, id: "x" })]) {
    const r = await handle(req(body), env, { fetch: fakeFetch(calls) });
    assert.equal(r.status, 200); assert.ok((await r.json()).skipped);
  }
  assert.equal(calls.length, 0);
});

test("أخطاء: JSON تالف، حساب خدمة ناقص، فشل FCM", async () => {
  const bad = new Request("http://x/", { method: "POST", headers: { "x-webhook-secret": "s3cret" }, body: "{" });
  assert.equal((await handle(bad, env, {})).status, 400);
  assert.equal((await handle(req(ins(rec)), { ...env, FCM_SERVICE_ACCOUNT: "nope" }, {})).status, 500);
  assert.equal((await handle(req(ins(rec)), { ...env, FCM_SERVICE_ACCOUNT: "{}" }, {})).status, 500);
  assert.equal((await handle(req(ins(rec)), env, { fetch: fakeFetch([], 404) })).status, 502);
});

test("buildMessage يقصّ الطول وsafeEqual/b64url", () => {
  const m = buildMessage({ id: 1, title: "a".repeat(300), body: "b".repeat(900) });
  assert.equal(m.data.title.length, 120); assert.equal(m.data.body.length, 500); assert.equal(m.data.news_id, undefined);
  assert.ok(safeEqual("a", "a")); assert.ok(!safeEqual("a", "ab")); assert.ok(!safeEqual(undefined, "x"));
  assert.equal(b64url("??>>"), "Pz8-Pg");
});
