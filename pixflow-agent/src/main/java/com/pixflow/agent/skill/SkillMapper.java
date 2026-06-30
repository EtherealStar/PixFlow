package com.pixflow.agent.skill;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * Skill MyBatis-Plus Mapper。
 *
 * <p>封装 skill 表的 CRUD。本期主要场景：
 * <ul>
 *   <li>{@code insert / updateById}：SkillLoader 同步入表</li>
 *   <li>{@code findByName}：SkillHandler 调用时取 body</li>
 *   <li>{@code findAllBySource}：SkillToolRegistrar 启动期枚举所有 skill</li>
 *   <li>{@code deleteByName} / {@code deleteBySourceNotIn}：清理已移除的 BUILTIN</li>
 * </ul>
 */
@Mapper
public interface SkillMapper extends BaseMapper<Skill> {

    @Select("SELECT * FROM skill WHERE name = #{name} LIMIT 1")
    Optional<Skill> findByName(String name);

    @Select("SELECT * FROM skill WHERE source = #{source}")
    List<Skill> findAllBySource(String source);

    @Select("SELECT name FROM skill WHERE source = 'BUILTIN'")
    List<String> findAllBuiltinNames();
}