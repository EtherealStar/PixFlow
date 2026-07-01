import { describe, it, expect, beforeEach, vi } from 'vitest'
import { getIdempotencyKey, setIdempotencyKey, clearIdempotencyKey } from '@/utils/idempotencyStore'

describe('idempotencyStore', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('returns null when not set', () => {
    expect(getIdempotencyKey('p1')).toBeNull()
  })

  it('returns the key after set', () => {
    setIdempotencyKey('p1', 'uuid-1')
    expect(getIdempotencyKey('p1')).toBe('uuid-1')
  })

  it('reuses same key for same proposalId (multi-tab semantics)', () => {
    setIdempotencyKey('p1', 'uuid-1')
    const k1 = getIdempotencyKey('p1')
    const k2 = getIdempotencyKey('p1')
    expect(k1).toBe('uuid-1')
    expect(k2).toBe('uuid-1')
  })

  it('expires after TTL', () => {
    vi.useFakeTimers()
    setIdempotencyKey('p1', 'uuid-1')
    vi.setSystemTime(Date.now() + 31 * 60 * 1000)
    expect(getIdempotencyKey('p1')).toBeNull()
    vi.useRealTimers()
  })

  it('clear removes the key', () => {
    setIdempotencyKey('p1', 'uuid-1')
    clearIdempotencyKey('p1')
    expect(getIdempotencyKey('p1')).toBeNull()
  })

  it('keys are scoped by proposalId', () => {
    setIdempotencyKey('p1', 'uuid-1')
    setIdempotencyKey('p2', 'uuid-2')
    expect(getIdempotencyKey('p1')).toBe('uuid-1')
    expect(getIdempotencyKey('p2')).toBe('uuid-2')
  })
})