<?php
declare(strict_types=1);

// البنية على الاستضافة: htdocs/api/v1/index.php و htdocs/app/ (شقيقان).
require dirname(__DIR__, 2) . '/app/bootstrap.php';

use App\Config;
use App\Database;
use App\Http\ApiException;
use App\Http\Request;
use App\Http\Response;
use App\Http\Router;

$router = new Router();

$router->add('GET', '/health', function (array $params): array {
    return ['status' => 'ok'];
});

// فحص الاتصال بقاعدة البيانات فقط (لا يقرأ جداول ولا يكشف تفاصيل).
$router->add('GET', '/health/db', function (array $params): array {
    Database::connect(Config::load()['db']);
    return ['db' => 'ok'];
});

// مسارات الأخبار (/news و /news/{id}) تُضاف بعد مراجعة مخطط قاعدة البيانات الفعلي.

try {
    $match = $router->resolve(
        (string)($_SERVER['REQUEST_METHOD'] ?? 'GET'),
        Request::path($_SERVER)
    );

    if ($match['status'] === 404) {
        throw new ApiException(404, 'not_found', 'المسار غير موجود');
    }
    if ($match['status'] === 405) {
        Response::error(
            405,
            'method_not_allowed',
            'الطريقة غير مسموحة',
            ['Allow' => implode(', ', $match['allow'] ?? [])]
        );
        exit;
    }

    $data = ($match['handler'])($match['params'] ?? []);
    Response::send(200, $data);
} catch (ApiException $e) {
    Response::error($e->getStatus(), $e->getErrorCode(), $e->getMessage());
} catch (Throwable $e) {
    // نسجّل نوع الخطأ وموضعه فقط؛ الرسائل قد تحوي بيانات حساسة.
    error_log(get_class($e) . ' at ' . basename($e->getFile()) . ':' . $e->getLine());
    Response::error(500, 'server_error', 'حدث خطأ في الخادم');
}
