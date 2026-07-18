# Conversation
This context owns conversation lifecycle, user-message admission, Agent turns, and the user's decision boundary for Proposals.

## Language

**Conversation**:
A durable transcript container owned by the configured administrator.
_Avoid_: task, session token

**Agent Turn**:
One admitted user message and the Agent processing it to a terminal turn event.
_Avoid_: task execution, Proposal

**Message Reference**:
An ordered canonical Asset Reference plus a display-path snapshot stored with a user message.
_Avoid_: attachment bytes, attached package id

**Proposal**:
A fully validated request for one user-authorized side effect, independently confirmable or rejectable.
_Avoid_: plan document, task, challenge

**Pending Proposal**:
A Proposal awaiting a decision in the active conversation runtime; it is not durable conversation history.
_Avoid_: pending-plan row, queued task

**Proposal Decision**:
The user's single confirm or reject action for one Proposal.
_Avoid_: second confirmation, challenge answer

## Current Boundaries

- Message admission proves conversation ownership before reading Asset facts, then validates each ordered reference through Permission `INSPECT` and the File resolver before acquiring the turn lock.
- A valid submission appends one durable USER message containing the original prompt and typed references. Conversation does not create attachment messages or bind a conversation to one package.
- History is read through Session's typed transcript view. Raw metadata JSON, storage locations, and deleted `attachedPackageId` fields do not cross this boundary.
- Display-path snapshots are historical labels only. `referenceKey` remains the identity and must be revalidated at later side-effect boundaries.
