package com.pixflow.harness.hooks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.hooks")
public class HookProperties {
    private boolean failFastOnCallbackError = false;

    public boolean isFailFastOnCallbackError() {
        return failFastOnCallbackError;
    }

    public void setFailFastOnCallbackError(boolean failFastOnCallbackError) {
        this.failFastOnCallbackError = failFastOnCallbackError;
    }
}
