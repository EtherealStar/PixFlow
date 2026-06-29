package com.pixflow.module.imagegen.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * imagegen 模块配置(对齐 imagegen.md §十三)。
 *
 * <p>承载提案护栏、生图输出参数、源图解析白名单与字节防护、装配开关。
 * 生图模型型号 / 紧超时 / 全局并发 **不在此** ——归 {@code infra/ai} 的 {@code pixflow.ai.roles.imagegen}。
 */
@ConfigurationProperties(prefix = "pixflow.imagegen")
public class ImagegenProperties {

    private Proposal proposal = new Proposal();
    private Output output = new Output();
    private Source source = new Source();
    private Executor executor = new Executor();

    public Proposal getProposal() {
        return proposal;
    }

    public void setProposal(Proposal proposal) {
        this.proposal = proposal;
    }

    public Output getOutput() {
        return output;
    }

    public void setOutput(Output output) {
        this.output = output;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /** 提案入参护栏。 */
    public static class Proposal {
        /** 单条生图提案的源图张数上限(超限 → IMAGEGEN_TOO_MANY_SOURCES)。 */
        private int maxSourceImages = 200;
        /** prompt 长度下界。 */
        private int promptMinChars = 1;
        /** prompt 长度上界。 */
        private int promptMaxChars = 2000;
        /** params 白名单(白名单外键 → IMAGEGEN_PROMPT_INVALID)。 */
        private List<String> allowedParamKeys = List.of("style", "strength", "negative_prompt", "seed");

        public int getMaxSourceImages() {
            return maxSourceImages;
        }

        public void setMaxSourceImages(int maxSourceImages) {
            this.maxSourceImages = maxSourceImages;
        }

        public int getPromptMinChars() {
            return promptMinChars;
        }

        public void setPromptMinChars(int promptMinChars) {
            this.promptMinChars = promptMinChars;
        }

        public int getPromptMaxChars() {
            return promptMaxChars;
        }

        public void setPromptMaxChars(int promptMaxChars) {
            this.promptMaxChars = promptMaxChars;
        }

        public List<String> getAllowedParamKeys() {
            return allowedParamKeys;
        }

        public void setAllowedParamKeys(List<String> allowedParamKeys) {
            this.allowedParamKeys = allowedParamKeys;
        }
    }

    /** 生图输出参数。 */
    public static class Output {
        /** 默认输出扩展名(落 GENERATED 桶时使用)。 */
        private String defaultExt = "png";
        /** 生成图字节防护:超过此值 → IMAGEGEN_OUTPUT_BYTES_TOO_LARGE(单位隔离)。 */
        private long maxOutputBytes = 52_428_800L; // 50 MiB

        public String getDefaultExt() {
            return defaultExt;
        }

        public void setDefaultExt(String defaultExt) {
            this.defaultExt = defaultExt;
        }

        public long getMaxOutputBytes() {
            return maxOutputBytes;
        }

        public void setMaxOutputBytes(long maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
        }
    }

    /** 源图解析白名单与字节防护。 */
    public static class Source {
        /** 源图内容类型白名单(仅形状校验,不实际解码)。 */
        private List<String> supportedTypes = List.of("image/jpeg", "image/png", "image/webp");
        /** 源图字节解析上限:超过此值不读取(保护堆)。 */
        private long maxReadBytes = 52_428_800L; // 50 MiB

        public List<String> getSupportedTypes() {
            return supportedTypes;
        }

        public void setSupportedTypes(List<String> supportedTypes) {
            this.supportedTypes = supportedTypes;
        }

        public long getMaxReadBytes() {
            return maxReadBytes;
        }

        public void setMaxReadBytes(long maxReadBytes) {
            this.maxReadBytes = maxReadBytes;
        }
    }

    /**
     * 执行器装配开关(对齐 imagegen.md §三 / §十六.14)。
     *
     * <p>Wave 3 默认 {@code expose=false},由 imagegen 自己的 {@code ImagegenAutoConfiguration}
     * 隐藏 {@link com.pixflow.module.imagegen.exec.DefaultImageGenExecutor};
     * Wave 4 task 模块就绪后,由 task 的 AutoConfiguration 设置 {@code pixflow.imagegen.executor.expose=true}
     * 并主动 import executor 的 @Bean。
     */
    public static class Executor {
        private boolean expose = false;

        public boolean isExpose() {
            return expose;
        }

        public void setExpose(boolean expose) {
            this.expose = expose;
        }
    }
}