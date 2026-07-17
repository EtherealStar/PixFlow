---
status: accepted
---

# Use canonical asset reference keys across product boundaries

PixFlow represents user-selected material across Web, Conversation, Agent, File, Vision, DAG, Task, and Imagegen with one backend-produced `referenceKey`. Package, SKU, and image keys have distinct resolution semantics; tools accept `referenceKey` or `referenceKeys` and do not accept parallel `packageId`, `imageIds`, or `source_image_ids` identities.

This prevents display names and storage paths from becoming accidental identities and lets the backend enforce expansion, deduplication, deletion, and permission rules in one place. The cost is a shared parser/resolver contract and explicit kind checking at every consumer, which we accept to avoid divergent attachment models.

