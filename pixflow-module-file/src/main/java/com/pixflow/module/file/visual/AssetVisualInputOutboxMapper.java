package com.pixflow.module.file.visual;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AssetVisualInputOutboxMapper extends BaseMapper<AssetVisualInputOutbox> {
    @Select("""
            select * from asset_visual_input_outbox
             where next_attempt_at <= #{now}
             order by id limit #{limit}
            """)
    List<AssetVisualInputOutbox> findDue(@Param("now") Instant now, @Param("limit") int limit);

    @Delete("delete from asset_visual_input_outbox where id = #{id}")
    int confirm(@Param("id") long id);

    @Update("""
            update asset_visual_input_outbox
               set attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt},
                   last_error = #{lastError}
             where id = #{id}
            """)
    int defer(@Param("id") long id, @Param("nextAttemptAt") Instant nextAttemptAt,
              @Param("lastError") String lastError);
}
