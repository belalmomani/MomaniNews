<?php
declare(strict_types=1);

namespace App\Http;

final class Request
{
    /**
     * يستخرج مسار الـ API النسبي (مثل /news/5) من متغيرات الخادم، سواء عبر إعادة الكتابة
     * (/api/v1/news/5) أو عبر PATH_INFO (/api/v1/index.php/news/5).
     *
     * @param array<string,mixed> $server
     */
    public static function path(array $server): string
    {
        $uri = (string)($server['REQUEST_URI'] ?? '/');
        $uriPath = parse_url($uri, PHP_URL_PATH);
        if (!is_string($uriPath) || $uriPath === '') {
            $uriPath = '/';
        }

        $script = (string)($server['SCRIPT_NAME'] ?? '');
        $dir = rtrim(str_replace('\\', '/', dirname($script)), '/');

        if ($script !== '' && strncmp($uriPath, $script, strlen($script)) === 0) {
            $path = substr($uriPath, strlen($script));
        } elseif ($dir !== '' && ($uriPath === $dir || strncmp($uriPath, $dir . '/', strlen($dir) + 1) === 0)) {
            $path = substr($uriPath, strlen($dir));
        } else {
            $path = $uriPath;
        }

        return '/' . trim((string)$path, '/');
    }
}
