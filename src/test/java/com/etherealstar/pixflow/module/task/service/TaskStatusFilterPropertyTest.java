package com.etherealstar.pixflow.module.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.etherealstar.pixflow.common.error.BusinessException;
import com.etherealstar.pixflow.common.error.ErrorCode;
import com.etherealstar.pixflow.common.web.PageResponse;
import com.etherealstar.pixflow.module.conversation.mapper.ConversationMapper;
import com.etherealstar.pixflow.module.dag.DagJsonCodec;
import com.etherealstar.pixflow.module.dag.engine.DagExecutionEngine;
import com.etherealstar.pixflow.module.dag.validator.DagValidator;
import com.etherealstar.pixflow.module.file.mapper.AssetImageMapper;
import com.etherealstar.pixflow.module.file.mapper.AssetPackageMapper;
import com.etherealstar.pixflow.module.task.dto.TaskListItem;
import com.etherealstar.pixflow.module.task.entity.ProcessTask;
import com.etherealstar.pixflow.module.task.mapper.ProcessResultMapper;
import com.etherealstar.pixflow.module.task.mapper.ProcessTaskMapper;
import java.util.Collections;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 任务状态筛选属性测试（任务 14.7）。
 *
 * <p>Feature: pixflow, Property 34: 任务列表的状态筛选——对任意非法 status（不属于 {0,1,2,3}）应以
 * {@link ErrorCode#INVALID_TASK_STATUS} 拒绝且不查询；对任意合法 status（0/1/2/3）或 {@code null}（不筛选）
 * 应正常放行，且当 status 非空时查询条件须精确按该 status 过滤。
 * Validates: Requirements 12.2, 12.3
 */
class TaskStatusFilterPropertyTest {

    /** 持有一套 TaskService 及其被 mock 的依赖，便于每次属性迭代独立构造。 */
    private static final class Fixture {
        final ProcessTaskMapper taskMapper = mock(ProcessTaskMapper.class);
        final TaskService service;

        Fixture() {
            ConversationMapper conversationMapper = mock(ConversationMapper.class);
            AssetPackageMapper packageMapper = mock(AssetPackageMapper.class);
            AssetImageMapper imageMapper = mock(AssetImageMapper.class);
            ProcessResultMapper resultMapper = mock(ProcessResultMapper.class);
            DagValidator dagValidator = mock(DagValidator.class);
            DagJsonCodec dagJsonCodec = mock(DagJsonCodec.class);
            DagExecutionEngine executionEngine = mock(DagExecutionEngine.class);
            this.service = new TaskService(conversationMapper, packageMapper, imageMapper,
                    taskMapper, resultMapper, dagValidator, dagJsonCodec, executionEngine);
        }

        @SuppressWarnings("unchecked")
        void stubEmptyPage() {
            Page<ProcessTask> empty = new Page<>(1, 20);
            empty.setRecords(Collections.emptyList());
            empty.setTotal(0);
            when(taskMapper.selectPage(any(), any(QueryWrapper.class))).thenReturn(empty);
        }
    }

    @Provide
    Arbitrary<Integer> illegalStatuses() {
        return Arbitraries.integers().filter(s -> s < 0 || s > 3);
    }

    @Provide
    Arbitrary<Integer> legalStatuses() {
        return Arbitraries.of(0, 1, 2, 3);
    }

    @Property(tries = 200)
    void illegalStatusIsRejectedWithoutQuery(@ForAll("illegalStatuses") int status) {
        Fixture f = new Fixture();

        assertThatThrownBy(() -> f.service.listTasks(1L, 20L, status))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TASK_STATUS);

        // 非法 status 在分页之后、查询之前即被拒绝，不应触达 mapper
        verify(f.taskMapper, org.mockito.Mockito.never())
                .selectPage(any(), any(QueryWrapper.class));
    }

    @Property(tries = 200)
    @SuppressWarnings("unchecked")
    void legalStatusFiltersByExactlyThatStatus(@ForAll("legalStatuses") Integer status) {
        Fixture f = new Fixture();
        f.stubEmptyPage();

        PageResponse<TaskListItem> response = f.service.listTasks(1L, 20L, status);
        assertThat(response).isNotNull();

        ArgumentCaptor<QueryWrapper<ProcessTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(f.taskMapper).selectPage(any(), captor.capture());
        QueryWrapper<ProcessTask> wrapper = captor.getValue();
        // getParamNameValuePairs 为惰性填充，需先生成 SQL 片段
        String segment = wrapper.getSqlSegment();
        // 查询条件中应携带且仅携带该 status 作为过滤值
        assertThat(segment).contains("status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullStatusDoesNotFilter() {
        Fixture f = new Fixture();
        f.stubEmptyPage();

        f.service.listTasks(1L, 20L, null);

        ArgumentCaptor<QueryWrapper<ProcessTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(f.taskMapper).selectPage(any(), captor.capture());
        QueryWrapper<ProcessTask> wrapper = captor.getValue();
        // 未指定 status：生成的 SQL 片段不应包含 status 过滤
        assertThat(wrapper.getSqlSegment()).doesNotContain("status");
        assertThat(wrapper.getParamNameValuePairs()).isEmpty();
    }
}
