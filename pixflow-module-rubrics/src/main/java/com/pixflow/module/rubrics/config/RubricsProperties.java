package com.pixflow.module.rubrics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.rubrics")
public class RubricsProperties {
    private TemplateScan templateScan = new TemplateScan();

    private Automation automation = new Automation();

    private int runnerConcurrency = 2;

    private int queueCapacity = 100;

    private Duration claimLease = Duration.ofMinutes(5);

    private Duration recoveryInterval = Duration.ofSeconds(30);

    private int recoveryBatchSize = 100;

    private int maxRationaleChars = 2000;

    public TemplateScan getTemplateScan() {
        return templateScan;
    }

    public void setTemplateScan(TemplateScan value) {
        templateScan = value;
    }

    public Automation getAutomation() {
        return automation;
    }

    public void setAutomation(Automation value) {
        automation = value;
    }

    public int getRunnerConcurrency() {
        return runnerConcurrency;
    }

    public void setRunnerConcurrency(int value) {
        runnerConcurrency = value;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int value) {
        queueCapacity = value;
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration value) {
        claimLease = value;
    }

    public Duration getRecoveryInterval() {
        return recoveryInterval;
    }

    public void setRecoveryInterval(Duration value) {
        recoveryInterval = value;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int value) {
        recoveryBatchSize = value;
    }

    public int getMaxRationaleChars() {
        return maxRationaleChars;
    }

    public void setMaxRationaleChars(int value) {
        maxRationaleChars = value;
    }

    /** 在组合根创建执行器或模型客户端前拒绝无效运行配置。 */
    public void validate() {
        if (templateScan == null || automation == null || automation.event == null
                || automation.scheduled == null || runnerConcurrency <= 0
                || queueCapacity <= 0 || recoveryBatchSize <= 0
                || maxRationaleChars <= 0 || claimLease == null || claimLease.isNegative()
                || claimLease.isZero() || recoveryInterval == null
                || recoveryInterval.isNegative() || recoveryInterval.isZero()) {
            throw new IllegalArgumentException("rubrics runtime limits must be positive");
        }
        if (automation.event.samplePermille < 0 || automation.event.samplePermille > 1000
                || automation.scheduled.interval == null
                || automation.scheduled.interval.isNegative()
                || automation.scheduled.interval.isZero()) {
            throw new IllegalArgumentException("rubrics automation limits are invalid");
        }
        if (automation.event.enabled
                && (automation.event.templateId.isBlank()
                || automation.event.templateVersion.isBlank())) {
            throw new IllegalArgumentException(
                    "enabled Rubrics event binding requires a fixed template identity");
        }
        if (automation.scheduled.enabled
                && (automation.scheduled.templateId.isBlank()
                || automation.scheduled.templateVersion.isBlank()
                || automation.scheduled.datasetId.isBlank()
                || automation.scheduled.datasetVersion.isBlank())) {
            throw new IllegalArgumentException(
                    "enabled Rubrics schedule requires fixed template and dataset identities");
        }
    }

    public static class TemplateScan {
        private String classpathPrefix = "rubrics/templates/";

        private String userHomeDir = "";

        public String getClasspathPrefix() {
            return classpathPrefix;
        }

        public void setClasspathPrefix(String value) {
            classpathPrefix = value;
        }

        public String getUserHomeDir() {
            return userHomeDir;
        }

        public void setUserHomeDir(String value) {
            userHomeDir = value;
        }
    }

    public static class Automation {
        private EventBinding event = new EventBinding();

        private ScheduledBinding scheduled = new ScheduledBinding();

        public EventBinding getEvent() {
            return event;
        }

        public void setEvent(EventBinding value) {
            event = value;
        }

        public ScheduledBinding getScheduled() {
            return scheduled;
        }

        public void setScheduled(ScheduledBinding value) {
            scheduled = value;
        }
    }

    public static class EventBinding {
        private boolean enabled;

        private String templateId = "";

        private String templateVersion = "";

        private int samplePermille = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String value) {
            templateId = value;
        }

        public String getTemplateVersion() {
            return templateVersion;
        }

        public void setTemplateVersion(String value) {
            templateVersion = value;
        }

        public int getSamplePermille() {
            return samplePermille;
        }

        public void setSamplePermille(int value) {
            samplePermille = value;
        }
    }

    public static class ScheduledBinding {
        private boolean enabled;

        private Duration interval = Duration.ofHours(24);

        private String templateId = "";

        private String templateVersion = "";

        private String datasetId = "";

        private String datasetVersion = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration value) {
            interval = value;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String value) {
            templateId = value;
        }

        public String getTemplateVersion() {
            return templateVersion;
        }

        public void setTemplateVersion(String value) {
            templateVersion = value;
        }

        public String getDatasetId() {
            return datasetId;
        }

        public void setDatasetId(String value) {
            datasetId = value;
        }

        public String getDatasetVersion() {
            return datasetVersion;
        }

        public void setDatasetVersion(String value) {
            datasetVersion = value;
        }
    }
}
