package org.javaup.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.javaup.ai.entity.RagIngestionTask;

@Mapper
public interface RagIngestionTaskMapper extends BaseMapper<RagIngestionTask> {

    @Select("SELECT * FROM d_ai_rag_ingestion_task WHERE task_id = #{taskId} AND status = 1")
    RagIngestionTask selectByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM d_ai_rag_ingestion_task WHERE task_status = #{taskStatus} AND status = 1 ORDER BY id DESC LIMIT 10")
    java.util.List<RagIngestionTask> selectRecentByStatus(@Param("taskStatus") String taskStatus);
}
