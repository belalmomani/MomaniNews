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
    ctx = b.new_context(); pg = ctx.new_page(); calls, route = run("admin"); pg.route("https://*.supabase.co/**", route)
    pg.on("dialog", lambda d: d.accept())
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
    ctx.close(); b.close()
bad = [n for n, ok in results if not ok]
print("FAILED:", bad if bad else "none"); sys.exit(1 if bad else 0)
