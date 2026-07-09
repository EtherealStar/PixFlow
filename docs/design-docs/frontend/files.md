# Files 前端模块设计

## 定位

Files 模块负责 `/files` 页面，提供素材图片和产物图片的统一浏览、搜索、排序、分组、预览、删除和打包下载。它是上传结果、任务产物和会话历史之间的主要视觉汇合点。

## 关键实现

- 页面：`src/pages/FilesPage.vue`
- 组件：`components/files/ImageGrid.vue`、`ImageGridToolbar.vue`、`ImageCard.vue`、`ImagePreviewDialog.vue`、`FileTreePanel.vue`
- API：`api/packages.ts`、`api/tasks.ts`、`api/downloads.ts`、`api/conversations.ts`
- 派生索引：`stores/fileIndex.ts`

## 数据源

素材 tab：

```text
listPackages({ page: 1, size: 100 })
  -> for each package: listPackageImages(packageId, { page: 1, size: 200 })
  -> ExtendedImageItem(sourceType='asset')
```

产物 tab：

```text
listConversations({ page: 1, size: 100 })
  -> for each conversation: listConversationImages(conversationId, { page: 1, size: 200 })
  -> ExtendedImageItem(sourceType='result')
```

素材图片使用 `packageId + imageId` 定位，产物图片使用 `taskId + resultId` 定位。页面不拼对象存储 key，只使用后端返回的预签名 `url`。

> **已知不一致**（a0ca73f3 / a60b9f5d 报告）：
> - `listPackages` / `listPackageImages` 的 `page` 必须从 `1` 开始（`FileController` 用 `Pagination` 校验 `page >= 1`，否则 `INVALID_PARAM` 400）。前端当前传 `0` 会 400。
> - `listConversations` 调用参数名应是 `includeArchived`，**不是** `archived`；参数 `size` 最大 100。
> - `packageId` 后端 JSON 字段是 `id`（`AssetPackage.id`），前端 `PackageDetail` 类型期望 `packageId`，需要在 adapter 层做 `id → packageId` 映射或后端添加 `@JsonProperty("packageId")`。
> - `imageId` 是 `long`，不是 `string`（前端 `AssetImageView.imageId: string` 错）。
> - `AssetImageView` 没有 `groupKey: string` 字段是 nullable，前端可以安全假设可为 null。

## 视图与交互

- tab：`materials` 展示素材，`products` 展示产物。
- viewMode：`folder` 按素材包/会话分组，`flat` 平铺。
- sortType：按时间倒序或按名称排序。
- query：按 filename 本地过滤。
- selectedIds：统一记录 `asset-{imageId}` 或 `result-{resultId}`。
- 预览：打开 `ImagePreviewDialog`。
- 单图下载：创建 `<a>` 指向预签名 URL。
- 批量下载：调用 `POST /api/downloads/bundle`，把选中的素材和产物统一转成 `BundleItem`。
- 批量删除：素材调用 `deletePackageImage`，产物调用 `deleteTaskResult`，成功后刷新当前 tab。删除都返回 `ApiResponse<null>` (200)，不是 204。

## 与后端的边界

删除是后端事实源操作；前端不能只本地移除。重命名 API 已在 adapter 中提供，页面可在后续接入 `RenameDialog` 时复用。

页面当前按包/会话并发拉取图片列表。产物侧必须使用 `listConversationImages` 聚合接口，避免回退到“会话列表 -> 任务列表 -> 结果列表 -> 单结果下载 URL”的 N+1 链路。

`POST /api/downloads/bundle` 请求（`CustomBundleRequest`）：

```json
{
  "items": [
    { "type": "ASSET_IMAGE", "imageId": "123", "filename": "source-front.png" },
    { "type": "TASK_RESULT", "resultId": "456", "filename": "output-front.png" }
  ],
  "archiveName": "selected-images.zip"
}
```

响应（`DownloadHandle`）：`{ url, expiresAt, contentType: 'application/zip', sizeBytes }`。注意 `contentType` 和 `sizeBytes` 是后端真实下发字段，前端 UI 可展示下载文件大小。

## 约束

1. 预签名 URL 只用于 `<img>`、下载或新窗口打开，不写入长期 store。
2. 删除、重命名、下载必须根据 `sourceType` 分派到素材或产物 API。
3. 选择状态使用页面局部状态，不写入全局 store。
4. 文件浏览不应直接访问 MinIO key。
5. 如果后续新增分页加载，必须保持素材和产物的分组语义清晰，避免一次性把不同来源混成无类型列表。
6. 分页参数 `page` 从 `1` 开始；列表接口（素材 / 产物 / 会话 / 任务）皆如此，**只有 task 模块的 `PageQuery` 接受 `page=0`**（默认值），且 task controller 已显式给出 `defaultValue="0"`——不要混用。
