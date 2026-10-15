<?php
declare(strict_types=1);

namespace App\Auth;

/**
 * يتحقق من Google ID Token على الخادم: الخوارزمية RS256 فقط، التوقيع بمفتاح Google العام،
 * الجهة المُصدرة، الجمهور (Client ID لتطبيقك)، الانتهاء، وتأكيد البريد.
 * أي فشل يُسقط AuthException بسبب داخلي؛ لا يُكشف السبب للعميل.
 */
final class GoogleIdTokenVerifier
{
    private const ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];
    private const MAX_FUTURE_IAT_SECONDS = 300;

    /** @var string */
    private $clientId;
    /** @var JwksProvider */
    private $jwks;
    /** @var callable */
    private $clock;

    public function __construct(string $clientId, JwksProvider $jwks, ?callable $clock = null)
    {
        if ($clientId === '') {
            throw new \InvalidArgumentException('Google client id must not be empty');
        }
        $this->clientId = $clientId;
        $this->jwks = $jwks;
        $this->clock = $clock ?? function (): int {
            return time();
        };
    }

    /**
     * @return array{sub:string,email:string,name:string,picture:?string}
     */
    public function verify(string $idToken): array
    {
        list($headerPart, $payloadPart, $signaturePart) = Jwt::split($idToken);

        $header = Jwt::json($headerPart);
        // منع هجمات خلط الخوارزميات (alg=none أو HS256 بمفتاح عام): RS256 فقط.
        if (($header['alg'] ?? null) !== 'RS256') {
            throw new AuthException('algorithm');
        }
        $kid = $header['kid'] ?? null;
        if (!is_string($kid) || $kid === '') {
            throw new AuthException('malformed');
        }

        $pem = Jwk::toPem($this->findKey($kid));
        $signature = Base64Url::decode($signaturePart);
        if (openssl_verify($headerPart . '.' . $payloadPart, $signature, $pem, OPENSSL_ALGO_SHA256) !== 1) {
            throw new AuthException('bad_signature');
        }

        // لا نثق بمحتوى الحمولة إلا بعد نجاح التوقيع.
        $claims = Jwt::json($payloadPart);
        $now = ($this->clock)();

        if (!in_array($claims['iss'] ?? null, self::ISSUERS, true)) {
            throw new AuthException('issuer');
        }
        if (($claims['aud'] ?? null) !== $this->clientId) {
            throw new AuthException('audience');
        }
        $exp = $claims['exp'] ?? null;
        if (!is_int($exp) || $exp <= $now) {
            throw new AuthException('expired');
        }
        $iat = $claims['iat'] ?? null;
        if (is_int($iat) && $iat > $now + self::MAX_FUTURE_IAT_SECONDS) {
            throw new AuthException('invalid_time');
        }

        $sub = $claims['sub'] ?? null;
        if (!is_string($sub) || $sub === '' || strlen($sub) > 255) {
            throw new AuthException('invalid_subject');
        }
        $email = $claims['email'] ?? null;
        if (!is_string($email) || $email === '' || strlen($email) > 254) {
            throw new AuthException('email_missing');
        }
        $verified = $claims['email_verified'] ?? null;
        if ($verified !== true && $verified !== 'true') {
            throw new AuthException('email_unverified');
        }

        $name = $claims['name'] ?? '';
        $name = is_string($name) ? mb_substr($name, 0, 100, 'UTF-8') : '';
        $picture = $claims['picture'] ?? null;
        $picture = (is_string($picture) && strncmp($picture, 'https://', 8) === 0 && strlen($picture) <= 512)
            ? $picture
            : null;

        return ['sub' => $sub, 'email' => $email, 'name' => $name, 'picture' => $picture];
    }

    /** @return array<string,mixed> */
    private function findKey(string $kid): array
    {
        foreach ($this->jwks->keys(false) as $key) {
            if (($key['kid'] ?? null) === $kid) {
                return $key;
            }
        }
        // قد يكون Google دوّر المفاتيح: نحاول تحديثاً واحداً (المزوّد يحدّه زمنياً).
        foreach ($this->jwks->keys(true) as $key) {
            if (($key['kid'] ?? null) === $kid) {
                return $key;
            }
        }
        throw new AuthException('unknown_key');
    }
}
