import { z } from 'zod'
import { request } from './client'

const pageFields = {
  total: z.number().int().nonnegative(),
  page: z.number().int().positive(),
  size: z.number().int().positive()
}

export const materialPackageSchema = z.strictObject({
  packageId: z.number().int().positive(),
  displayName: z.string(),
  status: z.enum(['READY', 'PARTIAL']),
  originalImageCount: z.number().int().nonnegative(),
  skuCount: z.number().int().nonnegative(),
  createdAt: z.string().min(1),
  updatedAt: z.string().min(1)
})

export const originalImageSchema = z.strictObject({
  imageId: z.string().min(1),
  packageId: z.number().int().positive(),
  skuId: z.string().nullable().optional(),
  referenceKey: z.string().min(1),
  sourceType: z.literal('ORIGINAL'),
  displayName: z.string(),
  width: z.number().int().positive().nullable().optional(),
  height: z.number().int().positive().nullable().optional(),
  sizeBytes: z.number().int().nonnegative().nullable().optional(),
  contentType: z.string().nullable().optional(),
  previewUrl: z.string().nullable().optional(),
  previewExpiresAt: z.string().nullable().optional(),
  createdAt: z.string().min(1)
})

const materialPackagePageSchema = z.strictObject({
  records: z.array(materialPackageSchema),
  ...pageFields
})

const originalImagePageSchema = z.strictObject({
  records: z.array(originalImageSchema),
  ...pageFields
})

const originalImageDetailSchema = z.strictObject({
  image: originalImageSchema,
  previousImageId: z.string().nullable(),
  nextImageId: z.string().nullable()
})

export type MaterialPackage = z.infer<typeof materialPackageSchema>
export type OriginalImage = z.infer<typeof originalImageSchema>
export type OriginalImageDetail = z.infer<typeof originalImageDetailSchema>

export async function listMaterialPackages(query: {
  page?: number
  size?: number
  query?: string
  sort?: 'UPDATED_DESC' | 'UPDATED_ASC' | 'NAME_ASC' | 'NAME_DESC'
} = {}): Promise<z.infer<typeof materialPackagePageSchema>> {
  const suffix = queryString(query)
  return materialPackagePageSchema.parse(await request<unknown>(`/api/files/packages${suffix}`))
}

export async function listOriginalImages(query: {
  page?: number
  size?: number
  packageId?: number
  skuId?: string
  query?: string
  sort?: 'CREATED_DESC' | 'CREATED_ASC' | 'NAME_ASC' | 'NAME_DESC'
} = {}): Promise<z.infer<typeof originalImagePageSchema>> {
  const suffix = queryString(query)
  return originalImagePageSchema.parse(await request<unknown>(`/api/files/images${suffix}`))
}

export async function getOriginalImage(packageId: number, imageId: string, signal?: AbortSignal): Promise<OriginalImageDetail> {
  const raw = await request<unknown>(
    `/api/files/packages/${packageId}/images/${encodeURIComponent(imageId)}`,
    { signal }
  )
  return originalImageDetailSchema.parse(raw)
}

function queryString(query: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  return params.size > 0 ? `?${params.toString()}` : ''
}
