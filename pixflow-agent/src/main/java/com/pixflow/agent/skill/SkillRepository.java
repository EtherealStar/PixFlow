package com.pixflow.agent.skill;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Skill 仓库门面（Repository pattern）。
 *
 * <p>封装 {@link SkillMapper}，屏蔽 MyBatis-Plus 细节，向上层提供
 * 业务语义 API（findByName / findAllBySource / upsert / deleteByName）。
 */
@Repository
public class SkillRepository {

    private final SkillMapper mapper;

    public SkillRepository(SkillMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Skill> findByName(String name) {
        return mapper.findByName(name);
    }

    public List<Skill> findAllBySource(SkillSource source) {
        return mapper.findAllBySource(source.name());
    }

    public List<Skill> findAllBuiltin() {
        return mapper.findAllBySource(SkillSource.BUILTIN.name());
    }

    public List<String> findAllBuiltinNames() {
        return mapper.findAllBuiltinNames();
    }

    public void insert(Skill skill) {
        mapper.insert(skill);
    }

    public void update(Skill skill) {
        mapper.updateById(skill);
    }

    public void deleteByName(String name) {
        Optional<Skill> existing = findByName(name);
        existing.ifPresent(s -> mapper.deleteById(s.getId()));
    }
}