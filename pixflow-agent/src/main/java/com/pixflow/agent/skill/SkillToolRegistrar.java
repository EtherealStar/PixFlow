package com.pixflow.agent.skill;

import com.pixflow.harness.tools.ToolClassifier;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolInputValidator;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.harness.tools.ToolResultPolicy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期把 skill 动态注册为 {@code skill__<name>} tool descriptor。
 *
 * <p>对应 {@code agent.md §5.6}：
 * <ul>
 *   <li>查 {@code skillRepository.findAllBySource(BUILTIN)}</li>
 *   <li>对每条 skill 构造 {@link ToolDescriptor}</li>
 *   <li>调 {@code toolRegistry.registerDynamic(descriptor)}</li>
 * </ul>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>启动期一次性注册（{@code @PostConstruct}）</li>
 *   <li>重复 name 抛 IllegalStateException（registry 内部已校验）</li>
 *   <li>readOnlyHint = true（skill 是只读知识查阅）</li>
 *   <li>prompt 字段填 description + 调用时机（与 schema 层 description 解耦）</li>
 * </ul>
 */
@Component
public class SkillToolRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkillToolRegistrar.class);

    private static final String PREFIX = "skill__";

    private final SkillRepository skillRepository;

    private final SkillHandler skillHandler;

    private final ApplicationContext applicationContext;

    public SkillToolRegistrar(SkillRepository skillRepository,
                              SkillHandler skillHandler,
                              ApplicationContext applicationContext) {
        this.skillRepository = skillRepository;
        this.skillHandler = skillHandler;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void registerAll() {
        ToolRegistry registry = lookupToolRegistry();
        if (registry == null) {
            LOGGER.warn("SkillToolRegistrar: ToolRegistry bean not found - skill tools not registered");
            return;
        }
        List<Skill> skills = skillRepository.findAllBuiltin();
        if (skills.isEmpty()) {
            LOGGER.info("SkillToolRegistrar: no BUILTIN skills to register");
            return;
        }
        for (Skill skill : skills) {
            ToolDescriptor descriptor = buildDescriptor(skill);
            registry.registerDynamic(descriptor);
        }
        LOGGER.info("SkillToolRegistrar: registered {} skill tools", skills.size());
    }

    private ToolDescriptor buildDescriptor(Skill skill) {
        String toolName = PREFIX + skill.getName();
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> questionProp = new LinkedHashMap<>();
        questionProp.put("type", "string");
        questionProp.put("description", "可选：具体咨询的问题（本期 handler 忽略）");
        properties.put("question", questionProp);
        inputSchema.put("properties", properties);
        inputSchema.put("additionalProperties", false);

        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        Map<String, Object> outProps = new LinkedHashMap<>();
        outProps.put("body", Map.of("type", "string", "description", "skill 规范正文"));
        outputSchema.put("properties", outProps);

        String prompt = skill.getDescription() + "\n调用时机：" + skill.getWhenToUse();

        return new ToolDescriptor(
                toolName,
                skill.getDescription(),
                inputSchema,
                outputSchema,
                prompt,
                true, // readOnlyHint
                skillHandler,
                ToolClassifier.defaultClassifier(),
                ToolInputValidator.noop(),
                ToolResultPolicy.defaults()
        );
    }

    private ToolRegistry lookupToolRegistry() {
        try {
            return applicationContext.getBean(ToolRegistry.class);
        } catch (Exception e) {
            return null;
        }
    }
}
