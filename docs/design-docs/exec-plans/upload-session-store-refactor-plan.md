# 以 UploadSessionStore 深模块重构分片上传与断点续传

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。本文是自包含的中文执行计划；后续执行者只需要当前工作树和本文，就应能理解上传状态的所有权、完成重构、运行验证并判断结果是否符合契约。

## Purpose / Big Picture

完成本计划后，大文件上传在刷新、断网或客户端暂停后，可以用标准整文件 SHA-256 再次初始化并得到成功的 `RESUME` 响应，只上传缺失分片。后端业务服务只理解上传会话快照、分片幂等结果和会话终结，不再理解 Redis key、Hash field、TTL、Redisson 或逐分片扫描。Redis 暂时不可用时请求明确失败，不会被误判为新上传或分片缺失；取消会清理 Redis 与 MinIO 临时对象，暂停则完整保留它们。

可观察结果是：同一 `fileHash` 的首次 init 返回 `UPLOAD`，上传部分分片后再次 init 返回相同 `uploadId` 的 `RESUME` 和已上传索引；相同 `chunkHash` 的重复 PUT 返回 `ALREADY_EXISTS`，不同 hash 返回校验冲突；Redis 故障不会创建第二个会话；complete 只使用一次 Hash 快照校验全部分片。

## Progress

- [x] (2026-07-11 00:00+08:00) 阅读 active ExecPlans、`docs/design-docs/index.md`、`PLANS.md` 以及 file/cache/frontend/web 相关上传设计。
- [x] (2026-07-11 00:05+08:00) 核对现有实现，确认旧 `UploadSessionStore` 是具体类，直接依赖可降级 `CacheStore`，分片进度按 `expectedChunks` 循环 `EXISTS`，active init 错误返回 `DEDUP`。
- [x] (2026-07-12 00:00+08:00) 实现 infra/cache 的 fail-closed `ExpiringStateStore` 与 `ExpiringHashStore`、Redisson adapter，并通过真实 Redis 集成测试验证 JSON、TTL 与 Hash 批量读取。
- [x] (2026-07-12 00:00+08:00) 将 `UploadSessionStore` 替换为领域 interface，新增 `RedisUploadSessionStore`、不可变 `UploadSnapshot` 与 `ChunkWriteResult`，删除旧 CacheStore 具体实现和全部旧方法。
- [x] (2026-07-12 00:00+08:00) 重构 `UploadSessionService` 使用单次快照，补齐 `UPLOAD | RESUME | DEDUP`、分片幂等、对象缺失重传、complete 回滚和 cancel 前缀清理语义。
- [x] (2026-07-12 00:08+08:00) 更新自动装配与测试；重点测试 10 项通过，触达模块及依赖测试通过。全仓最终重跑受并发中的 `pixflow-infra-ai` 未完成签名修改阻断。
- [x] (2026-07-12 00:08+08:00) 使用 code-review 技能完成标准与规格双轴审查，修复重复 Redis 写、TTL 续期和 READY/消息发布顺序问题；按用户要求未提交分支。

## Surprises & Discoveries

- Observation: 现有 `CacheStore` 的 get/put/delete 故障策略会吞异常或返回 miss，不能承载上传会话这种权威临时状态。
  Evidence: `RedissonCacheStore.get` catch 后返回 `Optional.empty()`，`put/delete` catch 后只记录 warning。

- Observation: 现有进度读取、complete 和 cancel 都按 `expectedChunks` 次数循环读取单独 chunk key。
  Evidence: `UploadSessionStore.uploadedChunks`、`UploadSessionService.loadAllChunks` 与 `existingChunks` 均存在 index 循环。

- Observation: MinIO TMP 桶已经由 `StorageInitializer` 配置生命周期过期规则，能够兜底清理 Redis TTL 先到期后失去引用的临时对象。
  Evidence: `StorageInitializer.configureTmpLifecycle()` 安装 `pixflow-tmp-expire` 规则，`StorageProperties.tmpExpiryDays` 默认 1 天。

- Observation: `docker-compose.yml` 的 Redis 已启用 AOF 并挂载持久卷。
  Evidence: Redis command 为 `redis-server --appendonly yes`，数据目录挂载 `redis_data:/data`。

## Decision Log

- Decision: `UploadSessionStore` 是 module/file 的 interface，生产实现命名为 `RedisUploadSessionStore`，内存实现仅服务同一份 interface 契约测试。
  Rationale: seam 必须表达上传领域语义并隐藏 Redis；测试替换的是 adapter，而不是让业务服务知道缓存原语。
  Date/Author: 2026-07-11 / Codex

- Decision: 快照 `UploadSnapshot` 一次携带 `UploadSession` 与按 index 排序的不可变 `Map<Integer, ChunkMetadata>`。
  Rationale: init、进度、complete、cancel 都可从同一批量快照工作，消除逐 index `EXISTS/HGET` 扫描和跨读取漂移。
  Date/Author: 2026-07-11 / Codex

- Decision: `recordChunk` 返回 `CREATED | ALREADY_EXISTS | HASH_CONFLICT`，只在 MinIO put 成功后调用；发现 Redis metadata 指向的 MinIO 对象缺失时，业务服务先从 store 删除该 field，再重新接收分片。
  Rationale: Redis 只能确认已成功落盘对象；重复 PUT 的 hash 冲突属于上传语义，不能泄露到 cache primitive。
  Date/Author: 2026-07-11 / Codex

- Decision: session 使用 Redis Hash，而 fileHash active index 使用 String，chunks 使用 Redis Hash；三个 key 在 create、有效 resume、成功 chunk 和状态迁移时续到同一个 TTL 窗口。
  Rationale: 这与已确认的三个业务映射一致；统一续期避免 active index、session 和 chunks 分别过期形成部分状态。
  Date/Author: 2026-07-11 / Codex

- Decision: complete/cancel 继续使用现有分布式锁，但锁 key 只留在 service 的并发协调边界；Redis 状态布局只存在于 adapter。
  Rationale: 锁不是恢复事实源。终态串行化与 store 深模块是不同职责，不应把整个业务流程塞入 adapter。
  Date/Author: 2026-07-11 / Codex

## Outcomes & Retrospective

计划主体已完成。上传服务不再依赖 `CacheStore` 或逐分片 Redis key；init 对有效会话返回携带原始分片形状的 `RESUME`，分片写入以 MinIO 成功为 Redis 可见前提，complete/cancel 均从单次不可变快照工作。真实 Redis/MinIO 的触达模块测试及 file 重点测试均通过。

验证证据：`mvn -pl pixflow-infra-cache,pixflow-infra-storage,pixflow-module-file -am test` 在 2026-07-11 23:59+08:00 成功；修复审查意见后，隔离运行的 `UploadSessionServiceTest`、`UploadSessionStoreContractTest`、`FileAutoConfigurationTest` 共 10 项成功。最终全仓重跑被同时进行的 `pixflow-infra-ai` 构造器和 `ConcurrencyGuard` 签名迁移阻断，该问题不属于本计划且未在此处修改。

残余测试缺口是完整 complete 成功路径的标准整文件 SHA-256 服务级测试，以及 Redis 进程在操作中途停止的 fail-closed 测试；当前真实 Redis 集成已覆盖普通 JSON、TTL、fields/entries，服务测试覆盖 RESUME、幂等/冲突、缺对象重传、不完整 complete 回滚与 cancel 清理。

## Context and Orientation

`pixflow-module-file/src/main/java/com/pixflow/module/file/upload/UploadSessionService.java` 实现 init、分片 PUT、complete 和 cancel。当前同包下的 `UploadSessionStore.java` 是一个具体类，并直接使用 `pixflow-infra-cache` 的 `CacheStore`、`CacheKey` 与 `CacheNamespace`。这正是本次要删除的旧路径。

`pixflow-infra-cache` 中的 `CacheStore` 是可丢失的性能缓存：Redis 读取失败会降级成 miss，写入和删除失败会被吞掉。上传会话不同，它在 TTL 生命周期内决定是否恢复旧会话、哪些分片可信、是否可以 complete，因此必须使用新的 fail-closed 原语。fail-closed 指 Redis 操作失败时抛出 `CacheException`，请求失败并等待依赖恢复，而不是假装没有状态。

`ExpiringStateStore` 是带 TTL 的单 value key 原语；`ExpiringHashStore` 是带 TTL 的 Redis Hash 原语。它们只负责 JSON 序列化、KV/field 操作、批量 `fields/entries` 和过期时间，不理解 fileHash、uploadId 或 chunkHash。

`UploadSessionStore` 是更深一层的业务模块接口。它把三个 Redis 映射组合为一个上传会话快照，并负责 chunkHash 幂等判定、相关 key 续期和生命周期删除。`UploadSessionService` 只调用该接口。

临时分片对象位于逻辑 `BucketType.TMP` 的 `uploads/{uploadId}/chunks/{index}`。写入顺序固定为 MinIO 成功后记录 Redis；如果 Redis 写失败，TMP 生命周期规则最终清理无引用对象。取消主动删除整个 upload prefix 并删除 store 状态；暂停没有后端动作。

## Plan of Work

第一里程碑建立 fail-closed cache 原语。在 `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/state/` 新增两个 interface 和 Redisson 实现。String 与 Hash value 都通过项目 `ObjectMapper` 转成普通 JSON 并显式使用 `StringCodec`。所有异常包装为 `CacheException`；`put` 保证有效 TTL，Hash put 后刷新整个 key TTL；`fields` 使用一次 Hash key set 读取，`entries` 使用一次 map readAll。自动配置注册两个 adapter。测试以真实 Redis 验证 JSON、TTL、fields/entries 和服务停止后的异常上抛。

第二里程碑替换 store seam。把 `UploadSessionStore.java` 改为 interface，定义 `findByFileHash`、`findByUploadId`、`create`、`touch`、`saveSession`、`recordChunk`、`removeChunk`、`delete` 等上传语义；新增 `UploadSnapshot` 和 `ChunkWriteResult`。新增 `RedisUploadSessionStore`，在这里集中构造 `upload:filehash:{fileHash}`、`upload:session:{uploadId}`、`upload:chunks:{uploadId}`，将 session 存为 Hash fields、chunks 存为 index field，并统一续期。旧 `CacheStore` 具体实现和逐分片 key 方法全部删除，不保留兼容重载。

第三里程碑重写服务调用。init 在 fileHash 锁内先查 READY 包，再读取 active 快照；有效 active 会话执行 touch 并返回 `RESUME`，包含原 chunkSize、expectedChunks 和索引。PUT 在单分片锁内读取一次快照：已有 metadata 时先确认 MinIO 对象存在且大小一致；存在且 hash 相同返回 `ALREADY_EXISTS`，hash 不同返回冲突；对象缺失则删除 field 并重新上传。新对象校验并写入 MinIO 成功后才调用 `recordChunk`。complete/cancel 从一次快照取得全部 metadata，不再循环读取 Redis。

`recordChunk` 的并发前提由当前 `(uploadId,index)` 分布式锁保证，store 在锁内完成旧 metadata 判定、写入 chunks Hash 和三个相关 key 的 TTL 续期。这里的“原子记录”指同一分片并发请求只产生一个领域结果，调用方不会先查再自行决定 `HSET`；不要求把 MinIO 与 Redis 伪装成一个不存在的跨系统事务。

第四里程碑补合同和跨存储测试。抽象契约测试分别运行内存 adapter 与 Redis adapter，覆盖 create/resume、快照排序、chunk 幂等、hash 冲突、统一 TTL 和删除。服务测试使用内存 store 与内存 object storage，覆盖 MinIO 成功后 Redis 才可见、Redis field 对象缺失允许重传、Redis 记录失败不返回成功、cancel 清理前缀、complete 不完整拒绝和标准整文件 SHA-256。

第五里程碑更新装配和运维证据。`FileAutoConfiguration` 只在 `ExpiringStateStore`、`ExpiringHashStore` 存在时创建 `RedisUploadSessionStore`；测试装配不再提供 `CacheStore` fake。确认 `docker-compose.yml` 保持 AOF + 持久卷，`StorageInitializer` 的 TMP 生命周期继续作为孤儿清理兜底。根据实现更新相关设计文档和本计划的 living sections。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。

先运行 cache 原语重点测试：

    mvn -pl pixflow-infra-cache -Dtest=ExpiringStateStoreTest,RedisIntegrationTest test

再运行 file 模块重点测试：

    mvn -pl pixflow-module-file -Dtest=UploadSessionStoreContractTest,UploadSessionServiceTest,FileAutoConfigurationTest test

阶段性编译：

    mvn -pl pixflow-infra-cache,pixflow-module-file -am -DskipTests compile

最终运行触达模块及依赖测试：

    mvn -pl pixflow-infra-cache,pixflow-infra-storage,pixflow-module-file -am test

最后运行全仓测试：

    mvn test

收尾搜索应不再命中旧路径：

    rg -n "CacheStore|exists\(chunkKey|dedupUploading|for \(int i = 0; i < session.expectedChunks" pixflow-module-file/src/main/java/com/pixflow/module/file/upload pixflow-module-file/src/main/java/com/pixflow/module/file/config

## Validation and Acceptance

首次 init 一个没有 READY 包且没有 active 会话的 fileHash，响应必须是 `UPLOAD`，包含非空 uploadId、配置 chunkSize、expectedChunks 和空 uploadedChunks。

成功上传 index 0 后再次 init 相同 fileHash，响应必须是 `RESUME`，uploadId 与首次相同，chunkSize/expectedChunks 不为零，uploadedChunks 为 `[0]`。这条路径是 HTTP 200 的成功响应，不得抛冲突错误或返回 `DEDUP`。

同一 index、同一 chunkHash 的重复 PUT 在 MinIO 对象仍存在时返回 `ALREADY_EXISTS`；同一 index、不同 chunkHash 返回 `CHUNK_HASH_MISMATCH`。如果 Redis metadata 存在但 MinIO 对象已经缺失，同 hash PUT 必须删除陈旧 field、重新写对象并返回 `ACCEPTED`。

让 Redis 在读取 active index 或写 chunk metadata 时不可用，请求必须失败并暴露依赖错误；不得返回空进度、创建第二个 uploadId 或向客户端报告 chunk 成功。

complete 使用 snapshot 中一次性取出的 chunk map 验证 `[0, expectedChunks)` 全覆盖，按 index 合并原始字节并计算 `SHA-256(fileBytes)`；缺 chunk、对象缺失、对象大小错误或整包 hash 不一致均不得创建成功包。

跨存储故障必须按以下固定语义验收。MinIO 写失败时 Redis 中没有该 field；MinIO 写成功而 Redis 写失败时请求失败，确定性对象 key 可由重试覆盖，TMP 生命周期负责最终兜底；Redis field 存在而 MinIO 对象缺失或大小不符时 field 失效并允许重传；Redis 故障时上述检查整体失败，不能返回 cache miss。complete 对所有 snapshot metadata 再做对象存在性和大小检查，因此不会只凭 Redis field 宣告完整。

pause 不对应后端 DELETE，状态自然保留。cancel 删除 `uploads/{uploadId}/` 下临时对象并删除 fileHash/session/chunks 三类状态；重复 cancel 保持幂等。TMP 桶生命周期规则继续回收因 Redis TTL 或跨存储部分失败遗留的孤儿对象。

## Idempotence and Recovery

代码和测试修改可以重复执行。若重构中编译提示旧 `UploadSessionStore` 构造器或 `CacheStore` 方法缺失，应更新调用方到新 interface，不得恢复旧构造器或双路径。

MinIO put 成功而 Redis `recordChunk` 失败时，不删除可能仍在被重试请求使用的对象；本次请求失败，后续同 index PUT 可覆盖同一确定性 key，TMP 生命周期兜底清理。Redis metadata 写成功前服务绝不返回成功。

complete 失败时状态回到 `UPLOADING`，已有 chunks 保留供重试。cancel 先依据 snapshot 清理对象 prefix，再删除 store；如果对象存储删除失败，Redis 状态保留，使 cancel 可重试，并由生命周期规则兜底。

工作树已有与 cache rate limiter 和设计文档相关的用户改动。实施时只在必要位置叠加本任务改动，不回退或重写这些变化。

## Artifacts and Notes

目标调用关系：

    UploadSessionService
        -> UploadSessionStore
            -> RedisUploadSessionStore
                -> ExpiringStateStore / ExpiringHashStore
                    -> Redisson

    UploadSessionStore contract test
        -> InMemoryUploadSessionStore

目标状态布局：

    fileHash -> active uploadId
    uploadId -> session Hash
    uploadId -> chunks Hash<index, ChunkMetadata>
    uploadId/index -> MinIO TMP object

## Interfaces and Dependencies

`pixflow-infra-cache` 最终提供：

    public interface ExpiringStateStore {
        <T> Optional<T> get(CacheKey key, Class<T> type);
        <T> void put(CacheKey key, T value, Duration ttl);
        <T> boolean putIfAbsent(CacheKey key, T value, Duration ttl);
        void expire(CacheKey key, Duration ttl);
        void delete(CacheKey key);
    }

    public interface ExpiringHashStore {
        <T> Optional<T> get(CacheKey key, String field, Class<T> type);
        <T> void put(CacheKey key, String field, T value, Duration ttl);
        <T> Map<String, T> entries(CacheKey key, Class<T> type);
        Set<String> fields(CacheKey key);
        void deleteField(CacheKey key, String field);
        void expire(CacheKey key, Duration ttl);
        void delete(CacheKey key);
    }

`pixflow-module-file` 最终由 `UploadSessionStore` 暴露不可变 `UploadSnapshot` 和 `ChunkWriteResult`。`UploadSessionService`、controller 与测试业务场景不得 import cache key、Hash 或 Redisson 类型。生产 adapter 可以依赖 cache 原语，但不得直接依赖 `RedissonClient`。

建议最终接口固定为以下形态；若实施时因 Java 可见性拆分 record 文件，方法语义和数据边界不得改变：

    public interface UploadSessionStore {
        Optional<UploadSnapshot> findByFileHash(String fileHash);
        Optional<UploadSnapshot> findByUploadId(String uploadId);
        void create(UploadSession session);
        void touch(UploadSnapshot snapshot);
        void save(UploadSession session);
        ChunkWriteResult recordChunk(String uploadId, ChunkMetadata metadata);
        void removeChunk(String uploadId, int index);
        void clearChunksAndActive(UploadSession session);
        void delete(UploadSnapshot snapshot);
    }

    public record UploadSnapshot(
            UploadSession session,
            Map<Integer, ChunkMetadata> chunks) {
        // 构造时防御性复制为按 index 排序的不可变 Map。
    }

    public enum ChunkWriteResult {
        CREATED,
        ALREADY_EXISTS,
        HASH_CONFLICT
    }

生产 adapter 的 session Hash 字段固定为 `filename`、`size`、`fileHash`、`chunkSize`、`expectedChunks`、`status`、`packageId`、`createdAt`、`updatedAt`、`expiresAt`。`packageId` 未产生时省略或保存明确的 null 编码，但读写必须对称。`expiresAt` 是三个业务 key 本次统一续期后的逻辑到期时间，用于快照和排障；Redis TTL 才是实际驱逐机制。chunks Hash field 使用十进制 index 字符串，value 使用普通 JSON `ChunkMetadata`。

## Revision Notes

2026-07-11 / Codex: 新建计划。原因是现有上传代码仍用可降级 `CacheStore` 和每分片 KV 扫描，无法满足已确认的 `UploadSessionStore` 深模块、Redis Hash、fail-closed、RESUME 成功响应和跨存储一致性契约。

2026-07-12 / Codex: 完成计划主体并记录验证结果、审查修复与并发工作树造成的全仓测试阻断；按用户明确要求不创建提交。
