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
