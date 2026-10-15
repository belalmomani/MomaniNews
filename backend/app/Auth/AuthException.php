<?php
declare(strict_types=1);

namespace App\Auth;

/**
 * فشل مصادقة. السبب (reason) رمز داخلي للسجلات والاختبارات فقط، ولا يُعرض للعميل،
 * ولا يحتوي أبداً على الرمز أو أجزاء منه.
 */
final class AuthException extends \RuntimeException
{
    /** @var string */
    private $reason;

    public function __construct(string $reason)
    {
        parent::__construct('auth:' . $reason);
        $this->reason = $reason;
    }

    public function getReason(): string
    {
        return $this->reason;
    }
}
