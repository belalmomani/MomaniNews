<?php
// ملف اختبار مؤقت: يتحقق أن الاستضافة تُرجع JSON نظيفاً لطلب غير متصفحي (مثل طلب التطبيق).
// لا يقرأ قاعدة البيانات ولا يحمل أسراراً. احذفه بعد انتهاء الاختبار.
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

echo json_encode(
    [
        'ok' => true,
        'php' => PHP_MAJOR_VERSION . '.' . PHP_MINOR_VERSION,
        'https' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off'),
        'pdo_mysql' => extension_loaded('pdo_mysql'),
        'curl' => extension_loaded('curl'),
        'time' => gmdate('c'),
    ],
    JSON_UNESCAPED_UNICODE
);
