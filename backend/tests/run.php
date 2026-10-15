<?php
declare(strict_types=1);

// تشغيل: php backend/tests/run.php  (يخرج برمز 1 عند أي فشل)
require dirname(__DIR__) . '/app/autoload.php';

use App\Config;
use App\Http\ApiException;
use App\Http\Input;
use App\Http\Request;
use App\Http\Router;

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

function expectStatus(string $name, callable $fn, int $status): void
{
    try {
        $fn();
        check($name . ' (no exception thrown)', false);
    } catch (ApiException $e) {
        check($name, $e->getStatus() === $status);
    }
}

// ---- Request::path
check('path: PATH_INFO style', Request::path([
    'REQUEST_URI' => '/api/v1/index.php/health', 'SCRIPT_NAME' => '/api/v1/index.php',
]) === '/health');
check('path: rewrite style with query', Request::path([
    'REQUEST_URI' => '/api/v1/news/5?page=2', 'SCRIPT_NAME' => '/api/v1/index.php',
]) === '/news/5');
check('path: base only', Request::path([
    'REQUEST_URI' => '/api/v1/', 'SCRIPT_NAME' => '/api/v1/index.php',
]) === '/');
check('path: script at web root', Request::path([
    'REQUEST_URI' => '/health', 'SCRIPT_NAME' => '/index.php',
]) === '/health');
check('path: similar prefix is not stripped', Request::path([
    'REQUEST_URI' => '/api/v10/health', 'SCRIPT_NAME' => '/api/v1/index.php',
]) === '/api/v10/health');

// ---- Router
$router = new Router();
$handler = function (array $p): array { return $p; };
$router->add('GET', '/health', $handler);
$router->add('GET', '/news/{id}', $handler);

$r = $router->resolve('GET', '/health');
check('router: static route matches', $r['status'] === 200);
$r = $router->resolve('GET', '/news/12');
check('router: id param captured', $r['status'] === 200 && $r['params'] === ['id' => '12']);
$r = $router->resolve('GET', '/news/12/');
check('router: trailing slash ok', $r['status'] === 200);
check('router: unknown path 404', $router->resolve('GET', '/nope')['status'] === 404);
check('router: non-numeric id 404', $router->resolve('GET', '/news/abc')['status'] === 404);
check('router: 10-digit id 404', $router->resolve('GET', '/news/1234567890')['status'] === 404);
$r = $router->resolve('POST', '/health');
check('router: wrong method 405 with Allow', $r['status'] === 405 && $r['allow'] === ['GET']);

// ---- Input::int
check('int: default when missing', Input::int([], 'page', 1, 1, 100) === 1);
check('int: valid', Input::int(['page' => '7'], 'page', 1, 1, 100) === 7);
expectStatus('int: below min', function () { Input::int(['page' => '0'], 'page', 1, 1, 100); }, 400);
expectStatus('int: above max', function () { Input::int(['page' => '101'], 'page', 1, 1, 100); }, 400);
expectStatus('int: negative', function () { Input::int(['page' => '-1'], 'page', 1, 1, 100); }, 400);
expectStatus('int: exponent form', function () { Input::int(['page' => '1e3'], 'page', 1, 1, 100); }, 400);
expectStatus('int: leading space', function () { Input::int(['page' => ' 5'], 'page', 1, 1, 100); }, 400);
expectStatus('int: empty string', function () { Input::int(['page' => ''], 'page', 1, 1, 100); }, 400);
expectStatus('int: array injection', function () { Input::int(['page' => ['1']], 'page', 1, 1, 100); }, 400);
expectStatus('int: absurdly long', function () { Input::int(['page' => str_repeat('9', 30)], 'page', 1, 1, 100); }, 400);

// ---- Input::text
check('text: missing is null', Input::text([], 'q', 100) === null);
check('text: empty is null', Input::text(['q' => ''], 'q', 100) === null);
check('text: whitespace-only is null', Input::text(['q' => "   \t "], 'q', 100) === null);
check('text: trims', Input::text(['q' => '  عبين '], 'q', 100) === 'عبين');
check('text: strips control chars', Input::text(['q' => "اب\x00ج\x1F"], 'q', 100) === 'ابج');
check('text: exactly max length ok', Input::text(['q' => str_repeat('ب', 100)], 'q', 100) === str_repeat('ب', 100));
expectStatus('text: over max length (counts characters)', function () {
    Input::text(['q' => str_repeat('ب', 101)], 'q', 100);
}, 400);
expectStatus('text: invalid UTF-8', function () { Input::text(['q' => "\xFF\xFE"], 'q', 100); }, 400);
expectStatus('text: array injection', function () { Input::text(['q' => ['x']], 'q', 100); }, 400);

// ---- Input::slug
check('slug: missing is null', Input::slug([], 'region') === null);
check('slug: valid', Input::slug(['region' => 'abeen'], 'region') === 'abeen');
expectStatus('slug: uppercase rejected', function () { Input::slug(['region' => 'Abeen'], 'region'); }, 400);
expectStatus('slug: sql-ish rejected', function () { Input::slug(['region' => "a' OR '1'='1"], 'region'); }, 400);
expectStatus('slug: arabic rejected', function () { Input::slug(['region' => 'عبين'], 'region'); }, 400);

// ---- Config (لا يُسرّب المسار في الرسالة)
try {
    Config::load('/nonexistent/path/config.php');
    check('config: missing file throws', false);
} catch (ApiException $e) {
    check('config: missing file => 500', $e->getStatus() === 500);
    check('config: message does not leak path', strpos($e->getMessage(), 'nonexistent') === false);
}

$tmp = tempnam(sys_get_temp_dir(), 'cfg');
file_put_contents($tmp, "<?php return ['db' => ['host' => 'h', 'name' => 'n', 'user' => 'u']];");
expectStatus('config: missing key => 500', function () use ($tmp) { Config::load($tmp); }, 500);
file_put_contents($tmp, "<?php return ['db' => ['host' => 'h', 'name' => 'n', 'user' => 'u', 'pass' => 'p']];");
check('config: valid file loads', Config::load($tmp)['db']['name'] === 'n');
unlink($tmp);

echo "\n$total checks, $failures failed\n";
exit($failures === 0 ? 0 : 1);
