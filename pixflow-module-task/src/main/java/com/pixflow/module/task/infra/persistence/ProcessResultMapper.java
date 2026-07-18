package com.pixflow.module.task.infra.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pixflow.module.task.domain.model.ProcessResult;
import com.pixflow.module.task.domain.model.ResultStatus;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProcessResultMapper extends BaseMapper<ProcessResult> {
  @Select("select * from process_result where task_id = #{taskId} order by id asc")
  List<ProcessResult> findByTaskId(@Param("taskId") long taskId);

  @Select(
      """
            select * from process_result
            where task_id = #{taskId} and status = #{status}
            order by id asc
            """)
  List<ProcessResult> findByTaskIdAndStatus(
      @Param("taskId") long taskId, @Param("status") ResultStatus status);

  @Select(
      """
            select * from process_result
            where task_id = #{taskId} and status = #{status} and deleted_at is null
              and (#{status} <> 'SUCCESS'
                   or publication_status in ('PUBLISHED', 'NOT_APPLICABLE'))
            order by id asc
            """)
  List<ProcessResult> findVisibleByTaskIdAndStatus(
      @Param("taskId") long taskId, @Param("status") ResultStatus status);

  @Select(
      """
            select * from process_result
            where task_id = #{taskId} and unit_key = #{unitKey}
            limit 1
            """)
  ProcessResult selectByUnit(@Param("taskId") long taskId, @Param("unitKey") String unitKey);

  @Update(
      """
            update process_result r
            set status = #{row.status}, run_epoch = #{epoch},
                output_minio_key = #{row.outputMinioKey}, generated_copy = #{row.generatedCopy},
                candidate_bucket = #{row.candidateBucket},
                candidate_content_type = #{row.candidateContentType},
                candidate_extension = #{row.candidateExtension},
                producer_kind = #{row.producerKind}, producer_provider = #{row.producerProvider},
                producer_model = #{row.producerModel}, producer_tool = #{row.producerTool},
                producer_node_id = #{row.producerNodeId},
                publication_status = #{row.publicationStatus},
                bytes_out = #{row.bytesOut}, failure_code = #{row.failureCode},
                failure_category = #{row.failureCategory}, failure_recovery = #{row.failureRecovery},
                failed_node_id = #{row.failedNodeId}, failed_tool = #{row.failedTool},
                failure_details_json = #{row.failureDetailsJson}, error_msg = #{row.errorMsg},
                attempt_count = #{row.attemptCount}, started_at = #{row.startedAt}, finished_at = #{row.finishedAt}
            where r.task_id = #{taskId} and r.unit_key = #{unitKey}
              and r.status <> 'SUCCESS' and r.run_epoch < #{epoch}
              and exists (
                select 1 from process_task t
                where t.id = r.task_id and t.status = 'RUNNING' and t.run_epoch = #{epoch}
              )
            """)
  int commitForEpoch(
      @Param("taskId") long taskId,
      @Param("unitKey") String unitKey,
      @Param("epoch") long epoch,
      @Param("row") ProcessResult row);

  @Select(
      """
            select * from process_result
            where task_id = #{taskId} and status = 'SUCCESS' and publication_status = 'PENDING'
            order by id asc
            """)
  List<ProcessResult> findPublicationBacklog(@Param("taskId") long taskId);

  @Update(
      """
            update process_result r
            set publication_status = 'PUBLISHED', generated_image_id = #{imageId},
                published_reference_key = #{referenceKey}, published_at = #{publishedAt},
                publication_last_error = null
            where r.id = #{resultId} and r.task_id = #{taskId}
              and r.status = 'SUCCESS' and r.publication_status = 'PENDING'
              and r.run_epoch = #{resultRunEpoch}
              and exists (
                select 1 from process_task t
                where t.id = r.task_id and t.status = 'RUNNING' and t.run_epoch = #{ownerEpoch}
              )
            """)
  int bindPublished(
      @Param("taskId") long taskId,
      @Param("resultId") long resultId,
      @Param("resultRunEpoch") long resultRunEpoch,
      @Param("ownerEpoch") long ownerEpoch,
      @Param("imageId") long imageId,
      @Param("referenceKey") String referenceKey,
      @Param("publishedAt") Instant publishedAt);

  @Update(
      """
            update process_result r
            set publication_attempt_count = publication_attempt_count + 1,
                publication_last_error = #{safeError}
            where r.id = #{resultId} and r.task_id = #{taskId}
              and r.status = 'SUCCESS' and r.publication_status = 'PENDING'
              and exists (
                select 1 from process_task t
                where t.id = r.task_id and t.status = 'RUNNING' and t.run_epoch = #{ownerEpoch}
              )
            """)
  int recordPublicationFailure(
      @Param("taskId") long taskId,
      @Param("resultId") long resultId,
      @Param("ownerEpoch") long ownerEpoch,
      @Param("safeError") String safeError);

  @Select(
      """
            select count(*) from process_result
            where task_id = #{taskId} and status = 'SUCCESS' and publication_status = 'PENDING'
            """)
  int countPendingPublication(@Param("taskId") long taskId);

  @Select("select count(*) from process_result where task_id = #{taskId} and status = #{status}")
  int countByStatus(@Param("taskId") long taskId, @Param("status") ResultStatus status);

  @Select(
      """
            select * from process_result
            where task_id = #{taskId} and deleted_at is null
              and (status <> 'SUCCESS'
                   or publication_status in ('PUBLISHED', 'NOT_APPLICABLE'))
            order by id asc
            limit #{size} offset #{offset}
            """)
  List<ProcessResult> pageVisibleByTaskId(
      @Param("taskId") long taskId, @Param("offset") long offset, @Param("size") int size);

  @Select(
      """
            select count(*) from process_result
            where task_id = #{taskId} and deleted_at is null
              and (status <> 'SUCCESS'
                   or publication_status in ('PUBLISHED', 'NOT_APPLICABLE'))
            """)
  long countVisibleByTaskId(@Param("taskId") long taskId);

  @Select(
      """
            select r.* from process_result r
            join process_task t on t.id = r.task_id
            where t.conversation_id = #{conversationId}
              and r.status = 'SUCCESS'
              and r.publication_status = 'PUBLISHED'
              and r.generated_image_id is not null
              and r.deleted_at is null
            order by r.created_at desc, r.id desc
            limit #{size} offset #{offset}
            """)
  List<ProcessResult> pageConversationImages(
      @Param("conversationId") String conversationId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
            select count(*) from process_result r
            join process_task t on t.id = r.task_id
            where t.conversation_id = #{conversationId}
              and r.status = 'SUCCESS'
              and r.publication_status = 'PUBLISHED'
              and r.generated_image_id is not null
              and r.deleted_at is null
            """)
  long countConversationImages(@Param("conversationId") String conversationId);

  @Update(
      """
            update process_result
            set deleted_at = #{deletedAt}
            where id = #{resultId} and task_id = #{taskId} and deleted_at is null
            """)
  int softDelete(
      @Param("taskId") long taskId,
      @Param("resultId") long resultId,
      @Param("deletedAt") Instant deletedAt);

  @Update(
      """
            update process_result
            set display_name = #{displayName}
            where id = #{resultId} and task_id = #{taskId} and deleted_at is null
            """)
  int updateDisplayName(
      @Param("taskId") long taskId,
      @Param("resultId") long resultId,
      @Param("displayName") String displayName);
}
