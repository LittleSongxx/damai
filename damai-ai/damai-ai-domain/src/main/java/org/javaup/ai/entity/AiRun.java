package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_run")
public class AiRun extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    private String conversationId;

    private Long userId;

    private String routeType;

    private String skillId;

    private String skillVersion;

    private String skillSnapshotJson;

    private String runStatus;

    private String currentStage;

    private String clientContextJson;

    private String userMessage;

    private String responseSummary;

    private String errorMessage;

    private Integer eventSeq;

    private Date completedAt;

    /**
     * 可恢复执行状态（JSON）—— 遵循 LangGraph "durable execution" 设计。
     *
     * <p>每个关键节点完成后写入中间产物快照：
     * <ul>
     *   <li>ROUTED: 路由决策 + Skill 选择</li>
     *   <li>SKILL_STARTED: 已加载的 Skill 描述符</li>
     *   <li>RETRIEVAL_COMPLETED: 检索结果快照（Knowledge Skill）</li>
     *   <li>SQL_GENERATED: 已生成且校验通过的 SQL（Ops Skill）</li>
     *   <li>ACTION_PREVIEWED: 购票预览快照（Business Skill）</li>
     * </ul>
     *
     * <p>失败时从最近 checkpoint 恢复，无需从头重跑全流程。
     */
    private String resumableStateJson;

    /**
     * 是否允许从断点恢复。为 true 时表示这是一个续跑 Run。
     */
    private Integer resumed;
}
