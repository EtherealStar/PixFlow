-- Skill 表
-- 对应 agent.md §5.4：11 字段（id / name / description / when_to_use / body /
-- source / version / body_hash / created_at / updated_at）+ 索引
-- 本期 BUILTIN 由 SkillLoader 启动期同步入表。

CREATE TABLE IF NOT EXISTS skill (
    id           VARCHAR(64)  NOT NULL,
    name         VARCHAR(50)  NOT NULL,
    description  VARCHAR(200) NOT NULL,
    when_to_use  VARCHAR(500) NOT NULL,
    body         MEDIUMTEXT   NOT NULL,
    source       VARCHAR(16)  NOT NULL,
    version      INT          NOT NULL,
    body_hash    VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_name (name),
    KEY idx_skill_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
