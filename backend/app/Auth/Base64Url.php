<?php
declare(strict_types=1);

namespace App\Auth;

final class Base64Url
{
    public static function encode(string $binary): string
    {
        return rtrim(strtr(base64_encode($binary), '+/', '-_'), '=');
    }

    /** فك صارم: أي محرف خارج المجموعة أو طول غير ممكن يُسقط AuthException('malformed'). */
    public static function decode(string $text): string
    {
        if ($text === '' || preg_match('/^[A-Za-z0-9_-]+$/', $text) !== 1) {
            throw new AuthException('malformed');
        }
        $base64 = strtr($text, '-_', '+/');
        $remainder = strlen($base64) % 4;
        if ($remainder === 1) {
            throw new AuthException('malformed');
        }
        if ($remainder !== 0) {
            $base64 .= str_repeat('=', 4 - $remainder);
        }
        $raw = base64_decode($base64, true);
        if ($raw === false) {
            throw new AuthException('malformed');
        }
        return $raw;
    }
}
