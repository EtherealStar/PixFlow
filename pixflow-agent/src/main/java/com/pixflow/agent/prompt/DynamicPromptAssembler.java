package com.pixflow.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态 system prompt 装配器。
 *
 * <p>对应 {@code agent.md §4.5} 的核心实现：按固定顺序串行渲染各 section，
 * 过滤空 body，"\n\n" 拼接；查 cache / 写 cache。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@link #assemble(SectionRenderer.PromptRuntimeContext)} 必须是<b>纯函数</b>
 *       ——不调 IO（ArchUnit 6/6 守护）；记忆召回调到 AgentOrchestrator 入口</li>
 *   <li>空 body section 跳过，不进入最终 prompt</li>
 *   <li>section 顺序由构造期 List 决定，新 section 加在末尾而非中间插入</li>
 * </ul>
 */
@Component
public final class DynamicPromptAssembler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicPromptAssembler.class);

    private final List<SectionRenderer> sections;

    private final PromptSectionCache cache;

    public DynamicPromptAssembler(List<SectionRenderer> sections, PromptSectionCache cache) {
        this.sections = List.copyOf(sections);
        this.cache = cache;
        LOGGER.info("DynamicPromptAssembler initialized with {} sections", sections.size());
    }

    /**
     * 装配完整 system prompt 字符串。
     *
     * <p>流程：
     * <ol>
     *   <li>遍历 sections（固定顺序）</li>
     *   <li>每段调 {@link SectionRenderer#render} 拿 PromptSection</li>
     *   <li>空 body 过滤</li>
     *   <li>cache.get 命中复用；未命中调 render() 后 cache.put</li>
     *   <li>非空段用 "\n\n" 拼接</li>
     * </ol>
     */
    public String assemble(SectionRenderer.PromptRuntimeContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (SectionRenderer renderer : sections) {
            PromptSection section = renderer.render(ctx);
            if (section.body().isEmpty()) {
                // 空 body section 跳过
                continue;
            }
            String rendered;
            if (!section.cacheable()) {
                // 不参与缓存：每次都重新渲染
                rendered = section.render();
            } else {
                var cached = cache.get(section.key(), section.fingerprint());
                rendered = cached.orElseGet(() -> {
                    String fresh = section.render();
                    cache.put(section.key(), section.fingerprint(), fresh);
                    return fresh;
                });
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(rendered);
        }
        return sb.toString();
    }

    /**
     * 装配产物的只读摘要视图（给 trace / eval 用，不暴露完整 prompt）。
     */
    public PromptSummary summarize(SectionRenderer.PromptRuntimeContext ctx) {
        var sectionKeys = sections.stream()
                .map(r -> {
                    PromptSection s = r.render(ctx);
                    return new PromptSummary.SectionDigest(s.key(), s.fingerprint(), s.body().length());
                })
                .toList();
        long totalChars = sectionKeys.stream().mapToLong(PromptSummary.SectionDigest::bodyChars).sum();
        return new PromptSummary(sectionKeys, totalChars);
    }
}
