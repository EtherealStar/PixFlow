# Shell, Routing, and Authentication

## Responsibility

Shell owns application startup, protected routing, responsive navigation panels, network status, and global toasts. It contains no page-specific business orchestration.

## Routes

The route table is exactly the route set in `product.md`. `/login` is public and standalone. Every other route, including `/`, declares `requiresAuth`. `/` redirects to `/chat/new` after authentication.

The Materials image-detail route is a normal protected child route. Browser back exits detail, while in-app leave guards may delay navigation until the Product Visual Analysis draft is saved, discarded, or resumed.

Removed routes are not placeholders: `/rubrics`, `/settings`, `/tasks/:taskId`, the old combined `/files`, and a dashboard Home page do not exist. The left navigation links separately to Materials and Outputs. The administrator menu contains Logout and no non-functional Settings item.

## Authentication runtime

The access token is held in runtime memory only. It is never written to localStorage, sessionStorage, IndexedDB, URL state, or Pinia persistence.

At application startup, one single-flight bootstrap calls `POST /api/auth/refresh` with the HttpOnly refresh cookie and then loads `/api/auth/me`. Business requests attach the in-memory bearer token. Only `AUTH_TOKEN_EXPIRED` may trigger one single-flight refresh and one replay of a replay-safe request. Invalid, revoked, or administrator-mismatch responses clear authentication and return to Login.

Login writes the returned access token and user into memory. Logout revokes the server session, clears memory, closes event transports, clears all application-memory message queues, and navigates to Login.

The Login page has username and password fields only. It shows `暂未开放注册` as non-interactive text. It never calls a register endpoint.

## Responsive shell

Desktop uses left navigation, main content, and right Activity. Pinned state may be stored as a non-sensitive UI preference. New activity never auto-expands the right panel.

Tablet and mobile panels use accessible drawers as defined in `product.md`. Opening one narrow-screen drawer closes the other. Focus is trapped inside, returned to the trigger on close, and background content is inert.

## Global presentation

Network loss shows one non-blocking global banner. Failures use toasts or inline error states. A trace ID is presented only as an error number in a failed operation; there is no global trace widget.

## Invariants

1. Route guards wait for the one startup bootstrap rather than launching refresh requests per navigation.
2. Login route changes never trigger refresh loops.
3. Shell mounts application-level queue, Activity, and event runtimes so SPA route changes do not destroy them.
4. Backend authentication remains the security boundary; route guards are usability controls.
