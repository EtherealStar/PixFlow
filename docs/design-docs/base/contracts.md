# Shared Boundary Contracts

## Purpose

This document defines which shapes may cross bounded-context boundaries. Shared contracts exist only to remove a real dependency cycle; they are not a general DTO bucket and do not own business workflows.

The canonical cross-module material identity is specified in [asset-references.md](asset-references.md). Conversation, Agent, File, Vision, DAG, Task, Imagegen, and Web must use that contract rather than inventing local attachment identifiers.

## Current shared shapes

### Asset references

The wire-facing view is:

```json
{
  "referenceKey": "package:123/image:789",
  "kind": "IMAGE",
  "sourceType": "ORIGINAL",
  "displayPath": "summer.zip / SKU-001 / front.jpg"
}
```

The backend owns serialization and parsing. Consumers pass keys verbatim. Storage keys, bytes, and display names are never resource identities.

### Progress and Activity projection

Task, upload, and extraction modules publish domain events in their own APIs. The application/state bridge projects them into the frontend Activity view. The Activity DTO is owned by the application boundary because it is a read model, not a shared domain entity.

### Proposal ownership

Proposal belongs to Conversation. DAG and Imagegen expose validated proposal payloads through their public module APIs; Conversation assigns `proposalId`, keeps a short-lived pending value in the active runtime, emits `proposal_ready`, and owns confirm/reject commands.

Proposal types are not placed in a neutral shared module merely because several modules participate. Dependencies must point through the owning module's public API or a narrow owner-defined port.

## Removed legacy contracts

The target design has no public or internal compatibility path for:

- `ConfirmationToken`, `TokenClaims`, `ConfirmationAction`, or `ConfirmationLevel`;
- `ConfirmationTokenStore` or Redis confirmation-token state;
- challenge records, challenge answers, bulk second-confirmation levels, or one-time confirmation tokens;
- `PendingPlanPort`, `PendingPlanProposal`, or a durable `pending_plan` confirmation queue;
- legacy proposal payload identities based on independent `packageId`, `imageIds`, or `source_image_ids` fields.

User confirmation is one direct action on a validated Proposal. `proposalId` is the stable business idempotency identity and is propagated into Task creation. Defense-in-depth revalidation is backend-internal and does not create another user confirmation.

## Dependency rules

1. A shared Java type is admitted only when it is a pure shape, prevents a concrete dependency cycle, and keeps the shared artifact dependency-free.
2. Owner-specific DTOs remain with their owner even when multiple downstream modules consume them.
3. Cross-module APIs use canonical Asset References for material identity.
4. Web read models such as Activity remain application-owned projections.
5. No module may reintroduce confirmation tokens or durable pending-plan storage under another name.

## Tests and guards

- architecture tests prevent `contracts` from depending on infra, harness, module, or agent packages;
- contract tests cover canonical reference parsing, round-trip serialization, permission resolution, and rejection of non-canonical keys;
- dependency scans reject imports of the removed confirmation and pending-plan packages;
- API tests prove Proposal confirm replay returns the task already bound to the same `proposalId`.

## Revision Notes

- 2026-07-17 / Codex: removed the confirmation-token, second-confirmation, and durable pending-plan target design; established canonical Asset References and owner-defined Proposal/Activity boundaries as the active contracts.
