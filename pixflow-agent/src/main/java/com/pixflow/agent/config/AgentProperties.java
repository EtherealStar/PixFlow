package com.pixflow.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 模块集中配置（{@code @ConfigurationProperties(prefix="pixflow.agent")}）。
 *
 * <p>对应 {@code agent.md §十四} 配置项清单。
 *
 * <p>设计要点：
 * <ul>
 *   <li>14 个子段全部可独立覆盖</li>
 *   <li>所有集合类默认值不为 null（避免装配期 NPE）</li>
 *   <li>无 setXxx；嵌套类用 builder 风格（record）确保不可变</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "pixflow.agent")
public class AgentProperties {

    private Prompt prompt = new Prompt();

    private Skill skill = new Skill();

    private Memory memory = new Memory();

    private SessionMemory sessionMemory = new SessionMemory();

    private Subagent subagent = new Subagent();

    private PlanMode planMode = new PlanMode();

    private Orchestrator orchestrator = new Orchestrator();

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public SessionMemory getSessionMemory() {
        return sessionMemory;
    }

    public void setSessionMemory(SessionMemory sessionMemory) {
        this.sessionMemory = sessionMemory;
    }

    public Subagent getSubagent() {
        return subagent;
    }

    public void setSubagent(Subagent subagent) {
        this.subagent = subagent;
    }

    public PlanMode getPlanMode() {
        return planMode;
    }

    public void setPlanMode(PlanMode planMode) {
        this.planMode = planMode;
    }

    public Orchestrator getOrchestrator() {
        return orchestrator;
    }

    public void setOrchestrator(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 动态 Prompt 装配相关配置（§十四 prompt.*）。
     */
    public static class Prompt {

        private SectionCache sectionCache = new SectionCache();

        public SectionCache getSectionCache() {
            return sectionCache;
        }

        public void setSectionCache(SectionCache sectionCache) {
            this.sectionCache = sectionCache;
        }

        public static class SectionCache {

            private int maxEntries = 1000;

            private boolean enabled = true;

            public int getMaxEntries() {
                return maxEntries;
            }

            public void setMaxEntries(int maxEntries) {
                this.maxEntries = maxEntries;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }

    /**
     * Skill 机制配置（§十四 skill.*）。
     */
    public static class Skill {

        private boolean enabled = true;

        private int maxBodyBytes = 32768;

        private int descriptionMaxChars = 200;

        private int whenToUseMaxChars = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getDescriptionMaxChars() {
            return descriptionMaxChars;
        }

        public void setDescriptionMaxChars(int descriptionMaxChars) {
            this.descriptionMaxChars = descriptionMaxChars;
        }

        public int getWhenToUseMaxChars() {
            return whenToUseMaxChars;
        }

        public void setWhenToUseMaxChars(int whenToUseMaxChars) {
            this.whenToUseMaxChars = whenToUseMaxChars;
        }
    }

    /**
     * 自动记忆召回配置（§十四 memory.recall.*）。
     */
    public static class Memory {

        private Recall recall = new Recall();

        public Recall getRecall() {
            return recall;
        }

        public void setRecall(Recall recall) {
            this.recall = recall;
        }

        public static class Recall {

            private int maxTokens = 4000;

            public int getMaxTokens() {
                return maxTokens;
            }

            public void setMaxTokens(int maxTokens) {
                this.maxTokens = maxTokens;
            }
        }
    }

    /**
     * Session Memory 累积提取配置（§十四 session-memory.*）。
     */
    public static class SessionMemory {

        private boolean enabled = true;

        private Threshold threshold = new Threshold();

        private CircuitBreaker circuitBreaker = new CircuitBreaker();

        private Cache cache = new Cache();

        private int maxContentTokens = 12000;

        private int maxContentBytes = 65536;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Threshold getThreshold() {
            return threshold;
        }

        public void setThreshold(Threshold threshold) {
            this.threshold = threshold;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        public Cache getCache() {
            return cache;
        }

        public void setCache(Cache cache) {
            this.cache = cache;
        }

        public int getMaxContentTokens() {
            return maxContentTokens;
        }

        public void setMaxContentTokens(int maxContentTokens) {
            this.maxContentTokens = maxContentTokens;
        }

        public int getMaxContentBytes() {
            return maxContentBytes;
        }

        public void setMaxContentBytes(int maxContentBytes) {
            this.maxContentBytes = maxContentBytes;
        }

        public static class Threshold {

            private long tokens = 10000;

            private int turns = 3;

            public long getTokens() {
                return tokens;
            }

            public void setTokens(long tokens) {
                this.tokens = tokens;
            }

            public int getTurns() {
                return turns;
            }

            public void setTurns(int turns) {
                this.turns = turns;
            }
        }

        public static class CircuitBreaker {

            private int maxConsecutiveFailures = 3;

            public int getMaxConsecutiveFailures() {
                return maxConsecutiveFailures;
            }

            public void setMaxConsecutiveFailures(int maxConsecutiveFailures) {
                this.maxConsecutiveFailures = maxConsecutiveFailures;
            }
        }

        public static class Cache {

            private long ttlSeconds = 3600;

            public long getTtlSeconds() {
                return ttlSeconds;
            }

            public void setTtlSeconds(long ttlSeconds) {
                this.ttlSeconds = ttlSeconds;
            }
        }
    }

    /**
     * 异步 Subagent Runner 线程池与超时配置（§十四 subagent.*）。
     */
    public static class Subagent {

        private Pool pool = new Pool();

        private int timeoutSeconds = 60;

        private boolean shareSkillTools = true;

        private boolean shareReadonlyTools = true;

        public Pool getPool() {
            return pool;
        }

        public void setPool(Pool pool) {
            this.pool = pool;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isShareSkillTools() {
            return shareSkillTools;
        }

        public void setShareSkillTools(boolean shareSkillTools) {
            this.shareSkillTools = shareSkillTools;
        }

        public boolean isShareReadonlyTools() {
            return shareReadonlyTools;
        }

        public void setShareReadonlyTools(boolean shareReadonlyTools) {
            this.shareReadonlyTools = shareReadonlyTools;
        }

        public static class Pool {

            private int coreSize = 4;

            private int maxSize = 16;

            private int queueCapacity = 100;

            private int keepAliveSeconds = 60;

            public int getCoreSize() {
                return coreSize;
            }

            public void setCoreSize(int coreSize) {
                this.coreSize = coreSize;
            }

            public int getMaxSize() {
                return maxSize;
            }

            public void setMaxSize(int maxSize) {
                this.maxSize = maxSize;
            }

            public int getQueueCapacity() {
                return queueCapacity;
            }

            public void setQueueCapacity(int queueCapacity) {
                this.queueCapacity = queueCapacity;
            }

            public int getKeepAliveSeconds() {
                return keepAliveSeconds;
            }

            public void setKeepAliveSeconds(int keepAliveSeconds) {
                this.keepAliveSeconds = keepAliveSeconds;
            }
        }
    }

    /**
     * Plan 模式配置（§十四 plan-mode.*）。
     */
    public static class PlanMode {

        private boolean autoExitOnCompletion = false;

        private boolean keepDraftOnExit = true;

        public boolean isAutoExitOnCompletion() {
            return autoExitOnCompletion;
        }

        public void setAutoExitOnCompletion(boolean autoExitOnCompletion) {
            this.autoExitOnCompletion = autoExitOnCompletion;
        }

        public boolean isKeepDraftOnExit() {
            return keepDraftOnExit;
        }

        public void setKeepDraftOnExit(boolean keepDraftOnExit) {
            this.keepDraftOnExit = keepDraftOnExit;
        }
    }

    /**
     * Agent Orchestrator 配置（§十四 orchestrator.*）。
     */
    public static class Orchestrator {

        private int conversationLockTtlSeconds = 300;

        public int getConversationLockTtlSeconds() {
            return conversationLockTtlSeconds;
        }

        public void setConversationLockTtlSeconds(int conversationLockTtlSeconds) {
            this.conversationLockTtlSeconds = conversationLockTtlSeconds;
        }
    }
}
