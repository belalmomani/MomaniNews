<?php
declare(strict_types=1);

namespace App;

use App\Http\ApiException;
use PDO;
use PDOException;

final class Database
{
    /** @param array{host:string,name:string,user:string,pass:string} $db */
    public static function connect(array $db): PDO
    {
        $dsn = 'mysql:host=' . $db['host'] . ';dbname=' . $db['name'] . ';charset=utf8mb4';
        try {
            return new PDO($dsn, $db['user'], $db['pass'], [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES => false,
                PDO::ATTR_TIMEOUT => 5,
            ]);
        } catch (PDOException $e) {
            // لا نسجّل رسالة الاستثناء لأنها قد تتضمن عنوان الخادم أو اسم المستخدم.
            error_log('DB connection failed (code ' . (string)$e->getCode() . ')');
            throw new ApiException(503, 'db_unavailable', 'الخدمة غير متاحة مؤقتاً');
        }
    }
}
