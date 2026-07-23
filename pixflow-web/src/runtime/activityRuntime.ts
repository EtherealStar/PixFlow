import type { Pinia } from 'pinia'
import {
  activityFrameSchema,
  cancelActivity,
  clearActivity,
  listActivities,
  retryFailedActivity,
  type ActivityFrame
} from '@/api/activities'
import { useActivitiesStore } from '@/stores/activities'
import { disposeStompConnection, getStompConnection, type StompConnection } from '@/transport/ws'

const ACTIVITY_DESTINATION = '/user/queue/activity'

export class ActivityRuntime {
  private connection: StompConnection | null = null
  private unsubscribe: (() => void) | null = null
  private generation = 0
  private snapshotAbort: AbortController | null = null
  private reconciling = false
  private reconciliation = 0
  private reconcileTimer: number | null = null
  private pendingFrames: ActivityFrame[] = []

  constructor(private readonly pinia: Pinia) {
    useActivitiesStore(pinia).setCommandExecutor((activityId, command) => this.runCommand(activityId, command))
  }

  async start(): Promise<void> {
    useActivitiesStore(this.pinia).setCommandExecutor((activityId, command) => this.runCommand(activityId, command))
    const generation = ++this.generation
    await this.reconcile(generation)
    if (generation !== this.generation) return

    const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
    this.connection = getStompConnection({
      url: `${protocol}://${location.host}/ws/activity`,
      onConnect: () => { void this.reconcile(this.generation) }
    })
    this.unsubscribe?.()
    this.unsubscribe = this.connection.subscribe(ACTIVITY_DESTINATION, (raw: unknown) => {
      const parsed = activityFrameSchema.safeParse(raw)
      if (!parsed.success) return
      if (this.reconciling) this.pendingFrames.push(parsed.data)
      else useActivitiesStore(this.pinia).applyFrame(parsed.data)
    }).unsubscribe
  }

  stop(): void {
    this.generation++
    this.snapshotAbort?.abort()
    this.snapshotAbort = null
    this.unsubscribe?.()
    this.unsubscribe = null
    this.connection = null
    this.reconciling = false
    this.reconciliation++
    if (this.reconcileTimer !== null) window.clearTimeout(this.reconcileTimer)
    this.reconcileTimer = null
    this.pendingFrames = []
    disposeStompConnection()
    useActivitiesStore(this.pinia).clear()
    useActivitiesStore(this.pinia).setCommandExecutor(null)
  }

  private async runCommand(activityId: string, command: 'cancel' | 'retry' | 'clear'): Promise<void> {
    if (command === 'cancel') await cancelActivity(activityId)
    if (command === 'retry') await retryFailedActivity(activityId)
    if (command === 'clear') await clearActivity(activityId)
  }

  private async reconcile(generation: number, preservePendingFrames = false): Promise<void> {
    const store = useActivitiesStore(this.pinia)
    const reconciliation = ++this.reconciliation
    this.snapshotAbort?.abort()
    const controller = new AbortController()
    this.snapshotAbort = controller
    this.reconciling = true
    if (!preservePendingFrames) this.pendingFrames = []
    store.loading = true
    let succeeded = false
    try {
      const snapshot = await listActivities({ page: 1, size: 50 }, controller.signal)
      if (generation === this.generation) {
        store.replaceSnapshot(snapshot)
        store.error = null
        succeeded = true
      }
    } catch (error: unknown) {
      if (generation === this.generation && !controller.signal.aborted) {
        store.error = error
        this.reconcileTimer = window.setTimeout(() => {
          this.reconcileTimer = null
          void this.reconcile(generation, true)
        }, 2_000)
      }
    } finally {
      if (succeeded && generation === this.generation && reconciliation === this.reconciliation) {
        // STOMP 在 CONNECT 后先恢复订阅；快照期间到达的新帧必须在快照之后重放。
        for (const frame of this.pendingFrames.sort((left, right) => left.sequence - right.sequence)) {
          store.applyFrame(frame)
        }
        this.pendingFrames = []
        this.reconciling = false
      }
      if (generation === this.generation && reconciliation === this.reconciliation) store.loading = false
      if (this.snapshotAbort === controller) this.snapshotAbort = null
    }
  }
}
