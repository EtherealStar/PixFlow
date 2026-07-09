package com.pixflow.agent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Skill 启动期同步器。
 *
 * <p>对应 agent.md §5.5：
 * 扫 classpath 下 skills 目录下的 SKILL.md 文件，
 * 解析 frontmatter + body，SELECT existing by name，
 * INSERT / UPDATE / skip（基于 body_hash 决定），
 * 删除启动时未出现的 BUILTIN skill（处理 skill 被移除场景）。
 *
 * <p>关键不变量：
 * 格式校验失败 → 记 WARN + 指标，不抛异常（保证启动不被一个坏 skill 卡死）；
 * 同步幂等：基于 body_hash 决定是否 UPDATE；
 * PROJECT/TEAM 来源的 skill 不删（仅清 BUILTIN）。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_PATTERN = "classpath:skills/*/SKILL.md";

    private final SkillRepository repository;
    private final SkillFrontmatterParser parser;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public SkillLoader(SkillRepository repository, SkillFrontmatterParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    @PostConstruct
    public void syncBuiltIn() {
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILL_PATTERN);
        } catch (Exception e) {
            log.warn("SkillLoader: failed to scan classpath:skills/", e);
            return;
        }
        Set<String> seenNames = new HashSet<>();
        for (Resource res : resources) {
            String path = res.getDescription();
            try {
                String content;
                try (InputStream in = res.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                var parsed = parser.parse(content);
                String name = parsed.frontmatter().name();
                String bodyHash = md5(content);
                seenNames.add(name);
                upsertSkill(parsed, bodyHash);
            } catch (SkillParseException e) {
                log.warn("SkillLoader: invalid SKILL.md at {}", path, e);
            } catch (Exception e) {
                log.warn("SkillLoader: failed to load {}", path, e);
            }
        }
        // 删除启动时未出现的 BUILTIN skill（被移除场景）
        try {
            for (String existingName : repository.findAllBuiltinNames()) {
                if (!seenNames.contains(existingName)) {
                    log.info("SkillLoader: removing stale BUILTIN skill '{}'", existingName);
                    repository.deleteByName(existingName);
                }
            }
        } catch (Exception e) {
            log.warn("SkillLoader: stale-skill cleanup failed", e);
        }
    }

    private void upsertSkill(SkillFrontmatterParser.ParsedSkill parsed, String bodyHash) {
        String name = parsed.frontmatter().name();
        repository.findByName(name).ifPresentOrElse(
                existing -> {
                    if (!bodyHash.equals(existing.getBodyHash())) {
                        existing.setDescription(parsed.frontmatter().description());
                        existing.setWhenToUse(parsed.frontmatter().whenToUse());
                        existing.setBody(parsed.body());
                        existing.setVersion(parsed.frontmatter().version());
                        existing.setBodyHash(bodyHash);
                        existing.setUpdatedAt(Instant.now());
                        repository.update(existing);
                        log.info("SkillLoader: updated BUILTIN skill '{}'", name);
                    } else {
                        log.debug("SkillLoader: BUILTIN skill '{}' unchanged (body_hash match)", name);
                    }
                },
                () -> {
                    Skill skill = new Skill();
                    skill.setName(name);
                    skill.setDescription(parsed.frontmatter().description());
                    skill.setWhenToUse(parsed.frontmatter().whenToUse());
                    skill.setBody(parsed.body());
                    skill.setSource(SkillSource.BUILTIN.name());
                    skill.setVersion(parsed.frontmatter().version());
                    skill.setBodyHash(bodyHash);
                    Instant now = Instant.now();
                    skill.setCreatedAt(now);
                    skill.setUpdatedAt(now);
                    repository.insert(skill);
                    log.info("SkillLoader: inserted BUILTIN skill '{}'", name);
                }
        );
    }

    private static String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
