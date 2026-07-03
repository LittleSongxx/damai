package org.javaup.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.javaup.ai.entity.AiRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiRunMapper extends BaseMapper<AiRun> {

    @Select("""
            select * from d_ai_run
            where run_id = #{runId}
              and status = 1
            limit 1
            for update
            """)
    AiRun selectByRunIdForUpdate(@Param("runId") String runId);
}
