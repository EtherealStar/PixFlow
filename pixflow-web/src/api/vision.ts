import { z } from 'zod'
import { request } from './client'

const factsSchema = z.strictObject({
  common: z.strictObject({
    categoryAppearance: z.string().nullable().optional(),
    dominantColors: z.array(z.string()),
    visibleMaterials: z.array(z.string()),
    shapes: z.array(z.string()),
    visibleComponents: z.array(z.string()),
    patterns: z.array(z.string()),
    visibleText: z.array(z.string()),
    background: z.string().nullable().optional(),
    viewTypes: z.array(z.string())
  }),
  attributes: z.array(z.strictObject({ name: z.string(), value: z.string() })),
  limitations: z.array(z.string()),
  conflicts: z.array(z.string())
})

export const visualFactsSchema = z.strictObject({
  packageId: z.number().int().positive(),
  skuId: z.string().min(1),
  analysisStatus: z.enum(['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED']),
  analysisGeneration: z.number().int().nonnegative(),
  facts: factsSchema.nullable(),
  version: z.number().int().nonnegative(),
  writer: z.enum(['AI_GENERATED', 'ADMINISTRATOR_EDITED']).nullable(),
  updatedAt: z.string().min(1),
  failureCode: z.string().nullable().optional()
})

export type VisualFacts = z.infer<typeof factsSchema>
export type VisualFactsView = z.infer<typeof visualFactsSchema>

export async function getVisualFacts(packageId: number, skuId: string, signal?: AbortSignal): Promise<VisualFactsView> {
  return visualFactsSchema.parse(await request<unknown>(
    `/api/vision/packages/${packageId}/skus/${encodeURIComponent(skuId)}/facts`,
    { signal }
  ))
}

export async function replaceVisualFacts(packageId: number, skuId: string, expectedVersion: number, facts: VisualFacts): Promise<VisualFactsView> {
  return visualFactsSchema.parse(await request<unknown>(`/api/vision/packages/${packageId}/skus/${encodeURIComponent(skuId)}/facts`, {
    method: 'PUT', body: { expectedVersion, facts }, noRetry: true
  }))
}

export async function reanalyzeVisualFacts(packageId: number, skuId: string, expectedGeneration: number, requestId: string): Promise<VisualFactsView> {
  return visualFactsSchema.parse(await request<unknown>(`/api/vision/packages/${packageId}/skus/${encodeURIComponent(skuId)}/reanalyze`, {
    method: 'POST', body: { expectedGeneration, requestId }, noRetry: true
  }))
}
