package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_episodic_memory")
public class AiEpisodicMemory extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String eventType;
    private String entityJson;
    private String summary;
    private Double weight;
    private String runId;
}
