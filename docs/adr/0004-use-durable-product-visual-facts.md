# Use durable product visual facts instead of general visual question answering

Product-image understanding is recorded as versioned SKU and image visual snapshots, and the main Agent reads those facts through a single lookup tool. We deliberately do not expose general image-question answering or let the vision model generate copy and redraw prompts: those paths bypass persistence, make cost and recovery unbounded, and blur observed evidence with the main Agent's business reasoning.
