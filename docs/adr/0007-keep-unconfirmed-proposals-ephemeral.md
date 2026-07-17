---
status: accepted
---

# Keep unconfirmed Proposals ephemeral and confirm them directly

An unconfirmed Proposal exists only in the active Conversation/browser runtime after complete backend validation. PixFlow does not persist it in a pending-plan table, Redis recovery store, transcript metadata, or durable audit record. Each Proposal is independently confirmed or rejected through a direct API; there is no challenge, confirmation token, bulk second confirmation, or client-generated idempotency key.

This keeps rejected and expired plans out of durable history and matches the product expectation that the Agent's own proposal text already remains in conversation context. The accepted tradeoff is that reload or process loss can discard an unresolved Proposal; the user can ask the Agent to generate it again. Once confirmed, `proposalId` becomes the task's stable business idempotency identity, so execution replay remains safe.

