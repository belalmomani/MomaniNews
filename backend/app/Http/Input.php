<?php
declare(strict_types=1);

namespace App\Http;

/** تحقق صارم من معاملات الاستعلام. أي قيمة غير صالحة تُسقط ApiException برمز 400. */
final class Input
{
    /** @param array<string,mixed> $query */
    public static function int(array $query, string $name, int $default, int $min, int $max): int
    {
        if (!array_key_exists($name, $query)) {
            return $default;
        }
        $value = $query[$name];
        if (!is_string($value) || !ctype_digit($value) || strlen($value) > 9) {
            throw self::invalid($name);
        }
        $number = (int)$value;
        if ($number < $min || $number > $max) {
            throw self::invalid($name);
        }
        return $number;
    }

    /**
     * نص حر (مثل نص البحث): يحذف محارف التحكم ويقصّ الفراغات ويتحقق من الطول بالأحرف.
     *
     * @param array<string,mixed> $query
     */
    public static function text(array $query, string $name, int $maxLength): ?string
    {
        if (!array_key_exists($name, $query) || $query[$name] === '') {
            return null;
        }
        $value = $query[$name];
        if (!is_string($value)) {
            throw self::invalid($name);
        }
        $clean = preg_replace('/[\x00-\x1F\x7F]/u', '', $value);
        if ($clean === null) { // ترميز UTF-8 غير صالح
            throw self::invalid($name);
        }
        $clean = trim($clean);
        if ($clean === '') {
            return null;
        }
        if (mb_strlen($clean, 'UTF-8') > $maxLength) {
            throw self::invalid($name);
        }
        return $clean;
    }

    /**
     * معرّف نصي قصير (مثل معرّف المنطقة): حروف لاتينية صغيرة وأرقام وشرطتان فقط.
     * الصيغة افتراض في المسودة وتُراجَع مع المخطط الفعلي.
     *
     * @param array<string,mixed> $query
     */
    public static function slug(array $query, string $name): ?string
    {
        if (!array_key_exists($name, $query) || $query[$name] === '') {
            return null;
        }
        $value = $query[$name];
        if (!is_string($value) || preg_match('/^[a-z0-9_-]{1,32}$/', $value) !== 1) {
            throw self::invalid($name);
        }
        return $value;
    }

    private static function invalid(string $name): ApiException
    {
        return new ApiException(400, 'invalid_parameter', 'قيمة المعامل غير صالحة: ' . $name);
    }
}
