<?php
declare(strict_types=1);

// تشغيل: php backend/tests/auth_tests.php  (يخرج برمز 1 عند أي فشل)
require dirname(__DIR__) . '/app/autoload.php';

use App\Auth\AuthException;
use App\Auth\Base64Url;
use App\Auth\GoogleIdTokenVerifier;
use App\Auth\GoogleJwksProvider;
use App\Auth\Jwk;
use App\Auth\JwksProvider;
use App\Auth\TokenService;

$failures = 0;
$total = 0;

function check(string $name, bool $ok): void
{
    global $failures, $total;
    $total++;
    if (!$ok) {
        $failures++;
        echo "FAIL  $name\n";
    } else {
        echo "ok    $name\n";
    }
}

function expectAuth(string $name, callable $fn, string $reason): void
{
    try {
        $fn();
        check($name . ' (no exception thrown)', false);
    } catch (AuthException $e) {
        $ok = $e->getReason() === $reason;
        check($name, $ok);
        if (!$ok) {
            echo "      expected reason '$reason', got '" . $e->getReason() . "'\n";
        }
    }
}

function expectThrows(string $name, callable $fn, string $class): void
{
    try {
        $fn();
        check($name . ' (no exception thrown)', false);
    } catch (\Throwable $e) {
        check($name, $e instanceof $class);
    }
}

final class FakeJwks implements JwksProvider
{
    /** @var array<int,array<string,mixed>> */
    public $initial = [];
    /** @var array<int,array<string,mixed>> */
    public $afterRefresh = [];
    /** @var array<int,bool> */
    public $calls = [];

    public function keys(bool $forceRefresh = false): array
    {
        $this->calls[] = $forceRefresh;
        return $forceRefresh && $this->afterRefresh !== [] ? $this->afterRefresh : $this->initial;
    }
}

// ================= Base64Url
$bytes = random_bytes(37);
check('b64url: roundtrip', Base64Url::decode(Base64Url::encode($bytes)) === $bytes);
check('b64url: no padding or +/ in output', preg_match('/^[A-Za-z0-9_-]+$/', Base64Url::encode(random_bytes(64))) === 1);
foreach (['', 'ab+c', 'ab/c', 'abc=', 'a', 'abcde', 'ab cd'] as $bad) {
    expectAuth('b64url: rejects ' . json_encode($bad), function () use ($bad) { Base64Url::decode($bad); }, 'malformed');
}

// ================= TokenService
$secret = str_repeat('s', 32);
$now = 1800000000;
$clock = function () use (&$now): int { return $now; };
$svc = new TokenService($secret, 3600, $clock);

$token = $svc->issue(42);
check('token: roundtrip returns user id', $svc->verify($token) === 42);
expectAuth('token: wrong secret', function () use ($token, $clock) {
    (new TokenService(str_repeat('x', 32), 3600, $clock))->verify($token);
}, 'bad_signature');

list($h, $p, $s) = explode('.', $token);
$forgedPayload = Base64Url::encode(json_encode(['sub' => '1', 'iat' => $now, 'exp' => $now + 3600]));
expectAuth('token: tampered payload', function () use ($svc, $h, $forgedPayload, $s) {
    $svc->verify($h . '.' . $forgedPayload . '.' . $s);
}, 'bad_signature');

$noneHeader = Base64Url::encode(json_encode(['alg' => 'none', 'typ' => 'JWT']));
expectAuth('token: alg=none rejected', function () use ($svc, $noneHeader, $p) {
    $svc->verify($noneHeader . '.' . $p . '.');
}, 'algorithm'); // فحص الخوارزمية يسبق فك التوقيع
expectAuth('token: alg=none with fake signature rejected', function () use ($svc, $noneHeader, $p) {
    $svc->verify($noneHeader . '.' . $p . '.' . Base64Url::encode('x'));
}, 'algorithm');

$now += 3601;
expectAuth('token: expired', function () use ($svc, $token) { $svc->verify($token); }, 'expired');
$now = 1800000000;

foreach (['', 'abc', 'a.b', 'a.b.c.d', str_repeat('a', 5000)] as $bad) {
    expectAuth('token: malformed ' . substr(json_encode($bad), 0, 20), function () use ($svc, $bad) { $svc->verify($bad); }, 'malformed');
}
expectThrows('token: short secret refused', function () { new TokenService('short', 3600); }, \InvalidArgumentException::class);
expectThrows('token: tiny TTL refused', function () use ($secret) { new TokenService($secret, 10); }, \InvalidArgumentException::class);
expectThrows('token: invalid user id refused', function () use ($svc) { $svc->issue(0); }, \InvalidArgumentException::class);

// ================= Jwk + GoogleIdTokenVerifier (مفتاح RSA حقيقي يُولَّد للاختبار فقط)
$rsa = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
$other = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);

function jwkOf($key, string $kid): array
{
    $d = openssl_pkey_get_details($key);
    return ['kty' => 'RSA', 'kid' => $kid, 'alg' => 'RS256', 'use' => 'sig',
        'n' => Base64Url::encode($d['rsa']['n']), 'e' => Base64Url::encode($d['rsa']['e'])];
}

/** @param array<string,mixed> $headerOver @param array<string,mixed> $claimsOver */
function idToken($key, array $headerOver, array $claimsOver, int $now, string $clientId): string
{
    $header = array_merge(['alg' => 'RS256', 'kid' => 'k1', 'typ' => 'JWT'], $headerOver);
    $claims = array_merge([
        'iss' => 'https://accounts.google.com', 'aud' => $clientId, 'sub' => '1234567890',
        'email' => 'user@example.invalid', 'email_verified' => true, 'name' => 'اسم المستخدم',
        'picture' => 'https://example.invalid/p.png', 'iat' => $now, 'exp' => $now + 3600,
    ], $claimsOver);
    $signing = Base64Url::encode(json_encode($header)) . '.' . Base64Url::encode(json_encode($claims));
    openssl_sign($signing, $sig, $key, OPENSSL_ALGO_SHA256);
    return $signing . '.' . Base64Url::encode($sig);
}

$clientId = 'test-client.apps.googleusercontent.com';
$jwks = new FakeJwks();
$jwks->initial = [jwkOf($rsa, 'k1')];
$verifier = new GoogleIdTokenVerifier($clientId, $jwks, $clock);

$ok = $verifier->verify(idToken($rsa, [], [], $now, $clientId));
check('google: valid token accepted', $ok['sub'] === '1234567890' && $ok['email'] === 'user@example.invalid');
check('google: name kept', $ok['name'] === 'اسم المستخدم');
check('google: https picture kept', $ok['picture'] === 'https://example.invalid/p.png');
$noPic = $verifier->verify(idToken($rsa, [], ['picture' => 'http://example.invalid/p.png'], $now, $clientId));
check('google: non-https picture dropped', $noPic['picture'] === null);
$strTrue = $verifier->verify(idToken($rsa, [], ['email_verified' => 'true'], $now, $clientId));
check('google: email_verified "true" accepted', $strTrue['sub'] === '1234567890');
$longName = $verifier->verify(idToken($rsa, [], ['name' => str_repeat('ب', 300)], $now, $clientId));
check('google: name truncated to 100 chars', mb_strlen($longName['name'], 'UTF-8') === 100);

expectAuth('google: wrong audience', function () use ($verifier, $rsa, $now) {
    $verifier->verify(idToken($rsa, [], ['aud' => 'someone-else'], $now, 'x'));
}, 'audience');
expectAuth('google: wrong issuer', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, [], ['iss' => 'https://evil.example'], $now, $clientId));
}, 'issuer');
expectAuth('google: expired', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, [], ['exp' => $now - 1], $now, $clientId));
}, 'expired');
expectAuth('google: iat far in the future', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, [], ['iat' => $now + 4000], $now, $clientId));
}, 'invalid_time');
expectAuth('google: email unverified', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, [], ['email_verified' => false], $now, $clientId));
}, 'email_unverified');
expectAuth('google: email missing', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, [], ['email' => ''], $now, $clientId));
}, 'email_missing');
expectAuth('google: HS256 confusion rejected', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, ['alg' => 'HS256'], [], $now, $clientId));
}, 'algorithm');
expectAuth('google: alg=none rejected', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, ['alg' => 'none'], [], $now, $clientId));
}, 'algorithm');
expectAuth('google: signed by another key', function () use ($verifier, $other, $now, $clientId) {
    $verifier->verify(idToken($other, [], [], $now, $clientId));
}, 'bad_signature');
expectAuth('google: tampered payload', function () use ($verifier, $rsa, $now, $clientId) {
    list($h, $p, $s) = explode('.', idToken($rsa, [], [], $now, $clientId));
    $claims = json_decode(Base64Url::decode($p), true);
    $claims['email'] = 'admin@example.invalid';
    $verifier->verify($h . '.' . Base64Url::encode(json_encode($claims)) . '.' . $s);
}, 'bad_signature');
expectAuth('google: unknown kid', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, ['kid' => 'nope'], [], $now, $clientId));
}, 'unknown_key');
expectAuth('google: missing kid', function () use ($verifier, $rsa, $now, $clientId) {
    $verifier->verify(idToken($rsa, ['kid' => ''], [], $now, $clientId));
}, 'malformed');
foreach (['', 'a.b', 'a.b.c'] as $bad) {
    expectAuth('google: garbage ' . json_encode($bad), function () use ($verifier, $bad) { $verifier->verify($bad); }, 'malformed');
}
expectThrows('google: empty client id refused', function () use ($jwks) { new GoogleIdTokenVerifier('', $jwks); }, \InvalidArgumentException::class);

// تدوير المفاتيح: kid غير موجود في الكاش ويظهر بعد التحديث الإجباري
$rot = new FakeJwks();
$rot->initial = [jwkOf($other, 'old')];
$rot->afterRefresh = [jwkOf($rsa, 'k1')];
$rotVerifier = new GoogleIdTokenVerifier($clientId, $rot, $clock);
$rotVerifier->verify(idToken($rsa, [], [], $now, $clientId));
check('google: key rotation triggers one forced refresh', $rot->calls === [false, true]);

// Jwk
expectAuth('jwk: non-RSA key rejected', function () { Jwk::toPem(['kty' => 'EC', 'n' => 'AQAB', 'e' => 'AQAB']); }, 'bad_key');
expectAuth('jwk: short modulus rejected', function () {
    Jwk::toPem(['kty' => 'RSA', 'n' => Base64Url::encode(str_repeat("\x01", 64)), 'e' => 'AQAB']);
}, 'bad_key');
check('jwk: PEM is accepted by openssl', openssl_pkey_get_public(Jwk::toPem(jwkOf($rsa, 'k1'))) !== false);

// ================= GoogleJwksProvider (جالب وهمي، لا اتصال شبكي)
$dir = sys_get_temp_dir() . '/jwks_test_' . bin2hex(random_bytes(4));
$cacheFile = $dir . '/jwks.json';
$body = json_encode(['keys' => [jwkOf($rsa, 'k1')]]);
$fetches = 0;
$okFetcher = function (string $url) use (&$fetches, $body): string { $fetches++; return $body; };
$provider = new GoogleJwksProvider($cacheFile, $okFetcher, 3600);

check('jwks: first call fetches', count($provider->keys()) === 1 && $fetches === 1);
$provider->keys();
check('jwks: second call served from cache', $fetches === 1);
$provider->keys(true);
check('jwks: forced refresh throttled within 60s', $fetches === 1);

touch($cacheFile, time() - 120);
$provider->keys(true);
check('jwks: forced refresh allowed after 60s', $fetches === 2);

touch($cacheFile, time() - 7200);
$failing = new GoogleJwksProvider($cacheFile, function (string $url): string { throw new \RuntimeException('network down'); }, 3600);
check('jwks: stale cache used when fetch fails', count($failing->keys()) === 1);

$badBody = new GoogleJwksProvider($dir . '/other.json', function (string $url): string { return '{"keys":[]}'; });
expectAuth('jwks: empty key list treated as failure', function () use ($badBody) { $badBody->keys(); }, 'jwks_unavailable');
$noCache = new GoogleJwksProvider($dir . '/none.json', function (string $url): string { throw new \RuntimeException('down'); });
expectAuth('jwks: no cache and fetch fails', function () use ($noCache) { $noCache->keys(); }, 'jwks_unavailable');

array_map('unlink', glob($dir . '/*') ?: []);
@rmdir($dir);

echo "\n$total checks, $failures failed\n";
exit($failures === 0 ? 0 : 1);
