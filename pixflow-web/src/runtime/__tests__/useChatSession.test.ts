import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useChatSession, type ChatRouteLike } from '@/runtime/useChatSession'
import * as conversationsApi from '@/api/conversations'

vi.mock('@/api/conversations', () => ({
  createConversation: vi.fn(),
  getConversation: vi.fn(),
  listConversations: vi.fn(),
  archiveConversation: vi.fn()
}))

vi.mock('@/api/attachments', () => ({
  uploadAttachment: vi.fn()
}))

describe('useChatSession', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(conversationsApi.getConversation).mockReset()
    vi.mocked(conversationsApi.getConversation).mockResolvedValue({
      conversationId: 'c1',
      title: '会话',
      createdAt: '2026-07-08T00:00:00Z',
      updatedAt: '2026-07-08T00:00:00Z'
    })
  })

  it('keeps the agent turn handle raw and exposes flat readable state', async () => {
    const route: ChatRouteLike = {
      params: { cid: 'c1' },
      query: {}
    }
    const router = {
      replace: vi.fn()
    }

    const session = useChatSession({ route, router: router as never })
    await flushPromises()
    await nextTick()

    expect(session.activeConversationId.value).toBe('c1')
    expect(session.turnError.value).toBeNull()
    expect(session.currentPhase.value).toBe('idle')
    expect(session.streamTimeline.value).toEqual([])
    expect(session.visibleProposals.value).toEqual([])
  })
})

async function flushPromises(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}
