package com.pixflow.module.commerce;

import com.pixflow.module.commerce.importer.CommerceImportService;
import com.pixflow.module.commerce.importer.ImportOptions;
import com.pixflow.module.commerce.importer.ImportReport;
import com.pixflow.module.commerce.importjob.CommerceImportJobService;
import com.pixflow.module.commerce.query.CommerceQuery;
import com.pixflow.module.commerce.query.CommerceQueryResult;
import com.pixflow.module.commerce.query.CommerceQueryService;
import com.pixflow.module.commerce.source.ApiImportRequest;
import com.pixflow.module.commerce.source.ImportJobStatusView;
import java.io.InputStream;

public class DefaultCommerceService implements CommerceService {
    private final CommerceImportService importService;
    private final CommerceImportJobService jobService;
    private final CommerceQueryService queryService;

    public DefaultCommerceService(
            CommerceImportService importService,
            CommerceImportJobService jobService,
            CommerceQueryService queryService) {
        this.importService = importService;
        this.jobService = jobService;
        this.queryService = queryService;
    }

    @Override
    public ImportReport importLocal(InputStream input, String filename, ImportOptions options) {
        return importService.importLocal(input, filename, options);
    }

    @Override
    public ImportJobStatusView startApiImport(ApiImportRequest request) {
        return jobService.start(request);
    }

    @Override
    public ImportJobStatusView getImportJob(long jobId) {
        return jobService.get(jobId);
    }

    @Override
    public CommerceQueryResult query(CommerceQuery query) {
        return queryService.query(query);
    }
}
