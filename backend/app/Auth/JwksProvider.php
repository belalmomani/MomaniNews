<?php
declare(strict_types=1);

namespace App\Auth;

interface JwksProvider
{
    /**
     * قائمة مفاتيح JWK العامة.
     *
     * @return array<int,array<string,mixed>>
     */
    public function keys(bool $forceRefresh = false): array;
}
