# Asset Library

This context owns uploaded package namespaces, processable image identities, canonical references, and asset deletion.

## Language

**Asset Package**:
A named uploaded-archive namespace that groups original images and their SKU structure.
_Avoid_: attachment, folder upload

**Original Image**:
A processable image admitted from an Asset Package.
_Avoid_: uploaded image, loose image

**Generated Image**:
A processable image published from a successful task result with a new identity and source lineage.
_Avoid_: task result row, overwritten source image

**Asset Reference**:
A canonical, backend-produced key that identifies one Asset Package, SKU scope, Original Image, or Generated Image across module boundaries.
_Avoid_: file path, object key, display name

**Reference Expansion**:
Resolution of a package or SKU Asset Reference into its processable Original Images for an operation.
_Avoid_: frontend enumeration

**Reference Tombstone**:
The minimum identity and original display-name facts retained only so historical message references remain understandable after asset deletion.
_Avoid_: soft-deleted asset, retained file
