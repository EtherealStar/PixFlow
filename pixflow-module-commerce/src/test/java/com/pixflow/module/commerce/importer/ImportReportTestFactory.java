package com.pixflow.module.commerce.importer;

public final class ImportReportTestFactory {
    private ImportReportTestFactory() {
    }

    public static ImportReport report(int succeeded, int failed) {
        ImportReport report = new ImportReport();
        for (int i = 0; i < succeeded; i++) {
            report.countTotal();
            report.countSucceeded();
        }
        for (int i = 0; i < failed; i++) {
            report.countTotal();
            report.addFailure(succeeded + i + 1, "bad row");
        }
        return report;
    }
}
