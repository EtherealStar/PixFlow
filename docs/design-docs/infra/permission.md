# permission — Deny-first Authorization Boundary

## Responsibility

Permission evaluates whether an authenticated configured administrator may perform an action in the current runtime scope. It provides hard authorization for tools, Asset References, Proposal publication/confirmation, task commands, and child/Plan-mode restrictions.

Authentication and administrator eligibility belong to `infra/auth`. Permission consumes the already authenticated principal and still fails closed when the subject or resource proof is missing.

## Principles

1. Security is enforced by code, never only by Prompt wording or hidden UI controls.
2. Evaluation is deny-first: explicit deny wins; absence of sufficient proof is deny.
3. Agent, child Agent, and model-visible tool inputs cannot manufacture authority.
4. Canonical `referenceKey` resolution and resource ownership are rechecked at every side-effect boundary.
5. A validated Proposal still requires one explicit user confirmation. Defense-in-depth revalidation is internal and is not a second user confirmation.
6. The system has no confirmation token, challenge answer, fixed phrase, bulk confirmation level, or token store.

## Public model

```java
public interface PermissionPolicy {
    PermissionDecision evaluate(PermissionContext context, PermissionSubject subject);
}

public record PermissionContext(
    AuthenticatedPrincipal principal,
    RuntimeScope runtimeScope,
    PlanModeState planMode,
    String conversationId,
    String toolCallId
) {}

public sealed interface PermissionSubject {
    record ToolInvocation(String toolName, boolean readOnly, Map<String,Object> safeFacts)
        implements PermissionSubject {}
    record AssetAccess(String referenceKey, AssetAccessMode mode)
        implements PermissionSubject {}
    record ProposalPublication(String proposalType, List<String> referenceKeys, String payloadHash)
        implements PermissionSubject {}
    record ProposalConfirmation(String proposalId, String payloadHash)
        implements PermissionSubject {}
    record TaskCommand(String taskId, TaskCommandType command)
        implements PermissionSubject {}
}
```

`safeFacts` may carry already parsed non-secret facts used for policy selection. It cannot carry a claimed user ID, ownership result, storage key, or precomputed “allowed” flag.

## Evaluation order

```text
1. authenticated principal exists
2. auth layer says principal is still the configured administrator
3. runtime-scope deny rules
4. Plan-mode deny rules
5. tool/action classification rules
6. conversation ownership proof
7. canonical Asset Reference resolution and availability
8. Proposal/task ownership and state proof
9. allow
```

Any unavailable dependency needed to prove steps 2 or 6–8 fails closed.

## Runtime restrictions

| Scope | Allowed | Denied |
|---|---|---|
| Main Agent, normal mode | read tools; Proposal-publication tools after validation | direct pixel/model execution tools, auth/admin mutation |
| Main Agent, Plan mode | read-only search/read/inspection and plan exit | Proposal publication and every side effect |
| Explore child | explicitly whitelisted read-only search/read/inspection | Proposal publication, task commands, recursive Agent spawn |
| Internal summarization/session compression | no business tools unless explicitly required by the owning runtime | all side effects and asset mutation |

Prompt visibility is only a UX layer. The same deny rules run again at tool execution.

## Asset Reference authorization

The caller passes a backend-produced canonical key. Permission asks the Asset Reference Resolver for a typed resource and verifies:

- canonical grammar and non-deleted/processable state required by the action;
- resource belongs to the configured administrator's accessible namespace;
- PACKAGE/SKU/IMAGE kind is accepted by the requested tool;
- generated output availability when an Outputs IMAGE is referenced;
- current facts, not a stale browser display snapshot.

Object-storage keys and display paths are never authorization inputs.

## Proposal boundary

### Publication

Proposal publication is allowed only after the owning module has completed schema, reference, ownership, readiness, count, payload-hash, and domain-fact validation. Permission evaluates the resolved facts; it does not trust Agent-declared counts or ownership.

On success Conversation assigns `proposalId` and keeps the Proposal only in the active runtime. The permission decision creates no token or durable authorization grant.

### Confirmation

```text
POST .../proposals/{proposalId}/confirm
  -> authenticate and recheck configured-administrator eligibility
  -> load ephemeral Proposal
  -> recheck conversation ownership, resource availability, permission, payload hash, and pending CAS
  -> create/bind task using proposalId as business idempotency identity
```

The user clicks confirm once. Internal rechecks do not call the user again. Replays return the task already bound to the same `proposalId`.

Reject requires the same conversation/administrator proof, then deletes the ephemeral Proposal. No reason or extra prompt is required.

## Error model

| Code | Meaning |
|---|---|
| `PERMISSION_UNAUTHENTICATED` | No valid authenticated principal. |
| `PERMISSION_ADMIN_INELIGIBLE` | Principal is not the currently configured administrator. |
| `PERMISSION_SCOPE_DENIED` | Runtime scope forbids the action. |
| `PERMISSION_PLAN_MODE_DENIED` | Plan mode forbids a side effect. |
| `PERMISSION_CONVERSATION_DENIED` | Conversation ownership proof failed. |
| `PERMISSION_ASSET_DENIED` | Asset reference cannot be authorized for the requested action. |
| `PERMISSION_PROPOSAL_DENIED` | Proposal ownership/state/payload proof failed. |
| `PERMISSION_TASK_DENIED` | Task command ownership/state proof failed. |

All are terminal permission failures and are sanitized before reaching the client or model.

## Removed mechanisms

Delete the old confirmation package and all callers of:

- `ConfirmationTokenService`, `ConfirmationTokenStore`, and Redis token state;
- `ConfirmationToken`, `TokenClaims`, `ConfirmationAction`, and `ConfirmationLevel`;
- issue/verify/consume flows, token TTL, nonce, challenge, and BULK level;
- permission subjects containing a pending token;
- “confirm again because count is large” policies.

No compatibility adapter is retained.

## Contracts

| Module | Contract |
|---|---|
| `infra/auth` | Produces principal and configured-administrator eligibility; every request rechecks it. |
| `harness/tools` | Adapts tool metadata and input facts to PermissionSubject before handler execution. |
| `module/file` | Resolves canonical references and proves current resource state. |
| `module/conversation` | Owns direct Proposal confirm/reject orchestration and conversation proof. |
| `module/task` | Rechecks task/conversation ownership for cancel, retry, delete, and download. |
| `infra/cache` | No permission or confirmation-token dependency. |

Permission depends on common error/security shapes and auth-facing interfaces, not on tool handlers, business module internals, Redis, or storage SDKs.

## Verification

- deny always overrides allow;
- missing authentication, administrator mismatch, or missing ownership proof fails closed;
- Plan mode and child scopes cannot publish Proposals even if a handler is invoked directly;
- invalid/deleted/wrong-kind canonical references are denied;
- Proposal publication occurs only after full owner validation;
- one confirm creates one task and replay returns it;
- dependency/architecture scans reject every removed confirmation type and Redis token adapter;
- model-visible schemas and transcripts never contain auth credentials or internal permission facts.

## Revision Notes

- 2026-07-17 / Codex: replaced confirmation-token authorization with direct deny-first Proposal/resource authorization; removed challenge, BULK second confirmation, token contracts, and Redis token storage.
