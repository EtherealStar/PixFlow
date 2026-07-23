import { z } from 'zod'
import { request } from './client'

export const assetReferenceCandidateSchema = z.strictObject({
  referenceKey: z.string().min(1),
  kind: z.enum(['PACKAGE', 'SKU', 'IMAGE']),
  sourceType: z.enum(['ORIGINAL', 'GENERATED']).nullable(),
  displayPath: z.string().min(1),
  hasChildren: z.boolean(),
  sourceGroup: z.enum(['MATERIALS', 'OUTPUTS'])
})

const assetReferencePageSchema = z.strictObject({
  records: z.array(assetReferenceCandidateSchema),
  total: z.number().int().nonnegative(),
  page: z.number().int().positive(),
  size: z.number().int().positive()
})

export type AssetReferenceCandidate = z.infer<typeof assetReferenceCandidateSchema>

export async function searchAssetReferences(
  query: string,
  excludedReferenceKeys: string[],
  signal?: AbortSignal
): Promise<AssetReferenceCandidate[]> {
  const params = new URLSearchParams({ page: '1', size: '20' })
  const normalized = query.trim()
  if (normalized) params.set('query', normalized)
  for (const key of excludedReferenceKeys) params.append('excludeReferenceKey', key)
  const page = assetReferencePageSchema.parse(
    await request<unknown>(`/api/asset-references?${params.toString()}`, { signal })
  )
  return page.records
}
