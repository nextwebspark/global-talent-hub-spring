# Authentication — App-owned JWT

The app issues and validates its **own** HS256 JWTs. Supabase Auth is no longer
used for identity. (Supabase Postgres may still be the database host, but the
`auth.*` schema and Supabase-issued tokens are out of the loop.)

## Components

| Piece | File | Role |
|-------|------|------|
| User store | `entity/User.java` → table `hak_auth_users` | email + bcrypt `password_hash` |
| Token mint/verify | `security/JwtService.java` | `issue(userId, email)` / `parse(token)`, HS256 |
| Request auth | `security/JwtAuthFilter.java` | reads Bearer (or `?access_token=` on the SSE route), attaches `AuthenticatedUser` |
| Signup / login / me | `service/AuthService.java`, `controller/AuthController.java` | atomic signup, login, auth context |
| Bcrypt bean | `config/SecurityConfig.java` | `BCryptPasswordEncoder` |

## Endpoints

- `POST /api/auth/signup` *(public)* — body `{ email, password, name, org:{ name, slug?, teamSize?, region? } }`.
  Atomically creates the user, organization, owner membership, and profile; returns
  `{ token, user:{id,email}, org, role:"owner" }`. `201`.
- `POST /api/auth/login` *(public)* — body `{ email, password }`. Returns
  `{ token, user, org, role }`. `401` on bad credentials (generic message, no enumeration).
- `GET /api/auth/me` *(authed)* — current `{ user, org, role, profile, lastLoginAt }`.

The client stores `token` in `localStorage` (`gth_token`) and sends it as
`Authorization: Bearer <token>` on `/api` calls (and `?access_token=` on the SSE route).

## Config

```
app.jwt.secret           # HS256 signing secret, >= 32 bytes. Env: APP_JWT_SECRET
app.jwt.expiry-seconds   # token lifetime, default 604800 (7d). Env: APP_JWT_EXPIRY_SECONDS
```

Non-test profiles refuse to start if the secret is the placeholder (`JwtService.verifySecret`).

## DB

Run `db-extent/add_users_auth.sql` (in the React repo) once. It creates
`hak_auth_users` — a **separate** table from the pre-existing `hak_users`
(varchar id / username / password), which is left untouched.

## Deferred: Google / Microsoft OAuth2 (SSO)

Not implemented this pass. To add later:

1. **Backend** — add `spring-boot-starter-oauth2-client`, register Google as a provider
   (`spring.security.oauth2.client.registration.google.*` with client-id/secret).
   Add a `/login/oauth2/...` success handler that, on the verified Google identity:
   - looks up `hak_auth_users` by email; creates the row if absent (no password — mark
     the account as SSO-only, e.g. a nullable `password_hash` + a `provider` column);
   - if the user has no org, returns a token whose holder is routed to org setup;
   - mints the **same app JWT** via `JwtService.issue(...)` and hands it to the client
     (redirect with the token, or set it and bounce to the SPA).
   The rest of the system is unchanged — every downstream API already trusts the app JWT,
   so SSO only needs to *produce* one.
2. **Frontend** — re-add the SSO buttons (the `SsoButtons` component in
   `client/src/features/auth/components.tsx` is still present) wired to
   `window.location = "/oauth2/authorization/google"`. On return, read the token from the
   redirect, `setAccessToken(token)`, and let the Gate hydrate via `/api/auth/me`.
3. **Schema** — make `password_hash` nullable and add `provider text` (`"password"` |
   `"google"` | …) to `hak_auth_users` so password and SSO accounts coexist.

No other part of the auth chain changes — OAuth is purely an additional way to obtain
the app's own JWT.
