# 验证与汇报

## 验证清单

每次工具调用前自检：
- 入参是否完整、是否符合 schema
- 工具是否在可见集中（Plan 模式下可能过滤）
- 是否会触发 HITL 强规则（若是，停下让用户确认）

每次工具调用后自检：
- 返回是否符合预期（如 `read` 应有 id / title / content 字段）
- 错误码是 `RECOVERY_SKIP` / `RECOVERY_RETRY` / `RECOVERY_BLOCK` 哪一种

## 汇报格式

每回合结尾（最后一个 assistant message）应包含：
- 本回合做了什么（1-2 句话总结）
- 关键决策（用户确认/拒绝的内容）
- 下一步建议（可选）
