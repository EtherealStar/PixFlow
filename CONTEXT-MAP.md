# Context Map

PixFlow is a multi-context repository. Context documents are added lazily when a context's language has been resolved; this map lists the contexts that currently have an explicit glossary.

## Contexts

- [Image Processing](./pixflow-infra-image/CONTEXT.md) - provides deterministic local raster operations and encoding pipelines.
- [DAG Execution](./pixflow-module-dag/CONTEXT.md) - validates image plans and compiles them into deterministic executable branches.
- [Task Execution](./pixflow-module-task/CONTEXT.md) - schedules work units, records their outcomes, and owns task terminal states.
- [Execution State](./pixflow-state/CONTEXT.md) - projects durable work-unit outcomes into recovery and progress read models.
- [Rubrics Evaluation](./pixflow-module-rubrics/CONTEXT.md) - evaluates typed artifacts and decisions against evidence-grounded criteria.
- [Product Vision Understanding](./pixflow-module-vision/CONTEXT.md) - owns the current durable, observation-only facts derived from product images.
- [Asset Library](./pixflow-module-file/CONTEXT.md) - owns packages, original/generated image identities, references, and deletion.
- [Conversation](./pixflow-conversation/CONTEXT.md) - owns turns, message references, ephemeral Proposals, and user decisions.
- [Image Generation](./pixflow-module-imagegen/CONTEXT.md) - validates and executes one-image redraw requests.
- [Administrator Authentication](./pixflow-infra-auth/CONTEXT.md) - authenticates the one configured administrator while allowing historical accounts to remain stored.
- [Web Workspace](./pixflow-web/CONTEXT.md) - owns Materials, Outputs, mention tokens, queued messages, and global Activity projections.

## Relationships

- **DAG Execution -> Image Processing**: DAG Execution resolves typed local steps and invokes Image Processing; Image Processing does not know about nodes, branches, tasks, or storage.
- **Task Execution -> DAG Execution**: Task Execution schedules compiled branches as work units and records their outcomes.
- **Task Execution <-> Execution State**: Task Execution owns state changes; Execution State reads successful checkpoints and returns the skippable work-unit set.
- **Execution State -> DAG Execution**: recovery identity uses the deterministic branch identity defined by DAG Execution.
- **Rubrics Evaluation -> Task Execution**: Rubrics Evaluation reads immutable task outcomes as evaluation subjects; it does not change task state.
- **Product Vision Understanding -> Image Processing**: Product Vision Understanding uses Image Processing to normalize model inputs; Image Processing does not know about SKUs or visual facts.
- **Agent -> Product Vision Understanding**: the main Agent reads current product visual facts and remains the sole owner of marketing copy and redraw-prompt reasoning.
- **Conversation -> Asset Library**: Message References use canonical Asset References; Conversation never owns file bytes or expands package contents in the browser.
- **Agent -> Asset Library**: the Agent passes canonical keys to inspection and Proposal tools without constructing or parsing them.
- **Conversation -> DAG Execution**: Conversation publishes a deterministic Proposal only after DAG validation, compilation, and material preflight have succeeded.
- **Conversation -> Image Generation**: each Redraw Proposal is independently confirmed and creates one Redraw Task.
- **Task Execution -> Asset Library**: a successful image result is published as a new Generated Image; task-record cleanup does not delete that asset.
- **Image Generation -> Asset Library**: a Generated Artifact becomes a Generated Image only after successful asset publication.
- **Web Workspace -> Conversation**: Proposal cards and Queued Messages live only in the current browser application; messages remain durable through Conversation history.
- **Web Workspace -> Asset Library**: Materials shows originals, Outputs shows generated images, and both supply canonical Mention Tokens.
- **Web Workspace -> Product Vision Understanding**: a Materials image detail resolves its SKU scope and reads or replaces that SKU's current Product Visual Facts; the Web does not own analysis history.
- **Web Workspace -> Task Execution**: Activity projects task state but does not own task outcomes or Generated Images.
- **Administrator Authentication -> all authenticated contexts**: every request must remain eligible as the Configured Administrator; historical accounts have no application authority.
