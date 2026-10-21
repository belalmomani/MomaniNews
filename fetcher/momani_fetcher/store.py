"""طبقة التخزين: Supabase REST بمفتاح service_role (من متغيرات بيئة Actions فقط، لا يُسجَّل ولا يُطبع)."""
import json
import urllib.error
import urllib.parse
import urllib.request


class StoreError(Exception):
    def __init__(self, message: str, code: int | None = None):
        super().__init__(message)
        self.code = code


class SupabaseStore:
    def __init__(self, url: str, service_key: str):
        if not url.startswith("https://"):
            raise StoreError("SUPABASE_URL must be https")
        if not service_key:
            raise StoreError("missing service key")
        self.base = url.rstrip("/") + "/rest/v1/"
        self._key = service_key

    def _headers(self) -> dict:
        h = {"apikey": self._key, "Accept": "application/json"}
        # المفاتيح الجديدة (sb_secret_...) ليست JWT فتُرسل في apikey فقط؛ المفتاح القديم (JWT) يُرسل أيضاً كـ Bearer.
        if self._key.startswith("eyJ"):
            h["Authorization"] = "Bearer " + self._key
        return h

    def _call(self, method: str, path: str, body=None, prefer: str | None = None):
        headers = self._headers()
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if prefer:
            headers["Prefer"] = prefer
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            raise StoreError(f"HTTP {e.code} on {method} {path.split('?')[0]}", e.code) from None  # لا نطبع الجسم (قد يحوي بيانات)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise StoreError(f"connection failed: {type(e).__name__}") from None
        return json.loads(raw) if raw else None

    # ---- قراءة
    def active_sources(self) -> list[dict]:
        return self._call("GET", "sources?select=id,name,url,kind,region_id&is_active=eq.true&order=id.asc")

    def active_keywords(self) -> list[dict]:
        return self._call("GET", "keywords?select=word,kind,region_id,needs_context&is_active=eq.true")

    def known_original_urls(self, urls: list[str]) -> set[str]:
        found: set[str] = set()
        for i in range(0, len(urls), 40):
            chunk = urls[i:i + 40]
            inlist = ",".join('"' + u.replace('"', "") + '"' for u in chunk)
            q = urllib.parse.quote(f"({inlist})", safe="(),\"")
            for r in self._call("GET", f"news?select=original_url&original_url=in.{q}") or []:
                found.add(r["original_url"])
            for r in self._call("GET", f"news_refs?select=url&url=in.{q}") or []:
                found.add(r["url"])
        return found

    def known_fingerprints(self, fps: list[str]) -> dict[str, int]:
        out: dict[str, int] = {}
        for i in range(0, len(fps), 40):
            q = ",".join(fps[i:i + 40])
            for r in self._call("GET", f"news?select=id,fingerprint&fingerprint=in.({q})") or []:
                out[r["fingerprint"]] = r["id"]
        return out

    def recent_titles(self, days: int = 7) -> list[tuple[int, str]]:
        from datetime import datetime, timedelta, timezone
        since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
        rows = self._call("GET", "news?select=id,title&created_at=gte." + urllib.parse.quote(since) + "&order=id.desc&limit=500") or []
        return [(r["id"], r["title"]) for r in rows]

    # ---- كتابة
    def insert_news(self, row: dict) -> int | None:
        try:
            res = self._call("POST", "news", row, prefer="return=representation")
        except StoreError as e:
            if e.code == 409:  # قيد التفرد (رابط/بصمة): الخبر موجود
                return None
            raise
        return res[0]["id"] if res else None

    def add_ref(self, news_id: int, source_name: str, url: str) -> None:
        try:
            self._call("POST", "news_refs", {"news_id": news_id, "source_name": source_name[:120], "url": url}, prefer="return=minimal")
        except StoreError as e:
            if e.code != 409:
                raise

    def source_result(self, source_id: int, ok: bool, error: str | None) -> None:
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc).isoformat()
        body = {"last_fetched_at": now, "last_status": "ok" if ok else "error", "last_error": None if ok else (error or "")[:300]}
        if ok:
            body["last_success_at"] = now
        self._call("PATCH", f"sources?id=eq.{int(source_id)}", body, prefer="return=minimal")

    def log_failure(self, source_id: int | None, url: str | None, error: str) -> None:
        self._call("POST", "failed_fetch_logs", {"source_id": source_id, "url": (url or "")[:500], "error": error[:500]}, prefer="return=minimal")

    def start_run(self, trigger: str) -> int:
        res = self._call("POST", "fetch_runs", {"trigger": trigger}, prefer="return=representation")
        return res[0]["id"]

    NEW_RUN_COLUMNS = ("items_rejected", "reject_reasons", "details")

    def finish_run(self, run_id: int, stats: dict) -> None:
        from datetime import datetime, timezone
        body = dict(stats, finished_at=datetime.now(timezone.utc).isoformat())
        try:
            self._call("PATCH", f"fetch_runs?id=eq.{int(run_id)}", body, prefer="return=minimal")
        except StoreError as e:
            if e.code != 400:
                raise
            # الترحيل 20261021100000 لم يُطبَّق بعد: نسجّل الأعمدة القديمة فقط بدل فقدان التشغيل كله
            legacy = {k: v for k, v in body.items() if k not in self.NEW_RUN_COLUMNS}
            self._call("PATCH", f"fetch_runs?id=eq.{int(run_id)}", legacy, prefer="return=minimal")
