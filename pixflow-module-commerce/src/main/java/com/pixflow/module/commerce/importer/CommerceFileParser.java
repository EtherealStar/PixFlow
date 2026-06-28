package com.pixflow.module.commerce.importer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface CommerceFileParser {
    boolean supports(String filename, String contentType);

    List<RawCommerceRow> parse(InputStream input, ColumnMapping mapping) throws IOException;
}
