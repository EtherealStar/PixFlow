package com.pixflow.module.rubrics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.rubrics")
public class RubricsProperties {
    private TemplateScan templateScan = new TemplateScan();

    public TemplateScan getTemplateScan() {
        return templateScan;
    }

    public void setTemplateScan(TemplateScan templateScan) {
        this.templateScan = templateScan;
    }

    public static class TemplateScan {
        private String classpathPrefix = "rubrics/templates/";
        private String userHomeDir = "";

        public String getClasspathPrefix() { return classpathPrefix; }
        public void setClasspathPrefix(String value) { classpathPrefix = value; }
        public String getUserHomeDir() { return userHomeDir; }
        public void setUserHomeDir(String value) { userHomeDir = value; }
    }
}
