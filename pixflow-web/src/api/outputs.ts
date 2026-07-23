import { z } from 'zod'
import { request } from './client'

const pageFields = {
  total: z.number().int().nonnegative(),
  page: z.number().int().positive(),
  size: z.number().int().positive()
}

export const outputConversationSchema = z.strictObject({
  conversationId: z.string().min(1),
  title: z.string(),
  generatedImageCount: z.number().int().nonnegative(),
  latestGeneratedAt: z.string().min(1)
})

export const outputTaskSchema = z.strictObject({
  taskId: z.string().min(1),
  taskType: z.enum(['IMAGE_PROCESS', 'IMAGEGEN']),
  generatedImageCount: z.number().int().nonnegative(),
  createdAt: z.string().min(1),
  finishedAt: z.string().nullable().optional()
})

export const generatedImageSchema = z.strictObject({
  imageId: z.string().min(1),
  referenceKey: z.string().min(1),
  sourceType: z.literal('GENERATED'),
  displayName: z.string(),
  packageId: z.number().int().positive(),
  skuId: z.string().nullable().optional(),
  conversationId: z.string().min(1),
  taskId: z.string().min(1),
  sourceImageId: z.string().nullable().optional(),
  width: z.number().int().positive().nullable().optional(),
  height: z.number().int().positive().nullable().optional(),
  sizeBytes: z.number().int().nonnegative().nullable().optional(),
  contentType: z.string().nullable().optional(),
  previewUrl: z.string().nullable().optional(),
  previewExpiresAt: z.string().nullable().optional(),
  createdAt: z.string().min(1)
})

const conversationPageSchema = z.strictObject({ records: z.array(outputConversationSchema), ...pageFields })
const taskPageSchema = z.strictObject({ records: z.array(outputTaskSchema), ...pageFields })
const imagePageSchema = z.strictObject({ records: z.array(generatedImageSchema), ...pageFields })

export type OutputConversation = z.infer<typeof outputConversationSchema>
export type OutputTask = z.infer<typeof outputTaskSchema>
export type GeneratedImage = z.infer<typeof generatedImageSchema>

export async function listOutputConversations(query: {
  page?: number
  size?: number
  query?: string
  sort?: 'LATEST_OUTPUT_DESC' | 'LATEST_OUTPUT_ASC' | 'NAME_ASC' | 'NAME_DESC'
} = {}) {
  return conversationPageSchema.parse(await request<unknown>(`/api/outputs/conversations${queryString(query)}`))
}

export async function listOutputTasks(conversationId: string, page = 1, size = 20) {
  const path = `/api/outputs/conversations/${encodeURIComponent(conversationId)}/tasks?page=${page}&size=${size}`
  return taskPageSchema.parse(await request<unknown>(path))
}

export async function listGeneratedImages(taskId: string, page = 1, size = 50) {
  const path = `/api/outputs/tasks/${encodeURIComponent(taskId)}/images?page=${page}&size=${size}`
  return imagePageSchema.parse(await request<unknown>(path))
}

export async function deleteGeneratedImage(imageId: string): Promise<void> {
  await request<unknown>(`/api/outputs/images/${encodeURIComponent(imageId)}`, { method: 'DELETE', noRetry: true })
}

export async function renameGeneratedImage(imageId: string, displayName: string): Promise<GeneratedImage> {
  return generatedImageSchema.parse(await request<unknown>(`/api/outputs/images/${encodeURIComponent(imageId)}`, {
    method: 'PATCH', body: { displayName }, noRetry: true
  }))
}

function queryString(query: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.size > 0 ? `?${params.toString()}` : ''
}
