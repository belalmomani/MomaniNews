<?php
declare(strict_types=1);

namespace App\Auth;

/**
 * رموز جلسة التطبيق: JWT موقّع بـ HS256 بسر الخادم (لا جدول جلسات في تصميم الـ 11 جدولاً).
 * الرمز يحمل رقم المستخدم والانتهاء فقط. الإلغاء الفوري (حظر مستخدم مثلاً) يتطلب فحص حالة
 * المستخدم في قاعدة البيانات عند كل طلب محمي، وهذا يُضاف مع نقاط الحسابات بعد مراجعة المخطط.
 */
final class TokenService
{
    private const ALGORITHM = 'HS256';
    private const MIN_SECRET_BYTES = 32;

    /** @var string */
    private $secret;
    /** @var int */
    private $ttlSeconds;
    /** @var callable */
    private $clock;

    public function __construct(string $secret, int $ttlSeconds, ?callable $clock = null)
    {
        if (strlen($secret) < self::MIN_SECRET_BYTES) {
            throw new \InvalidArgumentException('Token secret must be at least 32 bytes');
        }
        if ($ttlSeconds < 60) {
            throw new \InvalidArgumentException('Token TTL must be at least 60 seconds');
        }
        $this->secret = $secret;
        $this->ttlSeconds = $ttlSeconds;
        $this->clock = $clock ?? function (): int {
            return time();
        };
    }

    public function issue(int $userId): string
    {
        if ($userId < 1) {
            throw new \InvalidArgumentException('Invalid user id');
        }
        $now = ($this->clock)();
        $header = Base64Url::encode(json_encode(['alg' => self::ALGORITHM, 'typ' => 'JWT'], JSON_THROW_ON_ERROR));
        $payload = Base64Url::encode(json_encode(
            ['sub' => (string)$userId, 'iat' => $now, 'exp' => $now + $this->ttlSeconds],
            JSON_THROW_ON_ERROR
        ));
        $signature = Base64Url::encode(hash_hmac('sha256', $header . '.' . $payload, $this->secret, true));
        return $header . '.' . $payload . '.' . $signature;
    }

    /** يُرجع رقم المستخدم أو يُسقط AuthException. */
    public function verify(string $token): int
    {
        list($headerPart, $payloadPart, $signaturePart) = Jwt::split($token);

        $header = Jwt::json($headerPart);
        if (($header['alg'] ?? null) !== self::ALGORITHM) {
            throw new AuthException('algorithm');
        }

        $signature = Base64Url::decode($signaturePart);
        $expected = hash_hmac('sha256', $headerPart . '.' . $payloadPart, $this->secret, true);
        if (!hash_equals($expected, $signature)) {
            throw new AuthException('bad_signature');
        }

        $claims = Jwt::json($payloadPart);
        $exp = $claims['exp'] ?? null;
        if (!is_int($exp)) {
            throw new AuthException('malformed');
        }
        if ($exp <= ($this->clock)()) {
            throw new AuthException('expired');
        }

        $sub = $claims['sub'] ?? null;
        if (!is_string($sub) || !ctype_digit($sub) || strlen($sub) > 9 || (int)$sub < 1) {
            throw new AuthException('invalid_subject');
        }
        return (int)$sub;
    }
}
