package com.pixflow.module.file.copydoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.module.file.config.FileProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvCopyDocParserTest {

    @Test
    void parsesChineseHeaders() throws Exception {
        CsvCopyDocParser parser = new CsvCopyDocParser(new FileProperties());
        String csv = "商品编号,标题,关键词,描述\nSKU1,标题1,词1,描述1\n";

        List<ParsedCopyRow> rows = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).skuId()).isEqualTo("SKU1");
        assertThat(rows.get(0).productName()).isEqualTo("标题1");
        assertThat(rows.get(0).keywords()).isEqualTo("词1");
        assertThat(rows.get(0).description()).isEqualTo("描述1");
    }
}
