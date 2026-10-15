<?php
declare(strict_types=1);

namespace App\Http;

/** خطأ متوقع يُحوَّل إلى استجابة JSON بالرمز والرسالة العربية الآمنة للعرض. */
class ApiException extends \RuntimeException
{
    /** @var int */
    private $status;

    /** @var string */
    private $errorCode;

    public function __construct(int $status, string $errorCode, string $message)
    {
        parent::__construct($message);
        $this->status = $status;
        $this->errorCode = $errorCode;
    }

    public function getStatus(): int
    {
        return $this->status;
    }

    public function getErrorCode(): string
    {
        return $this->errorCode;
    }
}
