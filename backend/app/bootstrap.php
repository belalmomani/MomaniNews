<?php
declare(strict_types=1);

// لا تُعرض أخطاء PHP للعميل أبداً؛ تُسجَّل في سجل الخادم فقط.
ini_set('display_errors', '0');
ini_set('log_errors', '1');
error_reporting(E_ALL);

require __DIR__ . '/autoload.php';
