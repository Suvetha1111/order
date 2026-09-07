# ADR-003: Stateless JWT Authentication

## Status
Accepted

## Context
The application needs an authentication mechanism for its REST API. Two common approaches exist: server-side sessions (stored in memory or a shared store like Redis) and stateless JWT tokens. We need to choose one that aligns with the system's architecture and operational requirements.

## Decision
We use **stateless JWT authentication** via the JJWT library (0.12.6). The flow is:

1. User registers via `POST /api/auth/register` → receives a JWT
2. User logs in via `POST /api/auth/login` → receives a JWT
3. Subsequent requests include the JWT in the `Authorization: Bearer <token>` header
4. A `JwtAuthenticationFilter` (extending `OncePerRequestFilter`) extracts, validates, and sets the `SecurityContext` on every request
5. No session is stored on the server — the token itself carries the user's identity and roles

The JWT payload includes:
- `sub`: user ID (UUID)
- `email`: user's email
- `roles`: list of role names (e.g., `["CUSTOMER", "ADMIN"]`)
- `iat` / `exp`: issued-at and expiration timestamps

## Consequences

**Pros:**
- Stateless: no server-side session store needed — the application can scale horizontally without sticky sessions or a shared session store
- Self-contained: the token carries all the information needed to authorize a request (roles, user ID), avoiding a database lookup on every request
- Simple to implement with Spring Security's filter chain — a single `OncePerRequestFilter` handles extraction and validation
- Works naturally with REST APIs — no cookies or CSRF tokens needed
- Easy to test: integration tests simply pass the token as a header

**Cons:**
- Tokens cannot be individually revoked before expiration (mitigated by short expiration times)
- Token size grows with claims — but our payload is small (ID, email, roles)
- Secret key management: the signing secret must be kept secure (configured via environment variable, not hardcoded)

**Why not server-side sessions?**
- Sessions require a shared store (Redis, database) for horizontal scaling — adding infrastructure complexity
- Session lookup on every request adds latency
- For a stateless REST API, sessions add unnecessary coupling between the client and a specific server instance
- We already use Redis for caching and rate limiting; adding session storage would mix concerns and increase Redis load

**Security measures:**
- JWT secret is configurable via `JWT_SECRET` environment variable (not hardcoded)
- Tokens have a configurable expiration time
- `JwtAuthenticationFilter` validates the token signature and expiration on every request
- Invalid or expired tokens result in a 401 response
- Role-based access control (`CUSTOMER`, `ADMIN`) is enforced via Spring Security's `@PreAuthorize` and `SecurityConfig` rules
