package com.pixflow.module.rubrics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.rubrics")
public class RubricsProperties {
    private TemplateScan templateScan = new TemplateScan();
    private Automation automation = new Automation();
    private int runnerConcurrency = 2;

    public TemplateScan getTemplateScan() {
        return templateScan;
    }

    public void setTemplateScan(TemplateScan templateScan) {
        this.templateScan = templateScan;
    }
    public Automation getAutomation(){return automation;} public void setAutomation(Automation value){automation=value;}
    public int getRunnerConcurrency(){return runnerConcurrency;} public void setRunnerConcurrency(int value){runnerConcurrency=value;}

    public static class TemplateScan {
        private String classpathPrefix = "rubrics/templates/";
        private String userHomeDir = "";

        public String getClasspathPrefix() { return classpathPrefix; }
        public void setClasspathPrefix(String value) { classpathPrefix = value; }
        public String getUserHomeDir() { return userHomeDir; }
        public void setUserHomeDir(String value) { userHomeDir = value; }
    }
    public static class Automation {
        private boolean eventEnabled; private boolean schedulerEnabled; private String templateId=""; private String templateVersion=""; private String datasetId=""; private String datasetVersion="";
        public boolean isEventEnabled(){return eventEnabled;} public void setEventEnabled(boolean v){eventEnabled=v;} public boolean isSchedulerEnabled(){return schedulerEnabled;} public void setSchedulerEnabled(boolean v){schedulerEnabled=v;}
        public String getTemplateId(){return templateId;} public void setTemplateId(String v){templateId=v;} public String getTemplateVersion(){return templateVersion;} public void setTemplateVersion(String v){templateVersion=v;}
        public String getDatasetId(){return datasetId;} public void setDatasetId(String v){datasetId=v;} public String getDatasetVersion(){return datasetVersion;} public void setDatasetVersion(String v){datasetVersion=v;}
    }
}
