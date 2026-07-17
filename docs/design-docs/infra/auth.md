# infra/auth — Administrator-only Authentication

## Deployment model

PixFlow is deployed publicly but is accessible only to one configured administrator username:

```text
PIXFLOW_AUTH_ADMIN_USERNAME=<administrator username>
```

The database may contain other historical accounts. Their presence never fails application startup. They simply cannot authenticate or use an existing token.

## Authentication rule

Login, refresh, `/api/auth/me`, and every JWT-authenticated request compare the resolved account username with the configured administrator username. A mismatch returns the same ordinary authentication failure used for invalid credentials; it does not reveal that an account exists or is not the administrator.

This check is server-side on every authentication boundary. Frontend route guards are not a security boundary.

## Registration

Registration is completely disabled. There is no controller mapping, service entry point, anonymous allow-list entry, or compatibility endpoint for `/api/auth/register`; requests return 404. Existing accounts are managed outside the public application.

## Tokens

Access JWT claims remain minimal:

```text
sub, uname, iat, exp, jti, typ
```

No role claim is needed because administrator eligibility is checked against configuration and the current database account on each request.

The short-lived access token is returned in the login/refresh body and held only in frontend memory. Refresh credentials are opaque random tokens delivered as Secure, HttpOnly, SameSite cookies and stored server-side only as hashes. Refresh rotates/revokes according to the existing session policy.

Logout revokes the refresh session, blacklists the remaining access-token JTI lifetime, and clears the cookie.

## Public endpoints

The anonymous surface is limited to login, refresh, logout cookie cleanup, and infrastructure endpoints explicitly required for health/static delivery. Register is not anonymous because it does not exist.

## Failure and throttling

Invalid credentials, non-administrator accounts, disabled accounts, revoked tokens, and administrator mismatch use non-enumerating authentication errors. Login throttling applies before expensive password verification and does not weaken the administrator check.

## Invariants

1. Multiple database accounts are allowed; exactly the configured username may access the application.
2. Administrator authorization is not inferred from a frontend flag or JWT role.
3. Refresh tokens are never readable by JavaScript.
4. Registration cannot be re-enabled by exposing a hidden frontend form; the backend route is absent.
