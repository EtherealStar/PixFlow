package com.pixflow.module.rubrics.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.harness.eval.api.TraceQuery;
import com.pixflow.harness.eval.model.TurnStatus;
import com.pixflow.harness.eval.model.TurnTraceRecord;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.subject.TaskDecisionSubject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class DefaultTraceEvidenceProviderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static TaskDecisionSubject decision(String conversationId) {
        return new TaskDecisionSubject(
                "7@rev", 7, "rev", "IMAGE_PROCESS", conversationId, 3L,
                "{\"nodes\":[]}", "{\"nodes\":[]}", "1.0", Instant.EPOCH, "snapshot");
    }

    private static TurnTraceRecord turn(int turnNo, String traceId, String toolCalls) {
        return new TurnTraceRecord(
                "c1", turnNo, traceId, 1, TurnStatus.COMMITTED, null, null, toolCalls,
                null, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void projectsToolCallTurnsAsTraceSpansInTurnOrder() {
        TraceQuery query = mock(TraceQuery.class);
        // 故意乱序，验证按 turnNo 排序后赋予稳定 ID。
        when(query.listByConversation(ArgumentMatchers.eq("c1"), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(
                        turn(3, "t3", trace("submit_image_plan")),
                        turn(1, "t1", trace("submit_image_plan"))), 2L, 1L, 50L));

        DefaultTraceEvidenceProvider provider = provider(query);
        List<EvidenceEntry> entries = provider.trace(decision("c1"));

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).id()).isEqualTo("T1");
        assertThat(entries.get(0).type()).isEqualTo(EvidenceType.TRACE_SPAN);
        assertThat(entries.get(0).sourceRef()).isEqualTo("trace:c1:1");
        assertThat(entries.get(0).metadata()).containsEntry("traceId", "t1");
        assertThat(entries.get(1).id()).isEqualTo("T2");
        assertThat(entries.get(1).sourceRef()).isEqualTo("trace:c1:3");
    }

    @Test
    void skipsTurnsWithoutToolCalls() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.eq("c1"), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(
                        turn(1, "t1", "[]"),
                        turn(2, "t2", "   "),
                        turn(3, "t3", trace("submit_image_plan"))), 3L, 1L, 50L));

        List<EvidenceEntry> entries = provider(query).trace(decision("c1"));

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("T1");
            assertThat(entry.sourceRef()).isEqualTo("trace:c1:3");
        });
    }

    @Test
    void returnsEmptyWhenConversationHasNoTrace() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.any(), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(), 0L, 1L, 50L));

        assertThat(provider(query).trace(decision("c1"))).isEmpty();
    }

    @Test
    void returnsEmptyWhenSubjectHasNoConversationId() {
        TraceQuery query = mock(TraceQuery.class);

        // 没有 conversation 引用时按 best-effort 返回空，不查询 Eval。
        assertThat(provider(query).trace(decision(null))).isEmpty();
        assertThat(provider(query).trace(decision(""))).isEmpty();
    }

    @Test
    void swallowsTraceReadFailuresAsEmpty() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.any(), ArgumentMatchers.any(Pagination.class)))
                .thenThrow(new IllegalStateException("eval unavailable"));

        // trace 读取失败绝不升级为 Subject 失败，也不伪造 span。
        assertThat(provider(query).trace(decision("c1"))).isEmpty();
    }

    @Test
    void truncatesOversizedSpanAndStaysStable() {
        String large = trace("submit_image_plan") + " ".repeat(9_000); // 远超 8 KiB
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.eq("c1"), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(turn(1, "t1", large)), 1L, 1L, 50L));

        List<EvidenceEntry> first = provider(query).trace(decision("c1"));
        List<EvidenceEntry> second = provider(query).trace(decision("c1"));

        assertThat(first).hasSize(1);
        // 截断标记与重放稳定性：相同 trace 产生相同 content hash。
        assertThat(first.get(0).metadata()).containsEntry("truncated", true);
        assertThat(first.get(0).contentHash()).isEqualTo(second.get(0).contentHash());
    }

    @Test
    void emittedSpansAreVisibleViaTraceSpanView() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.eq("c1"), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(turn(1, "t1", trace("submit_image_plan"))), 1L, 1L, 50L));

        EvidencePack pack = new TextEvidencePackBuilder(CLOCK, provider(query))
                .build(decision("c1"));

        assertThat(pack.view(Set.of(EvidenceType.TRACE_SPAN))).hasSize(1);
        assertThat(pack.view(Set.of(EvidenceType.DAG_SNAPSHOT))).hasSize(1);
        assertThat(pack.view(Set.of(EvidenceType.PROPOSAL))).hasSize(1);
        // 全量条目 = 1 个 Proposal + 1 个 DAG + 1 个 TRACE_SPAN。
        assertThat(pack.entries()).hasSize(3);
    }

    @Test
    void onlyUsesTraceForTheConfirmedDecisionRevision() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.eq("c1"), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(
                        turn(1, "old", "[{\"name\":\"submit_image_plan\","
                                + "\"result\":{\"payloadHash\":\"old-revision\",\"error\":false}}]"),
                        turn(2, "current", trace("submit_image_plan"))),
                        2L, 1L, 50L));

        List<EvidenceEntry> entries = provider(query).trace(decision("c1"));

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.sourceRef()).isEqualTo("trace:c1:2");
            assertThat(entry.metadata()).containsEntry("traceId", "current");
        });
    }

    @Test
    void ignoresPayloadHashOutsideSuccessfulProposalMetadata() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation(ArgumentMatchers.eq("c1"), ArgumentMatchers.any(Pagination.class)))
                .thenReturn(PageResponse.of(List.of(
                        turn(1, "input", "[{\"name\":\"submit_image_plan\","
                                + "\"input\":{\"payloadHash\":\"rev\"},"
                                + "\"result\":{\"error\":true}}]"),
                        turn(2, "nested", "[{\"name\":\"other_tool\","
                                + "\"result\":{\"nested\":{\"payloadHash\":\"rev\"}}}]")),
                        2L, 1L, 50L));

        assertThat(provider(query).trace(decision("c1"))).isEmpty();
    }

    @Test
    void readsTheLatestBoundedTraceWindowForLongConversations() {
        TraceQuery query = mock(TraceQuery.class);
        when(query.listByConversation("c1", new Pagination(1, 1)))
                .thenReturn(PageResponse.of(List.of(
                        turn(1, "old", "[{\"result\":{\"payloadHash\":\"old-revision\"}}]")),
                        120L, 1L, 1L));
        List<TurnTraceRecord> previousPage = IntStream.rangeClosed(51, 100)
                .mapToObj(turnNo -> turn(
                        turnNo,
                        "t" + turnNo,
                        turnNo == 70 || turnNo == 71
                                ? trace("submit_image_plan")
                                : "[{\"result\":{\"payloadHash\":\"old-revision\"}}]"))
                .toList();
        when(query.listByConversation("c1", new Pagination(2, 50)))
                .thenReturn(PageResponse.of(previousPage, 120L, 2L, 50L));
        when(query.listByConversation("c1", new Pagination(3, 50)))
                .thenReturn(PageResponse.of(IntStream.rangeClosed(101, 120)
                        .mapToObj(turnNo -> turn(
                                turnNo,
                                "t" + turnNo,
                                "[{\"result\":{\"payloadHash\":\"old-revision\"}}]"))
                        .toList(), 120L, 3L, 50L));

        List<EvidenceEntry> entries = provider(query).trace(decision("c1"));

        assertThat(entries).singleElement().satisfies(entry ->
                assertThat(entry.sourceRef()).isEqualTo("trace:c1:71"));
        verify(query).listByConversation("c1", new Pagination(1, 1));
        verify(query).listByConversation("c1", new Pagination(2, 50));
        verify(query).listByConversation("c1", new Pagination(3, 50));
    }

    private static DefaultTraceEvidenceProvider provider(TraceQuery query) {
        return new DefaultTraceEvidenceProvider(query, CLOCK, OBJECT_MAPPER);
    }

    private static String trace(String tool) {
        return "[{\"name\":\"" + tool
                + "\",\"result\":{\"payloadHash\":\"rev\",\"error\":false}}]";
    }
}
