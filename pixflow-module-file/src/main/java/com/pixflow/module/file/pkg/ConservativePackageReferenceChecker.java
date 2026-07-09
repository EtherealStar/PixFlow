package com.pixflow.module.file.pkg;

/**
 * 没有权威引用检查器时采用保守语义：未知视为已引用，只允许软删。
 */
public class ConservativePackageReferenceChecker implements PackageReferenceChecker {
    @Override
    public boolean isReferenced(long packageId) {
        return true;
    }
}
