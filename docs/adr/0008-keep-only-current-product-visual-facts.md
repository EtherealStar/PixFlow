---
status: accepted
---

# Keep only current product visual facts

Product-image understanding remains durable and available through one lookup tool, but each SKU or target image keeps only one mutable current Product Visual Facts document and one current recoverable work item. Administrator edits and successful reanalysis replace the current facts in place; PixFlow deliberately keeps no fact revisions or completed-run history because the product treats replacement as correction and unbounded historical rows would retain content the administrator intended to remove. Only a change to the relevant image-content hashes clears SKU facts and triggers automatic analysis; model, prompt, preprocessing, and schema-version changes require an explicit reanalysis or data migration.
