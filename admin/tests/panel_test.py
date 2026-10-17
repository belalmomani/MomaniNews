import base64, json, threading, http.server, functools, sys
from playwright.sync_api import sync_playwright

def b64(d): return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")
TOKEN = b64({"alg":"none"}) + "." + b64({"sub":"uid-1"}) + ".sig"

handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory="/home/claude/MomaniNews/admin")
handler.log_message = lambda *a, **k: None
srv = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

results = []
def check(name, cond): results.append((name, bool(cond))); print(("PASS " if cond else "FAIL ") + name)

def run(role):
    calls = []
    news = [{"id": 1, "title": "<img src=x onerror=window.__xss=1>خبر", "excerpt": "م", "status": "pending", "region_id": "abeen", "source_name": "ص", "created_at": "2026-10-16T01:00:00Z", "original_url": "javascript:alert(1)"}]
    def route(r):
        req = r.request; u = req.url; m = req.method
        calls.append((m, u, req.headers.get("authorization"), req.post_data))
        def j(body, status=200): r.fulfill(status=status, content_type="application/json", body=json.dumps(body))
        if "/rest/v1/users" in u: return j([{"id": "uid-1", "display_name": "بلال", "role": role}])
        if "/rest/v1/regions" in u: return j([{"id": "abeen", "name_ar": "عبين"}, {"id": "sakhra", "name_ar": "صخرة"}])
        if "/rest/v1/news" in u and m == "GET": return j(news)
        if "/rest/v1/news" in u and m == "PATCH": return j([news[0]])
        if "/rest/v1/news" in u and m == "POST": return r.fulfill(status=201, body="")
        if "/rest/v1/comments" in u and m == "GET": return j([{"id": 9, "body": "تعليق <b>x</b>", "status": "visible", "created_at": "2026-10-16T01:00:00Z", "news_id": 1, "user_id": "uid-2"}])
        if "/rest/v1/comments" in u and m in ("PATCH", "DELETE"): return r.fulfill(status=204, body="")
        if "/rest/v1/verification_requests" in u and m == "GET": return j([{"id": 3, "user_id": "uid-2", "full_name": "علي أحمد", "document_path": "uid-2/id.jpg", "status": "pending", "created_at": "2026-10-16T01:00:00Z"}])
        if "/rest/v1/verification_requests" in u and m == "PATCH": return r.fulfill(status=204, body="")
        if "/storage/v1/object/sign/" in u: return j({"signedURL": "/object/sign/verification-docs/uid-2/id.jpg?token=t"})
        if "/rest/v1/advertisements" in u and m == "GET" and "status=eq.approved" in u: return j([{"id": 6, "title": "<u>معتمد</u>", "starts_at": "2026-10-01T00:00:00Z", "ends_at": "2099-01-01T00:00:00Z", "region_id": "abeen", "link_url": "javascript:alert(1)"}])
        if "/rest/v1/advertisements" in u and m == "DELETE": return r.fulfill(status=204, body="")
        if "/rest/v1/sources" in u and m == "GET": return j([{"id": 7, "name": "<b>مصدر</b>", "url": "https://s.example/feed", "kind": "rss", "region_id": None, "is_active": True}])
        if "/rest/v1/sources" in u and m in ("POST", "PATCH", "DELETE"): return r.fulfill(status=201 if m == "POST" else 204, body="")
        if "/rest/v1/keywords" in u and m == "GET": return j([{"id": 8, "word": "<i>مطر</i>", "region_id": "abeen", "is_active": False}])
        if "/rest/v1/keywords" in u and m in ("POST", "PATCH", "DELETE"): return r.fulfill(status=201 if m == "POST" else 204, body="")
        if "/rest/v1/advertisements" in u and m == "GET": return j([{"id": 4, "user_id": "uid-2", "title": "إعلان", "body": "نص", "link_url": "javascript:alert(1)", "contact": "07", "region_id": "abeen", "created_at": "2026-10-16T01:00:00Z"}])
        if "/rest/v1/advertisements" in u and m == "PATCH": return r.fulfill(status=204, body="")
        if "/rest/v1/notifications" in u and m == "GET": return j([{"id": 5, "user_id": None, "title": "<i>قديم</i>", "body": None, "news_id": 1, "created_at": "2026-10-16T01:00:00Z"}])
        if "/rest/v1/notifications" in u and m in ("POST", "DELETE"): return r.fulfill(status=201 if m == "POST" else 204, body="")
        if "/rest/v1/rpc/admin_set_user_flags" in u: return r.fulfill(status=204, body="")
        return j({}, 404)
    return calls, route

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(); pg.goto("http://127.0.0.1:8765/index.html")
    check("no session -> login visible", pg.is_visible("#login") and not pg.is_visible("#app"))
    pg.close()
    ctx = b.new_context(); pg = ctx.new_page(); calls, route = run("user"); pg.route("https://*.supabase.co/**", route)
    pg.goto("http://127.0.0.1:8765/index.html#access_token=%s&refresh_token=r&expires_in=3600" % TOKEN)
    pg.wait_for_selector("#denied:not(.hidden)")
    check("non-admin -> denied, no app", pg.is_visible("#denied") and not pg.is_visible("#app"))
    check("non-admin never fetched news", not any("/rest/v1/news" in c[1] for c in calls))
    check("hash cleared from URL", "access_token" not in pg.url)
    ctx.close()
    ctx = b.new_context(); pg = ctx.new_page(); calls, route = run("admin"); ctx.route("https://*.supabase.co/**", route)
    ANSWER = {"v": "7"}
    pg.on("dialog", lambda d: d.accept(ANSWER["v"]) if d.type == "prompt" else d.accept())
    pg.goto("http://127.0.0.1:8765/index.html#access_token=%s&refresh_token=r&expires_in=3600" % TOKEN)
    pg.wait_for_selector("#list .card")
    check("admin sees app", pg.is_visible("#app"))
    check("pending filter sent", any("status=eq.pending" in c[1] for c in calls if c[0]=="GET"))
    check("bearer sent", any(c[2] == "Bearer " + TOKEN for c in calls))
    check("XSS title rendered as text", pg.evaluate("window.__xss") is None and "<img" in pg.inner_text("#list h3"))
    check("javascript: link not rendered", pg.locator("#list a").count() == 0)
    pg.on("console", lambda m: print("CONSOLE", m.text)); pg.click("#list button.primary")
    pg.wait_for_selector("#msg.ok")
    patch = [c for c in calls if c[0]=="PATCH"]
    check("approve sends PATCH status=approved", patch and json.loads(patch[0][3]) == {"status": "approved"} and "id=eq.1" in patch[0][1])
    pg.click("[data-tab=add]")
    check("add form shown, list hidden", pg.is_visible("#addForm") and not pg.is_visible("#list"))
    pg.fill("#fTitle", "عنوان"); pg.fill("#fContent", "نص"); pg.check("#fPublish")
    pg.click("#btnAdd"); pg.wait_for_selector("#msg.ok")
    post = [c for c in calls if c[0]=="POST"]
    body = json.loads(post[0][3]) if post else {}
    check("add posts approved row", body.get("status") == "approved" and body.get("region_id") == "abeen" and body.get("title") == "عنوان")
    pg.fill("#fTitle", "x"); pg.fill("#fContent", "y"); pg.fill("#fImage", "http://insecure/x.png")
    n_before = len([c for c in calls if c[0]=="POST"]); pg.click("#btnAdd")
    pg.wait_for_selector("#msg.err")
    check("http image rejected client-side", len([c for c in calls if c[0]=="POST"]) == n_before)
    # ---- دفعة 2
    def tabto(name, sel):
        pg.click("[data-tab=%s]" % name); pg.wait_for_selector(sel)
    tabto("comments", "#list .card")
    check("comment text escaped", pg.locator("#list b").count() == 0 and "<b>x</b>" in pg.inner_text("#list"))
    pg.click("text=إخفاء"); pg.wait_for_selector("#msg.ok")
    c = [x for x in calls if x[0]=="PATCH" and "/comments" in x[1]]
    check("hide comment PATCH hidden", c and json.loads(c[0][3]) == {"status": "hidden"} and "id=eq.9" in c[0][1])
    tabto("verif", "#list .card")
    check("verif lists pending", any("status=eq.pending" in x[1] for x in calls if "verification_requests" in x[1]))
    with ctx.expect_page() as newp: pg.click("text=عرض المستند")
    newp.value.wait_for_url("**/storage/v1/object/sign/**", timeout=5000)
    check("document opens signed url", "/storage/v1/object/sign/verification-docs/uid-2/id.jpg?token=t" in newp.value.url)
    pg.click("#list button.primary"); pg.wait_for_selector("#msg.ok")
    v = [x for x in calls if x[0]=="PATCH" and "verification_requests" in x[1]]
    check("verif approve PATCH", v and json.loads(v[0][3]) == {"status": "approved"})
    sign = [x for x in calls if "/storage/v1/object/sign/" in x[1]]
    check("sign request path encoded + admin token", sign and "uid-2/id.jpg" in sign[0][1] and sign[0][2] == "Bearer " + TOKEN)
    tabto("ads", "#list .card")
    check("ad javascript: link not rendered", pg.locator("#list a").count() == 0)
    check("approved ads section lists with approved filter", any("status=eq.approved" in x[1] for x in calls if "advertisements" in x[1]))
    check("approved ad title escaped, no link", pg.locator("#list u").count() == 0 and "<u>معتمد</u>" in pg.inner_text("#list"))
    pg.click("text=إيقاف"); pg.wait_for_selector("#msg.ok")
    stop = [x for x in calls if x[0]=="PATCH" and "advertisements?id=eq.6" in x[1]]
    check("stop ad PATCH rejected", stop and json.loads(stop[0][3]).get("status") == "rejected")
    ANSWER["v"] = "5"
    pg.click("text=تمديد"); pg.wait_for_selector("#msg.ok")
    ext = [x for x in calls if x[0]=="PATCH" and "advertisements?id=eq.6" in x[1] and "ends_at" in (x[3] or "")]
    check("extend ad sends ends_at only", ext and list(json.loads(ext[0][3]).keys()) == ["ends_at"] and json.loads(ext[0][3])["ends_at"].startswith("2099-01-0"))
    pg.click("#list button.danger >> text=حذف"); pg.wait_for_selector("#msg.ok")
    check("delete ad sent", any(x[0]=="DELETE" and "advertisements?id=eq.6" in x[1] for x in calls))
    tabto("sources", "#list form")
    check("source name escaped", pg.locator("#list b").count() == 0 and "<b>مصدر</b>" in pg.inner_text("#list"))
    pg.fill("#list input:not([type=url])", "مصدر جديد"); pg.fill("#list input[type=url]", "https://new.example/rss")
    pg.click("#list form button[type=submit]"); pg.wait_for_selector("#msg.ok")
    sp = [x for x in calls if x[0]=="POST" and "/sources" in x[1]]
    check("source POST body", sp and json.loads(sp[0][3]) == {"name": "مصدر جديد", "url": "https://new.example/rss", "kind": "manual"})
    pg.click("text=تعطيل"); pg.wait_for_selector("#msg.ok")
    sx = [x for x in calls if x[0]=="PATCH" and "/sources?id=eq.7" in x[1]]
    check("source toggle PATCH is_active false", sx and json.loads(sx[0][3]) == {"is_active": False})
    tabto("keywords", "#list form")
    check("keyword escaped", pg.locator("#list i").count() == 0 and "<i>مطر</i>" in pg.inner_text("#list"))
    pg.fill("#list input", "حصاد"); pg.select_option("#list select", "sakhra")
    pg.click("#list form button[type=submit]"); pg.wait_for_selector("#msg.ok")
    kp = [x for x in calls if x[0]=="POST" and "/keywords" in x[1]]
    check("keyword POST body", kp and json.loads(kp[0][3]) == {"word": "حصاد", "region_id": "sakhra"})
    pg.click("text=تفعيل"); pg.wait_for_selector("#msg.ok")
    kx = [x for x in calls if x[0]=="PATCH" and "/keywords?id=eq.8" in x[1]]
    check("keyword toggle PATCH is_active true", kx and json.loads(kx[0][3]) == {"is_active": True})
    tabto("users", "#list .card")
    check("users tab shows card", pg.locator("#list .card").count() >= 1)
    pg.click("text=حظر"); pg.wait_for_selector("#msg.ok")
    rpc = [x for x in calls if "rpc/admin_set_user_flags" in x[1]]
    check("ban calls rpc", rpc and json.loads(rpc[0][3]) == {"p_user": "uid-1", "p_role": "admin", "p_banned": True})
    tabto("notifs", "#list form")
    check("notif title escaped", pg.locator("#list i").count() == 0)
    pg.fill("#list input:not([type=number])", "إشعار جديد"); pg.fill("#list input[type=number]", "1")
    pg.click("#list form button[type=submit]"); pg.wait_for_selector("#msg.ok")
    nposts = [x for x in calls if x[0]=="POST" and "/notifications" in x[1]]
    check("notif broadcast POST body", nposts and json.loads(nposts[0][3]) == {"title": "إشعار جديد", "news_id": 1})
    pg.click("#list .card button.danger"); pg.wait_for_selector("#msg.ok")
    check("notif delete sent", any(x[0]=="DELETE" and "/notifications?id=eq.5" in x[1] for x in calls))
    ctx.close(); b.close()
bad = [n for n, ok in results if not ok]
print("FAILED:", bad if bad else "none"); sys.exit(1 if bad else 0)
