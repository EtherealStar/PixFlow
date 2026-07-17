import { describe, it, expect } from 'vitest'
import { errorToMessage, categorize, isRetryable, redactToken } from '@/utils/error'
import { ApiError, type ApiErrorInit } from '@/types/api'

const make = (over: Partial<ApiErrorInit> = {}): ApiError => new ApiError({
  status: 500,
  errorCode: 'INTERNAL_ERROR',
  message: 'x',
  traceId: 't1',
  ...over
})

describe('errorToMessage', () => {
  const cases: Array<[string, ApiError, 'info' | 'warning' | 'error']> = [
    ['PROPOSAL_ALREADY_CONFIRMED', make({ status: 409, errorCode: 'PROPOSAL_ALREADY_CONFIRMED' }), 'warning'],
    ['PROPOSAL_NOT_FOUND', make({ status: 404, errorCode: 'PROPOSAL_NOT_FOUND' }), 'warning'],
    ['PROPOSAL_PAYLOAD_MISMATCH', make({ status: 409, errorCode: 'PROPOSAL_PAYLOAD_MISMATCH' }), 'warning'],
    ['CONVERSATION_ARCHIVED', make({ status: 410, errorCode: 'CONVERSATION_ARCHIVED' }), 'warning'],
    ['CONVERSATION_NOT_FOUND', make({ status: 404, errorCode: 'CONVERSATION_NOT_FOUND' }), 'warning'],
    ['CHUNK_HASH_MISMATCH', make({ status: 400, errorCode: 'CHUNK_HASH_MISMATCH' }), 'error'],
    ['CHUNK_SIZE_MISMATCH', make({ status: 400, errorCode: 'CHUNK_SIZE_MISMATCH' }), 'error'],
    ['INCOMPLETE_CHUNKS', make({ status: 400, errorCode: 'INCOMPLETE_CHUNKS' }), 'error'],
    ['UPLOAD_TOO_LARGE', make({ status: 400, errorCode: 'UPLOAD_TOO_LARGE' }), 'error'],
    ['PACKAGE_DEDUP_CONFLICT', make({ status: 409, errorCode: 'PACKAGE_DEDUP_CONFLICT' }), 'error'],
    ['NETWORK_ERROR', make({ status: 0, errorCode: 'NETWORK_ERROR' }), 'warning'],
    ['STREAM_INTERRUPTED', make({ status: 0, errorCode: 'STREAM_INTERRUPTED' }), 'warning'],
    ['INTERNAL_ERROR', make({ status: 500, errorCode: 'INTERNAL_ERROR' }), 'error'],
    ['UNKNOWN', make({ status: 500, errorCode: 'SOMETHING' }), 'error']
  ]
  for (const [name, err, type] of cases) {
    it(name, () => {
      expect(errorToMessage(err).type).toBe(type)
    })
  }
})

describe('categorize', () => {
  it('network', () => expect(categorize(make({ status: 0 }))).toBe('network'))
  it('biz-410', () => expect(categorize(make({ status: 410 }))).toBe('biz-410'))
  it('generic 429', () => expect(categorize(make({ status: 429, errorCode: 'RATE_LIMITED' }))).toBe('biz-4xx'))
  it('5xx', () => expect(categorize(make({ status: 503 }))).toBe('5xx'))
  it('biz-4xx', () => expect(categorize(make({ status: 400, errorCode: 'OTHER' }))).toBe('biz-4xx'))
  it('upload-400', () => expect(categorize(make({ status: 400, errorCode: 'CHUNK_HASH_MISMATCH' }))).toBe('upload-400'))
  it('ws', () => expect(categorize(make({ status: 0, errorCode: 'WS_RECONNECTING' }))).toBe('ws'))
})

describe('isRetryable', () => {
  it('network 0', () => expect(isRetryable(make({ status: 0 }))).toBe(true))
  it('429', () => expect(isRetryable(make({ status: 429 }))).toBe(true))
  it('5xx', () => expect(isRetryable(make({ status: 502 }))).toBe(true))
  it('4xx not retryable', () => expect(isRetryable(make({ status: 400 }))).toBe(false))
})

describe('redactToken', () => {
  it('short', () => expect(redactToken('ab')).toBe('***'))
  it('long', () => expect(redactToken('abcdefghijkl')).toBe('ab***kl'))
  it('empty', () => expect(redactToken('')).toBe(''))
})
