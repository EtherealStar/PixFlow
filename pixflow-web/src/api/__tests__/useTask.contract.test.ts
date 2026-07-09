import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createTask, type TaskProgressFrame } from '@/runtime/useTask'

const mocks = vi.hoisted(() => ({
  subscribe: vi.fn(),
  getTaskStatus: vi.fn(),
  cancelTask: vi.fn()
}))

vi.mock('@/transport/ws', () => ({
  getStompConnection: vi.fn(() => ({ subscribe: mocks.subscribe }))
}))

vi.mock('@/api/tasks', () => ({
  getTaskStatus: mocks.getTaskStatus,
  cancelTask: mocks.cancelTask
}))

describe('task runtime contract', () => {
  beforeEach(() => {
    mocks.subscribe.mockReset()
    mocks.getTaskStatus.mockReset()
    mocks.cancelTask.mockReset()
  })

  it('subscribes to hyphen topic and applies occurredAt/skipped frame fields', () => {
    mocks.subscribe.mockImplementation((destination: string, handler: (frame: TaskProgressFrame) => void) => {
      void destination
      void handler
      return { unsubscribe: vi.fn() }
    })

    const task = createTask({ conversationId: 'c1', taskId: 't1' })
    task.subscribeWS()

    expect(mocks.subscribe.mock.calls[0]?.[0]).toBe('/topic/task-progress-c1-t1')
    const onFrame = mocks.subscribe.mock.calls[0]?.[1] as ((frame: TaskProgressFrame) => void)
    onFrame({
      taskId: 't1',
      done: 1,
      total: 2,
      failed: 0,
      skipped: 1,
      status: 'COMPLETED',
      occurredAt: '2026-07-06T10:00:00Z'
    })

    expect(task.state.value.phase).toBe('completed')
    expect(task.state.value.progress).toEqual({ done: 1, total: 2, failed: 0, skipped: 1 })
    expect(task.state.value.finishedAt).toBe('2026-07-06T10:00:00Z')
  })
})
