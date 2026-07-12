---
status: accepted
---

# Use Redisson task locks with execution-epoch fencing

PixFlow uses a Redisson `RLock` with watchdog renewal as the task-level mutual-exclusion mechanism. A monotonically increasing MySQL execution epoch is not a second lock; it fences result and terminal writes so an obsolete worker cannot commit after losing the Redis lock or after another worker takes over. Database heartbeat timestamps drive stale-task discovery, while Redis unavailability fails closed rather than bypassing task mutual exclusion.
