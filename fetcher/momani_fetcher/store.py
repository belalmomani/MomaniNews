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
        return self._call("GET", "sources?select=id,name,url,kind,region_id,last_success_at,origin&is_active=eq.true&order=id.asc")

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

    # ---- سجل المصادر المكتشفة (يتطلب الترحيل 20261021120000)
    def upsert_sources(self, rows: list[dict]) -> None:
        """يضيف المصادر الجديدة فقط (المكرر بالرابط يُتجاهل). قبل الترحيل: بلا عمود origin."""
        try:
            self._call("POST", "sources?on_conflict=url", rows, prefer="resolution=ignore-duplicates,return=minimal")
        except StoreError as e:
            if e.code != 400:
                raise
            plain = [{k: v for k, v in r.items() if k != "origin"} for r in rows]
            self._call("POST", "sources?on_conflict=url", plain, prefer="resolution=ignore-duplicates,return=minimal")

    def add_source(self, row: dict) -> int | None:
        try:
            res = self._call("POST", "sources", row, prefer="return=representation")
        except StoreError as e:
            if e.code == 409:
                return None
            raise
        return res[0]["id"] if res else None

    def source_domains(self) -> set[str]:
        from urllib.parse import urlparse
        rows = self._call("GET", "sources?select=url&limit=1000") or []
        return {(urlparse(r["url"]).hostname or "").lower().removeprefix("www.") for r in rows}

    def news_domains(self, limit: int = 300) -> set[str]:
        from urllib.parse import urlparse
        rows = self._call("GET", f"news?select=original_url&original_url=not.is.null&order=id.desc&limit={int(limit)}") or []
        return {(urlparse(r["original_url"]).hostname or "").lower().removeprefix("www.") for r in rows}

    def note_candidate(self, domain: str, via: str = "search", relevant: bool = False) -> None:
        try:
            self._call("POST", "source_candidates?on_conflict=domain", {"domain": domain, "via": via, "relevant": relevant},
                       prefer="resolution=ignore-duplicates,return=minimal")
            if relevant:  # دليل صلة جديد على نطاق معروف
                self._call("PATCH", f"source_candidates?domain=eq.{urllib.parse.quote(domain)}&relevant=eq.false", {"relevant": True}, prefer="return=minimal")
        except StoreError as e:
            if e.code not in (400, 404):
                raise

    def candidates_due(self, limit: int = 40) -> list[dict]:
        from datetime import datetime, timezone
        now = urllib.parse.quote(datetime.now(timezone.utc).isoformat())
        return self._call("GET", f"source_candidates?select=domain,via,relevant,status&status=in.(new,rejected,blocked,stale)&next_check_at=lte.{now}&order=relevant.desc,found_at.asc&limit={int(limit)}") or []

    def update_candidate(self, domain: str, fields: dict) -> None:
        self._call("PATCH", f"source_candidates?domain=eq.{urllib.parse.quote(domain)}", fields, prefer="return=minimal")

    def stale_auto_sources(self, cutoff_iso: str) -> list[dict]:
        """مصادر آلية/بحث فعّالة لم تنجح منذ [cutoff]: مرشحة للتعطيل (اليدوية لا تُمس أبداً)."""
        q = urllib.parse.quote(cutoff_iso)
        return self._call("GET", f"sources?select=id,url,name&is_active=eq.true&origin=eq.auto&last_status=eq.error&or=(last_success_at.is.null,last_success_at.lt.{q})&created_at=lt.{q}") or []

    def set_source_active(self, source_id: int, active: bool) -> None:
        self._call("PATCH", f"sources?id=eq.{int(source_id)}", {"is_active": active}, prefer="return=minimal")

    def known_seen_urls(self, urls: list[str]) -> set[str]:
        """روابط صفحات فُحصت سابقاً (جدول fetch_seen). غيابه (قبل الترحيل) = لا ذاكرة، لا خطأ."""
        found: set[str] = set()
        try:
            for i in range(0, len(urls), 40):
                chunk = urls[i:i + 40]
                inlist = ",".join('"' + u.replace('"', "") + '"' for u in chunk)
                q = urllib.parse.quote(f"({inlist})", safe="(),\"")
                for r in self._call("GET", f"fetch_seen?select=url&url=in.{q}") or []:
                    found.add(r["url"])
        except StoreError as e:
            if e.code not in (400, 404):
                raise
        return found

    def mark_seen(self, urls: list[str], source_id: int | None = None) -> None:
        rows = [{"url": u[:1000], "source_id": source_id} for u in dict.fromkeys(urls)]
        try:
            for i in range(0, len(rows), 100):
                self._call("POST", "fetch_seen?on_conflict=url", rows[i:i + 100], prefer="resolution=ignore-duplicates,return=minimal")
        except StoreError as e:
            if e.code not in (400, 404):
                raise

    def recent_titles(self, days: int = 7) -> list[tuple[int, str]]:
        from datetime import datetime, timedelta, timezone
        since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
        rows = self._call("GET", "news?select=id,title&created_at=gte." + urllib.parse.quote(since) + "&order=id.desc&limit=500") or []
        return [(r["id"], r["title"]) for r in rows]

    # ---- كتابة
    category_supported = True   # يصير False إن لم يُطبَّق الترحيل 20261025100000 بعد (فلا نفقد الأخبار)

    def insert_news(self, row: dict) -> int | None:
        if not self.category_supported:
            row = {k: v for k, v in row.items() if k != "category"}
        try:
            res = self._call("POST", "news", row, prefer="return=representation")
        except StoreError as e:
            if e.code == 409:  # قيد التفرد (رابط/بصمة): الخبر موجود
                return None
            if e.code == 400 and "category" in row and "category" in str(e):
                self.category_supported = False
                return self.insert_news(row)
            if e.code == 400 and ("image_url" in row or "video_url" in row):
                # رابط وسائط رفضه قيد القاعدة: نحفظ الخبر بلا وسائط بدل أن نفقده
                return self.insert_news({k: v for k, v in row.items() if k not in ("image_url", "video_url")})
            raise
        return res[0]["id"] if res else None

    def add_ref(self, news_id: int, source_name: str, url: str) -> None:
        try:
            self._call("POST", "news_refs", {"news_id": news_id, "source_name": source_name[:120], "url": url}, prefer="return=minimal")
        except StoreError as e:
            if e.code != 409:
                raise

    def source_result(self, source_id: int, ok: bool, error: str | None, advance: bool = True) -> None:
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc).isoformat()
        body = {"last_fetched_at": now, "last_status": "ok" if ok else "error", "last_error": None if ok else (error or "")[:300]}
        if ok and advance:   # advance=False: الاستدراك لم يكتمل فنُبقي نقطة آخر نجاح لتُستكمل الفجوة في الدورة التالية
            body["last_success_at"] = now
        self._call("PATCH", f"sources?id=eq.{int(source_id)}", body, prefer="return=minimal")

    def update_source_url(self, source_id: int, url: str) -> None:
        self._call("PATCH", f"sources?id=eq.{int(source_id)}", {"url": url}, prefer="return=minimal")

    # ---- فحص روابط الأخبار (يتطلب الترحيل 20261022100000)
    def recent_news_links(self, days: int = 21, limit: int = 300) -> list[dict]:
        from datetime import datetime, timedelta, timezone
        since = urllib.parse.quote((datetime.now(timezone.utc) - timedelta(days=days)).isoformat())
        return self._call("GET", f"news?select=id,title,original_url&original_url=not.is.null&created_at=gte.{since}&order=id.desc&limit={int(limit)}") or []

    def link_checks_for(self, ids: list[int]) -> dict[int, dict]:
        out: dict[int, dict] = {}
        for i in range(0, len(ids), 80):
            q = ",".join(str(int(x)) for x in ids[i:i + 80])
            for r in self._call("GET", f"link_checks?select=news_id,status,attempts,next_check_at&news_id=in.({q})") or []:
                out[r["news_id"]] = r
        return out

    def save_link_check(self, row: dict) -> None:
        self._call("POST", "link_checks?on_conflict=news_id", row, prefer="resolution=merge-duplicates,return=minimal")

    def blocked_publishers(self) -> list[str]:
        """قيم الجدول blocked_publishers (الترحيل 20261025110000). غيابه = قائمة فارغة، فلا يتعطل الجلب."""
        try:
            return [r["value"] for r in (self._call("GET", "blocked_publishers?select=value&limit=500") or []) if r.get("value")]
        except StoreError as e:
            if e.code in (400, 404):
                return []
            raise

    # ---- تصنيف الأخبار القديمة (يتطلب الترحيل 20261025100000)
    def unclassified_news(self, limit: int = 500, after_id: int = 0) -> list[dict]:
        return self._call("GET", f"news?select=id,title,excerpt&category=is.null&id=gt.{int(after_id)}&order=id.asc&limit={int(limit)}") or []

    def set_category(self, news_id: int, category: str) -> None:
        self._call("PATCH", f"news?id=eq.{int(news_id)}&category=is.null", {"category": category}, prefer="return=minimal")

    # ---- تعبئة صور/فيديو الأخبار القديمة (backfill_media)
    def news_without_image(self, limit: int = 100, before_id: int | None = None) -> list[dict]:
        cond = f"&id=lt.{int(before_id)}" if before_id else ""
        return self._call("GET", f"news?select=id,original_url&status=eq.approved&image_url=is.null&original_url=not.is.null{cond}&order=id.desc&limit={int(limit)}") or []

    def set_media(self, news_id: int, image_url: str | None, video_url: str | None) -> None:
        """يكتب الوسائط فقط حيث الحقل فارغ (لا يستبدل صورة أو فيديو موجودَين)."""
        if image_url:
            self._call("PATCH", f"news?id=eq.{int(news_id)}&image_url=is.null", {"image_url": image_url}, prefer="return=minimal")
        if video_url:
            self._call("PATCH", f"news?id=eq.{int(news_id)}&video_url=is.null", {"video_url": video_url}, prefer="return=minimal")

    def failing_sources(self) -> list[dict]:
        return self._call("GET", "sources?select=id,name,url,kind,last_error&is_active=eq.true&last_status=eq.error&origin=neq.search&order=id.asc&limit=60") or []

    def recent_runs(self, limit: int = 8) -> list[dict]:
        return self._call("GET", f"fetch_runs?select=id,started_at,finished_at,sources_total,sources_failed,items_seen,error_summary&order=started_at.desc&limit={int(limit)}") or []

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
