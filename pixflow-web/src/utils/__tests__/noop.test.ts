import { describe, it, expect } from 'vitest'
import { newId, newUuid, newTraceId } from '@/utils/id'

describe('id utils', () => {
  it('newId returns 12 chars', () => {
    const id = newId()
    expect(id).toMatch(/^[0-9a-z]{12}$/)
  })
  it('newUuid returns v4', () => {
    const u = newUuid()
    expect(u).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
  it('newTraceId returns 16 hex chars', () => {
    expect(newTraceId()).toMatch(/^[0-9a-f]{16}$/)
  })
})