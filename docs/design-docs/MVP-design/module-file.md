# module-file —— 素材包管理

> 源码：`src/main/java/com/etherealstar/pixflow/module/file/`

素材包（`asset_package`）是 PixFlow 业务的输入端：

- 上传 zip（必填）+ 文案文档（可选）。
- 上传时执行 zip-bomb 防护 + 图片识别过滤 + SKU 提取 + 文案文档解析。
- 提供列表 / 详情 / 删除 / 结果预览 / 打包下载。

## 1. 目录结构

```
module/file/
├── config/                  AssetProperties 阈值
├── controller/              AssetPackageController / ResultPreviewController
├── copy/                    CopyDocumentParser + CopyRow + CopyParseResult
├── domain/                  PackageStatus / PackageScanResult / SkippedFile
├── dto/                     PackageUploadResponse / PackageListItem / ...
├── entity/                  AssetPackage / AssetImage / AssetCopy
├── extract/                 ZipExtractor + ZipEntryConsumer
├── image/                   ImageValidator + ImageDecoder + ImageIoImageDecoder
├── mapper/                  MyBatis Plus BaseMapper
├── service/                 AssetPackageService / PackageDeleter / ResultPreviewService
└── SkuExtractor.java        SKU 提取（包内根级工具类）
```

## 2. 配置 `config/AssetProperties`

`AssetProperties` 绑定 `pixflow.asset.*`：

| 字段 | 默认 | 含义 |
|---|---|---|
| `zipMaxSize` | 500 MB | 上传 zip 体积上限 |
| `extractedMaxSize` | 2 GB | 解压后累计文件总大小上限（zip-bomb 防护） |
| `extractedMaxCount` | 2000 | 解压后文件总数上限（zip-bomb 防护） |
| `docMaxSize` | 50 MB | 文案文档体积上限 |
| `docMaxRows` | 10000 | 文案文档数据行数上限（不含表头） |

对应 `application.yml` 中的 `pixflow.asset.*` 段。

## 3. 实体与软关联

| 实体 | 表 | 关键字段 |
|---|---|---|
| `AssetPackage` | `asset_package` | id, name, zip_path, doc_path, size, image_count, status(0/1/2), created_at |
| `AssetImage` | `asset_image` | id, package_id, sku_id, group_key, view_id, original_path, created_at |
| `AssetCopy` | `asset_copy` | id, package_id, sku_id, product_name, keywords, description, created_at |

软关联键：

- `asset_image.sku_id` ↔ `asset_copy.sku_id`（同包内）。
- 三个表通过 `package_id` 与 `asset_package` 关联。

> 全库无数据库外键。`SkuExtractor` 提取失败或文件无字母数字时回退到「完整去扩展名文件名」作为兜底 SKU。

## 4. 上传链路（核心）

入口：`POST /api/asset/package/upload`（`AssetPackageController.upload`）。

multipart 字段：

- `zip_file`（必填，zip 素材包）
- `doc_file`（可选，文案文档 xls/xlsx/csv）

`AssetPackageService.upload` 主流程：

```
validateZipPresence
        │
        ▼
validateZipSize          ── 越界 → ASSET_ZIP_TOO_LARGE
        │
        ▼
docFile 解析             ── 失败 → DOC_FORMAT_INVALID / DOC_ROWS_EXCEEDED / DOC_MISSING_ID_COLUMN
（CopyDocumentParser）       失败时一律不创建任何记录
        │
        ▼
insert asset_package      ── 状态 PARSING，获取 packageId
        │
        ▼
落盘 zip + 文案文档
        │
        ▼
validateZipSignature      ── 4 字节 zip 头签名（普通 / 空 / 跨卷三选一）
        │
        ▼
scanPackage(packageId)    ── ZipExtractor 流式解压 + ImageValidator 过滤 + SkuExtractor + asset_image 入库
        │
        ▼
persistCopies             ── asset_copy 入库（无 doc 时跳过；空 id 行记录行号）
        │
        ▼
PackageScanResult.of      ── imageCount == 0 → status=PARSE_FAILED，否则 READY
        │
        ▼
updateById
        │
        ▼
PackageUploadResponse.from(packageId, name, scan)
```

任一步抛 `BusinessException` 都进入 `cleanup(packageId)`：删除 `packages/{packageId}` 目录、删除三张表相关记录（每步独立 `try/catch`，避免「清理失败掩盖原异常」）。

### 4.1 ZipExtractor 流式解压

`extract/ZipExtractor.java`：

- 用 `ZipInputStream` + `BufferedInputStream`，按块读取。
- 累计「文件总数 + 累计解压大小」双阈值，越界立即终止并抛 `ASSET_ZIP_BOMB`（details 含阈值上下文）。
- zip 结构无法解析（`ZipException`）或读取失败 → `ASSET_ZIP_INVALID`。
- 防御路径穿越：任何条目名含 `..` 段 → `ASSET_ZIP_INVALID`。
- 条目名按 UTF-8 字节流解析后统一为正斜杠、去前导斜杠，作为消费回调的 `relativePath`（即 zip 内相对路径，含子目录层级）。
- 通过 `ZipEntryConsumer` 函数式接口逐条回调，调用方处理即时落盘与识别。

### 4.2 ImageValidator 图片识别

`image/ImageValidator.java` 两级判定：

1. 扩展名白名单（不区分大小写）：`jpg / jpeg / png / webp`。
2. `ImageDecoder.canDecode(content)`：默认实现 `ImageIoImageDecoder` 用 `ImageIO.read` 探测；返回 `null` 或抛异常视为损坏。

返回 `ImageCheckResult(recognized, reason)`。`REASON_NON_WHITELIST` / `REASON_UNDECODABLE` 是公开常量。

> `ImageDecoder` 是函数式接口（`canDecode`），可被 `ImageValidator` 注入，便于属性测试替换为内存替身。

### 4.3 SkuExtractor SKU 提取

`SkuExtractor.java` 纯函数：

- 输入：去扩展名的文件名（如 `sub/dir/ABC-123.v2.jpg` → `ABC-123.v2`）。
- 规则：扫描首个连续 `[A-Za-z0-9]` 段；超 `MAX_LENGTH=255` 截断；无任何字母数字时回退到完整去扩展名文件名。
- 保留原始大小写。

### 4.3.1 GroupKeyExtractor 分组解析

`GroupKeyExtractor.java` 纯函数，按文件名编号解析分组维度（不做视觉识别）：

- 输入：去扩展名文件名，按 `_` 分段。
- 三段 `组id_商品id_图id` → `group_key=组id`、`sku_id=商品id`、`view_id=图id`，标记为分组成员。
- 两段 `组id_图id` → `group_key=null`、首段作 `sku_id`、`view_id=第二段`，普通单图。
- 其余段数 / 无 `_` → `group_key=null`，回退 `SkuExtractor` 取 `sku_id`，`view_id=null`。
- 同 `(package_id, group_key)`（`group_key` 非空）的若干 `asset_image` 构成一组，供 DAG 引擎按组聚合（见 `module-dag.md` §6.10）。
- 解析结果写入 `asset_image.group_key` / `view_id`，在 `scanPackage` 入库阶段一并落库。

### 4.4 文案文档解析 CopyDocumentParser

`copy/CopyDocumentParser.java`：

- 后缀白名单：`xls / xlsx / csv`（不区分大小写）。
- 体积校验：`docMaxSize`，越界 → `DOC_FORMAT_INVALID`。
- Excel 走 Apache POI `WorkbookFactory`，取首张 sheet，用 `DataFormatter` 把单元格统一格式化为字符串。
- CSV 走**手写解析器**（`readCsv`）：UTF-8（忽略 BOM），支持双引号包裹字段、字段内逗号 / 换行、`""` 转义双引号；以 `\r\n / \n / \r` 切记录。
- 表头匹配：去首尾空白 + 全部小写，要求存在 `id` 列，否则 `DOC_MISSING_ID_COLUMN`。
- 数据行数校验：`> docMaxRows` → `DOC_ROWS_EXCEEDED`。
- `id` 为空白的行**跳过**并记录 1 基行号（含表头偏移），其余行写出 `CopyRow(skuId, productName, keywords, description)`，缺列写空值。

`CopyParseResult` 聚合 `rows` + `skippedRowNumbers`，由 `AssetPackageService.persistCopies` 写入 `asset_copy`。

## 5. 列表 / 详情 / 删除

### 5.1 列表 `GET /api/asset/package/list`

- 入参：`page, size, sortBy(created_at|size|name), order(asc|desc)`。
- 排序字段非法回退 `created_at`；方向非 `asc` 回退 `desc`。
- 稳定次级排序 `id` 同方向，保证同值下分页结果确定。
- 响应：`PageResponse<PackageListItem>`，含 total。

### 5.2 详情 `GET /api/asset/package/{packageId}`

- 素材包不存在 → `PACKAGE_NOT_FOUND`。
- 含 `images: List<PackageImageItem>`（按 `id` 升序），每项含 `imageId / skuId / originalPath`。

### 5.3 删除 `DELETE /api/asset/package/{packageId}`

`PackageDeleter.delete(packageId)`（`@Transactional`）：

1. 校验素材包存在（`PACKAGE_NOT_FOUND`）。
2. 校验 `process_task.package_id` 引用：被引用 → `PACKAGE_REFERENCED_BY_TASK`（HTTP 409）。
3. 删除数据库记录（`asset_image` / `asset_copy` / `asset_package`）。
4. 物理删除 `packages/{packageId}` 目录下全部文件（自底向上），逐文件容错：失败项写入 `DeleteReport.FailedFile(path, reason)`，不中断整体删除。
5. 结果图位于 `results/{taskId}`，不在素材包目录下，**天然不会被删除**。

响应 `DeleteReport(packageId, deleted, deletedFileCount, failedFiles)`。

## 6. 域名对象

| 对象 | 含义 |
|---|---|
| `PackageStatus.PARSING=0 / READY=1 / PARSE_FAILED=2` | 素材包状态枚举常量 |
| `SkippedFile(name, reason)` | 跳过文件记录 |
| `PackageScanResult(recognizedImages, skippedFiles, status, failureReason)` | 解压扫描结果；`imageCount == 0` 时自动标 `PARSE_FAILED` 并附 `failureReason="未识别到任何合法图片"` |

## 7. 结果图预览与下载（`ResultPreviewController` + `ResultPreviewService` + `ResultDownloadService`）

> `ResultDownloadService` 在 `module/task` 之下，但流式 zip 与列表查询都通过 `module/file` 的 controller 暴露；详见 `module-task.md`。

预览与原始字节：

- `GET /api/asset/result/{resultId}/preview`：返回 `ResultPreviewResponse(resultId, skuId, url)`，`url = "/api/asset/result/{id}/raw"`。
- `GET /api/asset/result/{resultId}/raw`：通过 `StorageService.openInputStream` 流式返回字节；按扩展名推断 `MediaType`（png/jpg/gif/webp，其余 `APPLICATION_OCTET_STREAM`）。

`ResultPreviewService` 在 `previewUrl` / `resolveOutputPath` 之前调用 `requireExisting`：结果图不存在、`output_path` 为空或文件不存在一律抛 `RESULT_NOT_FOUND`（需求 4.7）。

## 8. 关键不变量

1. **重复 SKU 全量保留**：每张图片入库为独立 `asset_image`，拥有自增 `id` 与互不相同的 `original_path`。
2. **文案与图片弱关联**：无文案 / 无图 / 缺列均为合法状态，不报错；`asset_image.sku_id` 与 `asset_copy.sku_id` 通过业务键匹配，无外键。
3. **包状态由 imageCount 单点决定**：`imageCount > 0 → READY`，`== 0 → PARSE_FAILED`。
4. **失败上传不留脏数据**：`cleanup` 在 `RuntimeException` 分支执行，逐项 `try/catch`。
5. **结果图目录隔离**：`results/{taskId}` 与 `packages/{packageId}` 不存在包含关系，删包不影响结果图。
6. **删除时不被引用是硬约束**：`PACKAGE_REFERENCED_BY_TASK` 在 `PackageDeleter.delete` 第二步检查。
