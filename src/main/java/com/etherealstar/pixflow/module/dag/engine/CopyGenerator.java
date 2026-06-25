package com.etherealstar.pixflow.module.dag.engine;

import com.etherealstar.pixflow.infra.ai.LlmClient;
import com.etherealstar.pixflow.module.file.entity.AssetCopy;
import com.etherealstar.pixflow.module.file.entity.AssetImage;
import org.springframework.stereotype.Component;

/**
 * 文案生成分支（需求 10，DAG_Engine 组件 CopyBranch）。
 *
 * <p>{@code generate_copy} 被建模为独立分支：其输入仅为该 SKU 的 {@link AssetCopy} 上下文
 * （{@code product_name}、{@code keywords}、{@code description}），不依赖任何像素处理节点的输出
 * （需求 10.1、10.6）。当该 SKU 无对应文案条目时，以图片自身（SKU 与文件路径）为依据生成
 * （需求 10.5）。生成结果写入与该 SKU 匹配的 {@code process_result.generated_copy} 前，先截断至
 * 配置上限（默认 2000 字符，需求 10.4）。</p>
 *
 * <p>LLM 调用被隔离在 {@link LlmClient} 接口之后，便于以内存替身替换真实模型；调用失败按 SKU 维度
 * 由失败隔离逻辑处理（需求 10.7）。</p>
 */
@Component
public class CopyGenerator {

    private final LlmClient llmClient;
    private final EngineProperties properties;

    public CopyGenerator(LlmClient llmClient, EngineProperties properties) {
        this.llmClient = llmClient;
        this.properties = properties;
    }

    /**
     * 为某 SKU 生成营销文案。
     *
     * @param copy  该 SKU 的文案上下文条目，可为 {@code null}（无对应条目时回退到以图片为依据）
     * @param image 该 SKU 对应的图片（用于无文案条目时的兜底依据）
     * @param style 可选风格（{@code generate_copy} 的可选参数 style），可为 {@code null}
     * @return 截断至配置上限后的生成文案
     */
    public String generate(AssetCopy copy, AssetImage image, String style) {
        String systemPrompt = buildSystemPrompt(style);
        String userPrompt = buildUserPrompt(copy, image);
        String raw = llmClient.complete(systemPrompt, userPrompt);
        return truncate(raw);
    }

    private String buildSystemPrompt(String style) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深电商营销文案撰写者，请基于给定的商品信息撰写一段简洁、有吸引力的中文营销文案。");
        sb.append("仅输出文案正文本身，不要输出额外说明或标题。");
        if (style != null && !style.isBlank()) {
            sb.append("文案风格要求：").append(style.trim()).append("。");
        }
        return sb.toString();
    }

    /**
     * 构造用户提示词：有文案条目时上下文恰含该 SKU 的 product_name/keywords/description
     * （需求 10.2）；否则以图片自身（SKU 与路径）为依据（需求 10.5）。
     */
    private String buildUserPrompt(AssetCopy copy, AssetImage image) {
        StringBuilder sb = new StringBuilder();
        if (copy != null) {
            sb.append("商品名：").append(nullToEmpty(copy.getProductName())).append('\n');
            sb.append("关键词：").append(nullToEmpty(copy.getKeywords())).append('\n');
            sb.append("描述：").append(nullToEmpty(copy.getDescription())).append('\n');
        } else {
            sb.append("（该商品无文案条目，请以图片本身为依据撰写）\n");
            if (image != null) {
                sb.append("SKU：").append(nullToEmpty(image.getSkuId())).append('\n');
                sb.append("图片路径：").append(nullToEmpty(image.getOriginalPath())).append('\n');
            }
        }
        sb.append("请据此撰写营销文案。");
        return sb.toString();
    }

    /** 截断至配置上限并保证为输入前缀（需求 10.4、Property 30）。 */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = properties.getCopyMaxLength();
        if (max > 0 && text.length() > max) {
            return text.substring(0, max);
        }
        return text;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
