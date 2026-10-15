<?php
declare(strict_types=1);

namespace App\Auth;

/**
 * يجلب مفاتيح Google العامة ويخزّنها في ملف مؤقت.
 * - التحديث الإجباري (عند kid مجهول) لا يتجاوز مرة كل 60 ثانية، لمنع إغراق الخادم بطلبات خارجية.
 * - عند فشل الجلب نستخدم النسخة المخزنة القديمة إن وُجدت؛ وإلا AuthException('jwks_unavailable').
 */
final class GoogleJwksProvider implements JwksProvider
{
    public const URL = 'https://www.googleapis.com/oauth2/v3/certs';
    private const MIN_FORCE_INTERVAL_SECONDS = 60;

    /** @var string */
    private $cacheFile;
    /** @var callable */
    private $fetcher;
    /** @var int */
    private $ttlSeconds;

    public function __construct(string $cacheFile, ?callable $fetcher = null, int $ttlSeconds = 3600)
    {
        $this->cacheFile = $cacheFile;
        $this->fetcher = $fetcher ?? [self::class, 'defaultFetch'];
        $this->ttlSeconds = $ttlSeconds;
    }

    public function keys(bool $forceRefresh = false): array
    {
        $cached = $this->readCache();
        if ($cached !== null) {
            $age = time() - $cached['mtime'];
            if (!$forceRefresh && $age < $this->ttlSeconds) {
                return $cached['keys'];
            }
            if ($forceRefresh && $age < self::MIN_FORCE_INTERVAL_SECONDS) {
                return $cached['keys'];
            }
        }

        try {
            $body = (string)($this->fetcher)(self::URL);
            $keys = self::parse($body);
            $this->writeCache($body);
            return $keys;
        } catch (\Throwable $e) {
            error_log('JWKS refresh failed: ' . get_class($e));
            if ($cached !== null) {
                return $cached['keys'];
            }
            throw new AuthException('jwks_unavailable');
        }
    }

    /** الجلب الافتراضي (curl إن توفر وإلا file_get_contents). لم يُختبر هنا لأن الشبكة محجوبة في بيئة التطوير. */
    public static function defaultFetch(string $url): string
    {
        if (function_exists('curl_init')) {
            $curl = curl_init($url);
            curl_setopt_array($curl, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_TIMEOUT => 5,
                CURLOPT_CONNECTTIMEOUT => 3,
                CURLOPT_FOLLOWLOCATION => false,
                CURLOPT_SSL_VERIFYPEER => true,
                CURLOPT_SSL_VERIFYHOST => 2,
                CURLOPT_HTTPHEADER => ['Accept: application/json'],
            ]);
            $body = curl_exec($curl);
            $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
            curl_close($curl);
            if (!is_string($body) || $status !== 200) {
                throw new AuthException('jwks_fetch_failed');
            }
            return $body;
        }

        $context = stream_context_create([
            'http' => ['timeout' => 5, 'header' => "Accept: application/json\r\n"],
            'ssl' => ['verify_peer' => true, 'verify_peer_name' => true],
        ]);
        $body = @file_get_contents($url, false, $context);
        if (!is_string($body)) {
            throw new AuthException('jwks_fetch_failed');
        }
        return $body;
    }

    /** @return array<int,array<string,mixed>> */
    private static function parse(string $body): array
    {
        $data = json_decode($body, true, 8);
        if (!is_array($data) || !isset($data['keys']) || !is_array($data['keys']) || $data['keys'] === []) {
            throw new AuthException('jwks_malformed');
        }
        $keys = [];
        foreach ($data['keys'] as $key) {
            if (is_array($key)) {
                $keys[] = $key;
            }
        }
        if ($keys === []) {
            throw new AuthException('jwks_malformed');
        }
        return $keys;
    }

    /** @return array{keys:array<int,array<string,mixed>>,mtime:int}|null */
    private function readCache(): ?array
    {
        // حداثة الكاش تُقرأ من وقت الملف الفعلي، لا من ذاكرة stat المؤقتة في PHP.
        clearstatcache(true, $this->cacheFile);
        if (!is_file($this->cacheFile)) {
            return null;
        }
        $body = @file_get_contents($this->cacheFile);
        $mtime = @filemtime($this->cacheFile);
        if (!is_string($body) || !is_int($mtime)) {
            return null;
        }
        try {
            return ['keys' => self::parse($body), 'mtime' => $mtime];
        } catch (AuthException $e) {
            return null;
        }
    }

    private function writeCache(string $body): void
    {
        $dir = dirname($this->cacheFile);
        if (!is_dir($dir)) {
            @mkdir($dir, 0755, true);
        }
        $tmp = $this->cacheFile . '.' . bin2hex(random_bytes(4)) . '.tmp';
        if (@file_put_contents($tmp, $body, LOCK_EX) === false || !@rename($tmp, $this->cacheFile)) {
            @unlink($tmp);
            error_log('JWKS cache write failed');
        }
    }
}
