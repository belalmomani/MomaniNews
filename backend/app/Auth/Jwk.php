<?php
declare(strict_types=1);

namespace App\Auth;

/** يحوّل مفتاح RSA عام بصيغة JWK إلى PEM (SubjectPublicKeyInfo) لاستخدامه مع openssl_verify. */
final class Jwk
{
    private const MIN_MODULUS_BYTES = 256; // 2048 بت على الأقل

    /** @param array<string,mixed> $jwk */
    public static function toPem(array $jwk): string
    {
        if (($jwk['kty'] ?? null) !== 'RSA' || !is_string($jwk['n'] ?? null) || !is_string($jwk['e'] ?? null)) {
            throw new AuthException('bad_key');
        }
        $modulus = ltrim(Base64Url::decode($jwk['n']), "\0");
        $exponent = ltrim(Base64Url::decode($jwk['e']), "\0");
        if (strlen($modulus) < self::MIN_MODULUS_BYTES || $exponent === '') {
            throw new AuthException('bad_key');
        }

        $rsaPublicKey = self::sequence(self::integer($modulus) . self::integer($exponent));
        $bitString = "\x03" . self::length(strlen($rsaPublicKey) + 1) . "\x00" . $rsaPublicKey;
        // AlgorithmIdentifier: rsaEncryption (1.2.840.113549.1.1.1) مع NULL
        $algorithm = "\x30\x0d\x06\x09\x2a\x86\x48\x86\xf7\x0d\x01\x01\x01\x05\x00";
        $spki = self::sequence($algorithm . $bitString);

        return "-----BEGIN PUBLIC KEY-----\n"
            . chunk_split(base64_encode($spki), 64, "\n")
            . "-----END PUBLIC KEY-----\n";
    }

    private static function length(int $length): string
    {
        if ($length < 128) {
            return chr($length);
        }
        $bytes = ltrim(pack('N', $length), "\0");
        return chr(0x80 | strlen($bytes)) . $bytes;
    }

    private static function sequence(string $content): string
    {
        return "\x30" . self::length(strlen($content)) . $content;
    }

    private static function integer(string $unsigned): string
    {
        if ($unsigned === '' || ord($unsigned[0]) > 0x7f) {
            $unsigned = "\0" . $unsigned;
        }
        return "\x02" . self::length(strlen($unsigned)) . $unsigned;
    }
}
