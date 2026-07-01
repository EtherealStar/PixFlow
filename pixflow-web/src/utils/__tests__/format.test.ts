import { describe, it, expect } from 'vitest'
import { formatBytes, formatMs, progressPercent, formatTime } from '@/utils/format'

describe('formatBytes', () => {
  it('bytes', () => expect(formatBytes(512)).toBe('512 B'))
  it('KB', () => expect(formatBytes(2048)).toBe('2.0 KB'))
  it('MB', () => expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB'))
  it('GB', () => expect(formatBytes(2 * 1024 * 1024 * 1024)).toBe('2.00 GB'))
})

describe('progressPercent', () => {
  it('0', () => expect(progressPercent(0, 100)).toBe(0))
  it('50%', () => expect(progressPercent(50, 100)).toBe(50))
  it('clamp', () => expect(progressPercent(150, 100)).toBe(100))
  it('zero total', () => expect(progressPercent(1, 0)).toBe(0))
})

describe('formatMs', () => {
  it('<1s', () => expect(formatMs(123)).toBe('123ms'))
  it('>=1s', () => expect(formatMs(1500)).toBe('1.5s'))
  it('>=1min', () => expect(formatMs(90_000)).toBe('1.5min'))
})

describe('formatTime', () => {
  it('zero-pads', () => {
    const ts = new Date(2026, 0, 1, 9, 5, 3).getTime()
    expect(formatTime(ts)).toBe('09:05:03')
  })
})