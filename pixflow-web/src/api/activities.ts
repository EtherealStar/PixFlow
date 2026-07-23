import { z } from 'zod'
import { request } from './client'

export const activityKindSchema = z.enum(['UPLOAD', 'PROCESS', 'IMAGEGEN'])
export const activityStatusSchema = z.enum([
  'UPLOADING',
  'EXTRACTING',
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'PARTIALLY_SUCCEEDED',
  'FAILED'
])

const activityProgressSchema = z.strictObject({
  completed: z.number().int().nonnegative(),
  total: z.number().int().nonnegative(),
  failed: z.number().int().nonnegative()
})

const activityActionsSchema = z.strictObject({
  cancel: z.boolean(),
  retryFailed: z.boolean(),
  clear: z.boolean()
})

export const activityViewSchema = z.strictObject({
  activityId: z.string().min(1),
  kind: activityKindSchema,
  status: activityStatusSchema,
  progress: activityProgressSchema,
  conversationId: z.string().nullable().optional(),
  packageId: z.string().nullable().optional(),
  taskId: z.string().nullable().optional(),
  createdAt: z.string().min(1),
  startedAt: z.string().nullable().optional(),
  finishedAt: z.string().nullable().optional(),
  allowedActions: activityActionsSchema,
  sequence: z.number().int().nonnegative()
})

export const activitySnapshotSchema = z.strictObject({
  records: z.array(activityViewSchema),
  total: z.number().int().nonnegative(),
  page: z.number().int().positive(),
  size: z.number().int().positive(),
  cursor: z.number().int().nonnegative()
})

export const activityFrameSchema = z.strictObject({
  sequence: z.number().int().nonnegative(),
  operation: z.enum(['UPSERT', 'REMOVE']),
  activityId: z.string().min(1),
  view: activityViewSchema.nullable()
}).superRefine((frame, context) => {
  if (frame.operation === 'UPSERT' && frame.view === null) {
    context.addIssue({ code: 'custom', message: 'UPSERT frame requires view', path: ['view'] })
  }
  if (frame.operation === 'REMOVE' && frame.view !== null) {
    context.addIssue({ code: 'custom', message: 'REMOVE frame requires a null view', path: ['view'] })
  }
})

const retryResultSchema = z.strictObject({
  sourceActivityId: z.string().min(1),
  activityId: z.string().min(1),
  taskId: z.string().min(1),
  retryOfTaskId: z.string().min(1)
})

export type ActivityKind = z.infer<typeof activityKindSchema>
export type ActivityStatus = z.infer<typeof activityStatusSchema>
export type ActivityView = z.infer<typeof activityViewSchema>
export type ActivitySnapshot = z.infer<typeof activitySnapshotSchema>
export type ActivityFrame = z.infer<typeof activityFrameSchema>
export type ActivityRetryResult = z.infer<typeof retryResultSchema>

export interface ActivityQuery {
  page?: number
  size?: number
  status?: ActivityStatus
  kind?: ActivityKind
}

export async function listActivities(query: ActivityQuery = {}, signal?: AbortSignal): Promise<ActivitySnapshot> {
  const params = new URLSearchParams()
  if (query.page !== undefined) params.set('page', String(query.page))
  if (query.size !== undefined) params.set('size', String(query.size))
  if (query.status !== undefined) params.set('status', query.status)
  if (query.kind !== undefined) params.set('kind', query.kind)
  const suffix = params.size > 0 ? `?${params.toString()}` : ''
  return activitySnapshotSchema.parse(await request<unknown>(`/api/activities${suffix}`, { signal }))
}

export async function cancelActivity(activityId: string): Promise<void> {
  await request<unknown>(activityPath(activityId, 'cancel'), { method: 'POST', noRetry: true })
}

export async function retryFailedActivity(activityId: string): Promise<ActivityRetryResult> {
  return retryResultSchema.parse(await request<unknown>(activityPath(activityId, 'retry-failed'), {
    method: 'POST',
    noRetry: true
  }))
}

export async function clearActivity(activityId: string): Promise<void> {
  await request<unknown>(activityPath(activityId), { method: 'DELETE', noRetry: true })
}

function activityPath(activityId: string, action?: string): string {
  const base = `/api/activities/${encodeURIComponent(activityId)}`
  return action ? `${base}/${action}` : base
}
