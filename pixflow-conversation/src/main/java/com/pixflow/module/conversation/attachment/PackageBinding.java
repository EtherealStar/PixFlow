package com.pixflow.module.conversation.attachment;

public record PackageBinding(String packageId) {
    public boolean present() {
        return packageId != null && !packageId.isBlank();
    }
}
