/**
 * Rubrics 占位接口（Wave 6 范畴，V1 不在范围）。
 * 见 web.md §四「占位内容 Wave 6 / V1 不在范围」。
 */
export interface RubricTemplate {
  templateId: string
  name: string
  version: string
}

export function listTemplates(): Promise<RubricTemplate[]> {
  return Promise.resolve([])
}