package com.pixflow.module.rubrics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.rubrics")
public class RubricsProperties {
    private boolean enabled = true;
    private TemplateScan templateScan = new TemplateScan();
    private Scheduler scheduler = new Scheduler();
    private EventTrigger eventTrigger = new EventTrigger();
    private Runner runner = new Runner();
    private Baseline baseline = new Baseline();
    private Feedback feedback = new Feedback();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TemplateScan getTemplateScan() {
        return templateScan;
    }

    public void setTemplateScan(TemplateScan templateScan) {
        this.templateScan = templateScan;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public EventTrigger getEventTrigger() {
        return eventTrigger;
    }

    public void setEventTrigger(EventTrigger eventTrigger) {
        this.eventTrigger = eventTrigger;
    }

    public Runner getRunner() {
        return runner;
    }

    public void setRunner(Runner runner) {
        this.runner = runner;
    }

    public Baseline getBaseline() {
        return baseline;
    }

    public void setBaseline(Baseline baseline) {
        this.baseline = baseline;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public void setFeedback(Feedback feedback) {
        this.feedback = feedback;
    }

    public static class TemplateScan {
        private String classpathPrefix = "rubrics/templates/";
        private String userHomeDir = "${PIXFLOW_HOME:/tmp/pixflow}/rubrics/";

        public String getClasspathPrefix() {
            return classpathPrefix;
        }

        public void setClasspathPrefix(String classpathPrefix) {
            this.classpathPrefix = classpathPrefix;
        }

        public String getUserHomeDir() {
            return userHomeDir;
        }

        public void setUserHomeDir(String userHomeDir) {
            this.userHomeDir = userHomeDir;
        }
    }

    public static class Scheduler {
        private String dailyBatchCron = "0 0 3 * * ?";
        private boolean dailyBatchEnabled = true;

        public String getDailyBatchCron() {
            return dailyBatchCron;
        }

        public void setDailyBatchCron(String dailyBatchCron) {
            this.dailyBatchCron = dailyBatchCron;
        }

        public boolean isDailyBatchEnabled() {
            return dailyBatchEnabled;
        }

        public void setDailyBatchEnabled(boolean dailyBatchEnabled) {
            this.dailyBatchEnabled = dailyBatchEnabled;
        }
    }

    public static class EventTrigger {
        private boolean enabled = true;
        private int queueSize = 16;
        private int workerThreads = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }
    }

    public static class Runner {
        private int maxConcurrentItems = 8;
        private int itemRetry = 1;
        private Duration judgeTimeout = Duration.ofSeconds(60);
        private double judgeTemperature = 0.0;
        private int visionMaxEdgePx = 1024;

        public int getMaxConcurrentItems() {
            return maxConcurrentItems;
        }

        public void setMaxConcurrentItems(int maxConcurrentItems) {
            this.maxConcurrentItems = maxConcurrentItems;
        }

        public int getItemRetry() {
            return itemRetry;
        }

        public void setItemRetry(int itemRetry) {
            this.itemRetry = itemRetry;
        }

        public Duration getJudgeTimeout() {
            return judgeTimeout;
        }

        public void setJudgeTimeout(Duration judgeTimeout) {
            this.judgeTimeout = judgeTimeout;
        }

        public double getJudgeTemperature() {
            return judgeTemperature;
        }

        public void setJudgeTemperature(double judgeTemperature) {
            this.judgeTemperature = judgeTemperature;
        }

        public int getVisionMaxEdgePx() {
            return visionMaxEdgePx;
        }

        public void setVisionMaxEdgePx(int visionMaxEdgePx) {
            this.visionMaxEdgePx = visionMaxEdgePx;
        }
    }

    public static class Baseline {
        private double regressionDimensionThreshold = -5.0;
        private double regressionOverallThreshold = -10.0;

        public double getRegressionDimensionThreshold() {
            return regressionDimensionThreshold;
        }

        public void setRegressionDimensionThreshold(double regressionDimensionThreshold) {
            this.regressionDimensionThreshold = regressionDimensionThreshold;
        }

        public double getRegressionOverallThreshold() {
            return regressionOverallThreshold;
        }

        public void setRegressionOverallThreshold(double regressionOverallThreshold) {
            this.regressionOverallThreshold = regressionOverallThreshold;
        }
    }

    public static class Feedback {
        private int negativePatternMinRuns = 5;
        private double negativePatternScoreCap = 60.0;
        private int positivePatternMinRuns = 10;
        private double positivePatternScoreFloor = 85.0;

        public int getNegativePatternMinRuns() {
            return negativePatternMinRuns;
        }

        public void setNegativePatternMinRuns(int negativePatternMinRuns) {
            this.negativePatternMinRuns = negativePatternMinRuns;
        }

        public double getNegativePatternScoreCap() {
            return negativePatternScoreCap;
        }

        public void setNegativePatternScoreCap(double negativePatternScoreCap) {
            this.negativePatternScoreCap = negativePatternScoreCap;
        }

        public int getPositivePatternMinRuns() {
            return positivePatternMinRuns;
        }

        public void setPositivePatternMinRuns(int positivePatternMinRuns) {
            this.positivePatternMinRuns = positivePatternMinRuns;
        }

        public double getPositivePatternScoreFloor() {
            return positivePatternScoreFloor;
        }

        public void setPositivePatternScoreFloor(double positivePatternScoreFloor) {
            this.positivePatternScoreFloor = positivePatternScoreFloor;
        }
    }
}
