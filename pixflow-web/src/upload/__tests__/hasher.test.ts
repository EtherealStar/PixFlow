import { describe, it, expect } from 'vitest'
import { sha256Blob } from '@/upload/hasher'

describe('sha256Blob', () => {
  it('computes a 64-char hex digest', async () => {
    const blob = new Blob(['hello pixflow'], { type: 'text/plain' })
    const hex = await sha256Blob(blob)
    expect(hex).toMatch(/^[0-9a-f]{64}$/)
  })

  it('is deterministic', async () => {
    const a = await sha256Blob(new Blob(['abc']))
    const b = await sha256Blob(new Blob(['abc']))
    expect(a).toBe(b)
  })

  it('differs for different inputs', async () => {
    const a = await sha256Blob(new Blob(['abc']))
    const b = await sha256Blob(new Blob(['abd']))
    expect(a).not.toBe(b)
  })
})