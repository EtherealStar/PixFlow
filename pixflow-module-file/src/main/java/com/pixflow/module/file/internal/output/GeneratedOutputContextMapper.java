package com.pixflow.module.file.internal.output;

import com.pixflow.module.file.api.publication.GeneratedOutputContext;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import java.util.List;
import com.pixflow.module.file.output.OutputConversationView;
import com.pixflow.module.file.output.OutputTaskView;

@Mapper
public interface GeneratedOutputContextMapper {
    @Delete("delete from generated_output_context")
    int deleteAll();

    @Insert("""
            insert ignore into generated_output_context (
                task_id, conversation_id, conversation_title_snapshot, task_type,
                task_created_at, created_at, updated_at
            ) values (
                #{context.taskId}, #{context.conversationId},
                #{context.conversationTitleSnapshot}, #{context.taskType},
                #{context.taskCreatedAt}, #{now}, #{now}
            )
            """)
    int insertIfAbsent(@Param("context") GeneratedOutputContext context, @Param("now") Instant now);

    @Select("select * from generated_output_context where task_id = #{taskId}")
    GeneratedOutputContextRow find(@Param("taskId") String taskId);

    @Update("""
            update generated_output_context
            set task_finished_at = coalesce(task_finished_at, #{finishedAt}), updated_at = #{finishedAt}
            where task_id = #{taskId}
            """)
    int markFinished(@Param("taskId") String taskId, @Param("finishedAt") Instant finishedAt);

    @Select("""
            <script>
            select c.conversation_id as conversationId,
                   max(c.conversation_title_snapshot) as title,
                   count(i.id) as generatedImageCount,
                   max(i.created_at) as latestGeneratedAt
            from generated_output_context c
            join asset_image i on i.source_task_id = c.task_id
            where i.source_type = 'GENERATED' and i.publication_status = 'READY'
              and i.deletion_status is null
            <if test="query != null and query != ''">
              and c.conversation_title_snapshot like concat('%', #{query}, '%')
            </if>
            group by c.conversation_id
            <choose>
              <when test="sort == 'LATEST_OUTPUT_ASC'">order by latestGeneratedAt asc, c.conversation_id</when>
              <when test="sort == 'NAME_ASC'">order by title asc, c.conversation_id</when>
              <when test="sort == 'NAME_DESC'">order by title desc, c.conversation_id</when>
              <otherwise>order by latestGeneratedAt desc, c.conversation_id</otherwise>
            </choose>
            limit #{limit} offset #{offset}
            </script>
            """)
    List<OutputConversationView> listConversations(
            @Param("query") String query, @Param("sort") String sort,
            @Param("offset") long offset, @Param("limit") long limit);

    @Select("""
            <script>
            select count(distinct c.conversation_id)
            from generated_output_context c
            join asset_image i on i.source_task_id = c.task_id
            where i.source_type = 'GENERATED' and i.publication_status = 'READY'
              and i.deletion_status is null
            <if test="query != null and query != ''">
              and c.conversation_title_snapshot like concat('%', #{query}, '%')
            </if>
            </script>
            """)
    long countConversations(@Param("query") String query);

    @Select("""
            select cast(c.task_id as char) as taskId, c.task_type as taskType,
                   count(i.id) as generatedImageCount,
                   c.task_created_at as createdAt, c.task_finished_at as finishedAt
            from generated_output_context c
            join asset_image i on i.source_task_id = c.task_id
            where c.conversation_id = #{conversationId}
              and i.source_type = 'GENERATED' and i.publication_status = 'READY'
              and i.deletion_status is null
            group by c.task_id, c.task_type, c.task_created_at, c.task_finished_at
            order by c.task_created_at desc, c.task_id desc
            limit #{limit} offset #{offset}
            """)
    List<OutputTaskView> listTasks(
            @Param("conversationId") String conversationId,
            @Param("offset") long offset, @Param("limit") long limit);

    @Select("""
            select count(distinct c.task_id)
            from generated_output_context c
            join asset_image i on i.source_task_id = c.task_id
            where c.conversation_id = #{conversationId}
              and i.source_type = 'GENERATED' and i.publication_status = 'READY'
              and i.deletion_status is null
            """)
    long countTasks(@Param("conversationId") String conversationId);

    @Select("""
            select i.id as imageId, i.package_id as packageId, i.sku_id as skuId,
                   coalesce(i.display_name, concat('output-', i.id)) as displayName,
                   c.conversation_id as conversationId, c.task_id as taskId,
                   i.source_image_id as sourceImageId, i.width, i.height,
                   i.byte_size as sizeBytes, i.content_type as contentType,
                   i.stable_bucket as stableBucket, i.minio_key as minioKey,
                   i.created_at as createdAt
            from asset_image i
            join generated_output_context c on c.task_id = i.source_task_id
            where i.source_task_id = #{taskId} and i.source_type = 'GENERATED'
              and i.publication_status = 'READY' and i.deletion_status is null
            order by i.created_at desc, i.id desc
            limit #{limit} offset #{offset}
            """)
    List<GeneratedOutputImageRow> listImages(
            @Param("taskId") long taskId,
            @Param("offset") long offset, @Param("limit") long limit);

    @Select("""
            select count(*) from asset_image
            where source_task_id = #{taskId} and source_type = 'GENERATED'
              and publication_status = 'READY' and deletion_status is null
            """)
    long countImages(@Param("taskId") long taskId);
}
