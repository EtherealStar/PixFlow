package com.pixflow.harness.session.history;

import java.util.List;

/** Conversation 只读历史出口；message 表写入仍只经过 TranscriptPort。 */
public interface TranscriptHistoryReader {
    long count(String conversationId);

    List<TranscriptMessageView> page(String conversationId, long offset, long limit);
}
