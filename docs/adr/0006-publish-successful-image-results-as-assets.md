---
status: accepted
---

# Publish successful image results as independent assets

A successful image-processing or redraw result is published through the Asset Library as a new Generated Image with a new `imageId` and source lineage. The source image identity is never reused. Task results remain execution facts; Generated Images remain reusable material even when the user clears the successful task or Activity record.

This separates operational retention from product value and makes every output mentionable through the same Asset Reference contract. The tradeoff is an explicit publication step from temporary candidate bytes to a stable asset object and idempotent lineage record, which is required to prevent task cleanup from deleting user-visible outputs.

