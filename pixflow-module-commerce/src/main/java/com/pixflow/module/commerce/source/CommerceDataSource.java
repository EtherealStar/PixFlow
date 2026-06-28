package com.pixflow.module.commerce.source;

import com.pixflow.module.commerce.store.CommerceData;
import java.util.List;

public interface CommerceDataSource {
    List<CommerceData> pull(PullSpec spec);

    boolean supportsLive();
}
