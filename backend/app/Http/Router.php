<?php
declare(strict_types=1);

namespace App\Http;

/** موجّه بسيط. المعاملات {name} أرقام صحيحة فقط (1 إلى 9 خانات). */
final class Router
{
    /** @var array<int,array{0:string,1:string,2:callable}> */
    private $routes = [];

    public function add(string $method, string $pattern, callable $handler): void
    {
        $parts = [];
        foreach (explode('/', trim($pattern, '/')) as $segment) {
            if (preg_match('/^\{(\w+)\}$/', $segment, $m) === 1) {
                $parts[] = '(?P<' . $m[1] . '>[0-9]{1,9})';
            } else {
                $parts[] = preg_quote($segment, '#');
            }
        }
        $regex = '#^/' . implode('/', $parts) . '$#';
        $this->routes[] = [strtoupper($method), $regex, $handler];
    }

    /**
     * @return array{status:int, handler?:callable, params?:array<string,string>, allow?:array<int,string>}
     */
    public function resolve(string $method, string $path): array
    {
        $method = strtoupper($method);
        $path = '/' . trim($path, '/');
        $allowed = [];

        foreach ($this->routes as $route) {
            list($routeMethod, $regex, $handler) = $route;
            if (preg_match($regex, $path, $matches) !== 1) {
                continue;
            }
            if ($routeMethod === $method) {
                $params = [];
                foreach ($matches as $key => $value) {
                    if (is_string($key)) {
                        $params[$key] = $value;
                    }
                }
                return ['status' => 200, 'handler' => $handler, 'params' => $params];
            }
            $allowed[] = $routeMethod;
        }

        if ($allowed !== []) {
            return ['status' => 405, 'allow' => array_values(array_unique($allowed))];
        }
        return ['status' => 404];
    }
}
