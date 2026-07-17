# Image Generation

This context owns validation and execution semantics for one-source-image redraw requests.

## Language

**Redraw Proposal**:
A fully validated Proposal that binds one concrete source-image Asset Reference to one redraw instruction.
_Avoid_: batch imagegen plan, source image list

**Redraw Task**:
The asynchronous execution of one confirmed Redraw Proposal.
_Avoid_: multi-image generation batch

**Generated Artifact**:
The single candidate image produced by a Redraw Task before the Asset Library publishes it.
_Avoid_: Generated Image

**Generated Image**:
The Asset Library's durable image created from a successful Generated Artifact with a new image identity.
_Avoid_: source image replacement

