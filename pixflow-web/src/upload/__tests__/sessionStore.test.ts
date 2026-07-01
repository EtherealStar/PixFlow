import { describe, it, expect, beforeEach, vi } from 'vitest'
import { getSession, setSession, deleteSession } from '@/upload/sessionStore'

describe('sessionStore', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('returns null when not set', () => {
    expect(getSession('hash1')).toBeNull()
  })

  it('round-trips a session entry', () => {
    setSession('hash1', { uploadId: 'u1', filename: 'a.zip', size: 100, chunkSize: 1024, savedAt: Date.now() })
    const e = getSession('hash1')
    expect(e?.uploadId).toBe('u1')
    expect(e?.filename).toBe('a.zip')
  })

  it('expires after 7 days', () => {
    vi.useFakeTimers()
    setSession('hash1', { uploadId: 'u1', filename: 'a.zip', size: 100, chunkSize: 1024, savedAt: Date.now() })
    vi.setSystemTime(Date.now() + 8 * 24 * 60 * 60 * 1000)
    expect(getSession('hash1')).toBeNull()
    vi.useRealTimers()
  })

  it('deleteSession removes the entry', () => {
    setSession('hash1', { uploadId: 'u1', filename: 'a.zip', size: 100, chunkSize: 1024, savedAt: Date.now() })
    deleteSession('hash1')
    expect(getSession('hash1')).toBeNull()
  })
})