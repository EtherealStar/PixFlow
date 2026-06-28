package com.pixflow.module.commerce;

import com.pixflow.module.commerce.importer.ImportOptions;
import com.pixflow.module.commerce.importer.ImportReport;
import com.pixflow.module.commerce.query.CommerceQuery;
import com.pixflow.module.commerce.query.CommerceQueryResult;
import com.pixflow.module.commerce.source.ApiImportRequest;
import com.pixflow.module.commerce.source.ImportJobStatusView;
import java.io.InputStream;

public interface CommerceService {
    ImportReport importLocal(InputStream input, String filename, ImportOptions options);

    ImportJobStatusView startApiImport(ApiImportRequest request);

    ImportJobStatusView getImportJob(long jobId);

    CommerceQueryResult query(CommerceQuery query);
}
