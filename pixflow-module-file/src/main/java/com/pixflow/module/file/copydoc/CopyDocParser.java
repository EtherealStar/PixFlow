package com.pixflow.module.file.copydoc;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface CopyDocParser {
    boolean supports(String fileName);

    List<ParsedCopyRow> parse(InputStream inputStream) throws IOException;
}
