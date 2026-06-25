-- PixFlow 数据库建表脚本
-- 说明：asset_image 与 asset_copy 通过 sku_id 软关联，全库不使用数据库外键（软关联）。
-- 字段类型与设计文档 design.md「Data Models」一致。

-- ---------------------------------------------------------------------------
-- asset_package 素材包
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `asset_package` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(255) NOT NULL COMMENT '包名（取上传文件名）',
    `zip_path`    VARCHAR(500) DEFAULT NULL COMMENT 'zip 存储路径（insert 时可空，解压后回填）',
    `doc_path`    VARCHAR(500) DEFAULT NULL COMMENT '文案文档路径，可空',
    `size`        BIGINT       NOT NULL DEFAULT 0 COMMENT '上传 zip 体积（字节），供列表按 size 排序（需求 4.3）',
    `image_count` INT          NOT NULL DEFAULT 0 COMMENT '成功识别图片数',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 解析中 1 就绪 2 解析失败',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_asset_package_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '素材包';

-- ---------------------------------------------------------------------------
-- asset_image 单张图片（与 asset_copy 通过 sku_id 软关联，无外键）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `asset_image` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `package_id`    BIGINT       NOT NULL COMMENT '所属素材包 id（软关联，无外键）',
    `sku_id`        VARCHAR(255) NOT NULL COMMENT '从文件名提取的 SKU ID',
    `original_path` VARCHAR(500) NOT NULL COMMENT '相对 zip 根目录的相对路径',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_asset_image_package_id` (`package_id`),
    KEY `idx_asset_image_sku_id` (`sku_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '单张图片';

-- ---------------------------------------------------------------------------
-- asset_copy 文案条目（与 asset_image 通过 sku_id 软关联，无外键）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `asset_copy` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `package_id`   BIGINT       NOT NULL COMMENT '所属素材包 id（软关联，无外键）',
    `sku_id`       VARCHAR(255) NOT NULL COMMENT '软关联键（取文案文档 id 列）',
    `product_name` VARCHAR(255) DEFAULT NULL COMMENT '商品名',
    `keywords`     VARCHAR(500) DEFAULT NULL COMMENT '关键词',
    `description`  TEXT         DEFAULT NULL COMMENT '详细描述',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_asset_copy_package_id` (`package_id`),
    KEY `idx_asset_copy_sku_id` (`sku_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文案条目';

-- ---------------------------------------------------------------------------
-- conversation 对话
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`      VARCHAR(255) DEFAULT NULL COMMENT '取首条消息前 20 字',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话';

-- ---------------------------------------------------------------------------
-- message 消息（含 task_id：消息触发的处理任务关联，需求 5.6）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `message` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id`     BIGINT        NOT NULL COMMENT '所属对话 id（软关联，无外键）',
    `role`                VARCHAR(20)   NOT NULL COMMENT 'user / assistant',
    `content`             VARCHAR(4000) NOT NULL COMMENT '消息内容，最大 4000 字符',
    `attached_package_id` BIGINT        DEFAULT NULL COMMENT '关联素材包 id，可空',
    `task_id`             BIGINT        DEFAULT NULL COMMENT '该消息触发的处理任务 id，可空（需求 5.6）',
    `created_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_message_conversation_id` (`conversation_id`),
    KEY `idx_message_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息';

-- ---------------------------------------------------------------------------
-- process_task 处理任务
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `process_task` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT   NOT NULL COMMENT '来源对话 id（软关联，无外键）',
    `package_id`      BIGINT   NOT NULL COMMENT '处理的素材包 id（软关联，无外键）',
    `dag_json`        JSON     NOT NULL COMMENT 'LLM 解析出的 DAG 结构',
    `status`          TINYINT  NOT NULL DEFAULT 0 COMMENT '0 待执行 1 执行中 2 完成 3 失败',
    `total_count`     INT      NOT NULL DEFAULT 0 COMMENT '总图片数',
    `done_count`      INT      NOT NULL DEFAULT 0 COMMENT '已完成数',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `finished_at`     DATETIME DEFAULT NULL COMMENT '执行结束时刻',
    PRIMARY KEY (`id`),
    KEY `idx_process_task_package_id` (`package_id`),
    KEY `idx_process_task_status` (`status`),
    KEY `idx_process_task_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '处理任务';

-- ---------------------------------------------------------------------------
-- process_result 单张图处理结果（含 branch_id：支路标识，需求 9.2）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `process_result` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`        BIGINT        NOT NULL COMMENT '所属任务 id（软关联，无外键）',
    `image_id`       BIGINT        NOT NULL COMMENT '对应原图 id（软关联，无外键）',
    `sku_id`         VARCHAR(255)  NOT NULL COMMENT 'SKU ID',
    `branch_id`      VARCHAR(100)  NOT NULL COMMENT '支路标识，同一 image_id 下唯一（需求 9.2）',
    `output_path`    VARCHAR(500)  DEFAULT NULL COMMENT '处理后图片路径，失败时为空',
    `generated_copy` TEXT          DEFAULT NULL COMMENT '生成文案，最大 2000 字符',
    `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待处理 1 成功 2 失败',
    `error_msg`      VARCHAR(1000) DEFAULT NULL COMMENT '失败原因，最大 1000 字符',
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_process_result_task_id` (`task_id`),
    KEY `idx_process_result_image_id` (`image_id`),
    KEY `idx_process_result_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '单张图处理结果';
