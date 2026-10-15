<?php
declare(strict_types=1);

namespace App\Auth;

/** أدوات تحليل JWT مشتركة. لا تتحقق من أي توقيع؛ المتحقق هو مسؤولية الصنف المستدعي. */
final class Jwt
{
    private const MAX_LENGTH = 4096;

    /** @return array{0:string,1:string,2:string} الأجزاء الثلاثة كما هي (base64url) */
    public static function split(string $token): array
    {
        if ($token === '' || strlen($token) > self::MAX_LENGTH) {
            throw new AuthException('malformed');
        }
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            throw new AuthException('malformed');
        }
        return [$parts[0], $parts[1], $parts[2]];
    }

    /** @return array<string,mixed> */
    public static function json(string $base64Url): array
    {
        $data = json_decode(Base64Url::decode($base64Url), true, 8);
        if (!is_array($data)) {
            throw new AuthException('malformed');
        }
        return $data;
    }
}
