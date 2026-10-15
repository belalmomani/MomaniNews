<?php
declare(strict_types=1);

namespace App\Http;

final class Response
{
    /**
     * @param array<string,mixed> $data
     * @param array<string,string> $headers
     */
    public static function send(int $status, array $data, array $headers = []): void
    {
        if (!headers_sent()) {
            http_response_code($status);
            header('Content-Type: application/json; charset=utf-8');
            header('Cache-Control: no-store');
            header('X-Content-Type-Options: nosniff');
            foreach ($headers as $name => $value) {
                header($name . ': ' . $value);
            }
        }
        echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    }

    /** @param array<string,string> $headers */
    public static function error(int $status, string $code, string $message, array $headers = []): void
    {
        self::send($status, ['error' => ['code' => $code, 'message' => $message]], $headers);
    }
}
