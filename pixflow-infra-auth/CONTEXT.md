# Administrator Authentication

This context owns login sessions and continued eligibility to access the publicly deployed application.

## Language

**Configured Administrator**:
The one account username selected by deployment configuration as eligible to access PixFlow.
_Avoid_: first database user, role=ADMIN

**Administrator Eligibility**:
The current fact that an authenticated account still matches the Configured Administrator and remains usable.
_Avoid_: JWT role claim

**Access Session**:
The revocable authentication relationship represented by short-lived access credentials and a rotating refresh credential.
_Avoid_: user account, browser login flag

**Historical Account**:
A database account that may remain stored but is not the Configured Administrator and cannot access the application.
_Avoid_: startup error, secondary administrator

