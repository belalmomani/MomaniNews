<?php
declare(strict_types=1);

namespace App;

use App\Http\ApiException;

final class Config
{
    /**
     * يقرأ app/config.php. أي نقص أو خلل يُسقط 500 برسالة عامة دون كشف المسار أو القيم.
     *
     * @return array{db:array{host:string,name:string,user:string,pass:string}}
     */
    public static function load(?string $path = null): array
    {
        $path = $path ?? (__DIR__ . '/config.php');
        if (!is_file($path)) {
            error_log('Config file missing');
            throw self::broken();
        }

        $config = require $path;
        if (!is_array($config) || !isset($config['db']) || !is_array($config['db'])) {
            error_log('Config file malformed');
            throw self::broken();
        }
        foreach (['host', 'name', 'user', 'pass'] as $key) {
            if (!isset($config['db'][$key]) || !is_string($config['db'][$key])) {
                error_log('Config file missing key: db.' . $key);
                throw self::broken();
            }
        }
        return $config;
    }

    /**
     * إعدادات المصادقة (مطلوبة فقط لنقاط الحسابات). تفشل برسالة عامة إن نقصت أو ضعف السر.
     *
     * @param array<string,mixed> $config
     * @return array{token_secret:string,google_client_id:string,token_ttl_seconds:int}
     */
    public static function auth(array $config): array
    {
        $auth = $config['auth'] ?? null;
        if (
            !is_array($auth)
            || !isset($auth['token_secret']) || !is_string($auth['token_secret']) || strlen($auth['token_secret']) < 32
            || !isset($auth['google_client_id']) || !is_string($auth['google_client_id']) || $auth['google_client_id'] === ''
        ) {
            error_log('Config auth section missing or invalid');
            throw self::broken();
        }
        $days = $auth['token_ttl_days'] ?? 30;
        if (!is_int($days) || $days < 1 || $days > 365) {
            error_log('Config auth.token_ttl_days invalid');
            throw self::broken();
        }
        return [
            'token_secret' => $auth['token_secret'],
            'google_client_id' => $auth['google_client_id'],
            'token_ttl_seconds' => $days * 86400,
        ];
    }

    private static function broken(): ApiException
    {
        return new ApiException(500, 'server_error', 'إعداد الخادم غير مكتمل');
    }
}
