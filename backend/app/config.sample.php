<?php
// انسخ هذا الملف إلى config.php في نفس المجلد (app/) واملأ القيم على الخادم فقط.
// config.php لا يُرفع إلى GitHub ولا يُشارَك في أي محادثة. هذا الملف قالب بقيم وهمية.
return [
    'db' => [
        'host' => 'YOUR_DB_HOST',
        'name' => 'YOUR_DB_NAME',
        'user' => 'YOUR_DB_USER',
        'pass' => 'YOUR_DB_PASSWORD',
    ],
    // مطلوب لنقاط الحسابات (المرحلة 4). ولّد السر على الخادم بـ:
    //   php -r "echo bin2hex(random_bytes(32));"
    // ولا تضع القيمة الحقيقية في أي مستودع أو محادثة.
    'auth' => [
        'token_secret' => 'REPLACE_WITH_64_HEX_CHARS_FROM_random_bytes_32',
        'token_ttl_days' => 30,
        'google_client_id' => 'YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com',
    ],
];
