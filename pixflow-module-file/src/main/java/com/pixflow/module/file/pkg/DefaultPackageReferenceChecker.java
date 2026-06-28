package com.pixflow.module.file.pkg;

public class DefaultPackageReferenceChecker implements PackageReferenceChecker {
    @Override
    public boolean isReferenced(long packageId) {
        return false;
    }
}
