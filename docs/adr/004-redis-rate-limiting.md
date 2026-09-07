# ADR-004: Redis-Backed Rate Limiting

## Status
Accepted

## Context
Public-facing APIs need rate limiting to protect against abuse, brute-force attacks, and accidental overload. We need to choose between in-memory rate limiting (e.g., Guava `RateLimiter`, Bucket4j in-memory) and distributed rate limiting backed by an external store.

## Decision
We use a **Redis-backed sliding window rate limiter** implemented as a `OncePerRequestFilter`. The mechanism is:

1. On each request, compute a client key:
   - Authenticated users → `rate_limit:<userId>`
   - Anonymous users → `rate_limit:<X-Forwarded-For or remoteAddr>`
2. Execute Redis `INCR` on the key to atomically increment the request count
3. If this is the first request in the window (`count == 1`), set `EXPIRE` with the configured window duration
4. If the count exceeds `maxRequests`, return `429 Too Many Requests` with a JSON error body
5. Set response headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

**Configuration** (via `application.yml`):
- `orderflow.rate-limit.max-requests`: default 100
- `orderflow.rate-limit.window-seconds`: default 60

**Excluded paths:** `/api/auth/**` (login/register) and `/actuator/**` (health checks).

## Consequences

**Pros:**
- Distributed: rate limits are enforced consistently across multiple application instances — all instances share the same Redis counters
- Per-user limiting for authenticated users prevents one user from consuming another's quota
- Fail-open resilience: if Redis is unavailable, requests are allowed through rather than blocking all traffic — availability is prioritized over strict enforcement
- Standard rate limit headers (`X-RateLimit-*`) let clients implement backoff logic
- Simple implementation: just `INCR` + `EXPIRE` — no Lua scripts or complex data structures needed

**Cons:**
- Depends on Redis availability for enforcement (mitigated by fail-open)
- The fixed-window variant (INCR + EXPIRE) can allow up to 2x the limit at window boundaries — acceptable for our use case since we're protecting against abuse, not enforcing precise quotas
- Additional Redis round-trip per request (mitigated by Redis's sub-millisecond response times)

**Why not in-memory rate limiting?**
- In-memory limiters (e.g., `ConcurrentHashMap` with counters, Guava `RateLimiter`) are local to a single JVM
- With multiple application instances, each maintains its own counters — a client can multiply its effective limit by the number of instances
- We already run Redis for caching, so using it for rate limiting adds no new infrastructure

**Why not a gateway-level rate limiter?**
- Adding an API gateway (e.g., Spring Cloud Gateway, Kong) solely for rate limiting would be over-engineering for this application's scope
- Application-level rate limiting gives us access to the authenticated user identity for per-user limits
- If the application later moves behind a gateway, this filter can be disabled in favor of the gateway's rate limiter
