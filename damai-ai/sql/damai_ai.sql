-- 建数据库
create database if not exists damai_ai character set utf8mb4;
use damai_ai;


CREATE TABLE IF NOT EXISTS `SPRING_AI_CHAT_MEMORY` (
  `conversation_id` varchar(191) NOT NULL,
  `content` text NOT NULL,
  `type` varchar(10) NOT NULL,
  `timestamp` timestamp NOT NULL,
  CONSTRAINT `TYPE_CHECK` CHECK (`type` IN ('USER','ASSISTANT','SYSTEM','TOOL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Spring AI聊天记忆表';

ALTER TABLE `SPRING_AI_CHAT_MEMORY` MODIFY COLUMN `conversation_id` varchar(191) NOT NULL;

CREATE TABLE IF NOT EXISTS `d_ai_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `type` int NOT NULL COMMENT '会话类型，详见ChatType枚举',
  `chat_id` varchar(225) NOT NULL COMMENT '会话id',
  `title` varchar(512) DEFAULT NULL COMMENT '标题',
  `latest_run_id` varchar(128) DEFAULT NULL COMMENT '最近一次工作流ID',
  `workflow_status` varchar(32) DEFAULT NULL COMMENT '工作流状态',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_chat` (`user_id`,`type`,`chat_id`),
  KEY `idx_chat_id` (`chat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会话目录表';

CREATE TABLE IF NOT EXISTS `d_ai_workflow_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `run_id` varchar(128) NOT NULL COMMENT '工作流ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `chat_id` varchar(225) NOT NULL COMMENT '会话ID',
  `type` int NOT NULL COMMENT '会话类型',
  `request_type` varchar(64) DEFAULT NULL COMMENT '业务请求类型',
  `workflow_status` varchar(32) NOT NULL COMMENT '工作流状态',
  `current_step` varchar(64) DEFAULT NULL COMMENT '当前步骤',
  `latest_approval_id` varchar(128) DEFAULT NULL COMMENT '最近一次审批ID',
  `request_summary` text DEFAULT NULL COMMENT '请求摘要',
  `response_summary` text DEFAULT NULL COMMENT '响应摘要',
  `context_json` longtext DEFAULT NULL COMMENT '工作流上下文',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_id` (`run_id`),
  KEY `idx_user_chat` (`user_id`,`chat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作流运行表';

CREATE TABLE IF NOT EXISTS `d_ai_workflow_step` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `run_id` varchar(128) NOT NULL COMMENT '工作流ID',
  `step_order` int NOT NULL COMMENT '步骤序号',
  `step_key` varchar(64) NOT NULL COMMENT '步骤标识',
  `step_status` varchar(32) NOT NULL COMMENT '步骤状态',
  `input_json` longtext DEFAULT NULL COMMENT '输入快照',
  `output_json` longtext DEFAULT NULL COMMENT '输出快照',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_run_order` (`run_id`,`step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工作流步骤表';

CREATE TABLE IF NOT EXISTS `d_ai_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `approval_id` varchar(128) NOT NULL COMMENT '审批ID',
  `run_id` varchar(128) NOT NULL COMMENT '工作流ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `chat_id` varchar(225) NOT NULL COMMENT '会话ID',
  `approval_type` varchar(64) DEFAULT NULL COMMENT '审批类型',
  `approval_status` varchar(32) NOT NULL COMMENT '审批状态',
  `preview_json` longtext DEFAULT NULL COMMENT '审批预览数据',
  `result_json` longtext DEFAULT NULL COMMENT '审批结果数据',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间',
  `approved_at` datetime DEFAULT NULL COMMENT '通过时间',
  `rejected_at` datetime DEFAULT NULL COMMENT '拒绝时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_approval_id` (`approval_id`),
  KEY `idx_run_status` (`run_id`,`approval_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI审批表';

CREATE TABLE IF NOT EXISTS `d_ai_tool_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `run_id` varchar(128) DEFAULT NULL COMMENT '工作流ID',
  `chat_id` varchar(225) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `tool_name` varchar(128) NOT NULL COMMENT '工具名',
  `tool_type` varchar(64) DEFAULT NULL COMMENT '工具类型',
  `request_summary` text DEFAULT NULL COMMENT '工具入参摘要',
  `response_summary` text DEFAULT NULL COMMENT '工具出参摘要',
  `success` tinyint(1) DEFAULT 1 COMMENT '是否成功',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_run_tool` (`run_id`,`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI工具调用审计表';

CREATE TABLE IF NOT EXISTS `d_ai_retrieval_trace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `trace_id` varchar(128) NOT NULL COMMENT '检索追踪ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '工作流ID',
  `chat_id` varchar(225) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `parent_trace_id` varchar(128) DEFAULT NULL COMMENT '父追踪ID',
  `trace_type` varchar(32) DEFAULT NULL COMMENT '追踪类型(snapshot/stage/route)',
  `step_key` varchar(64) DEFAULT NULL COMMENT '阶段标识',
  `original_query` text DEFAULT NULL COMMENT '原始Query',
  `rewritten_query` text DEFAULT NULL COMMENT '改写Query',
  `dense_hits_json` longtext DEFAULT NULL COMMENT '向量召回结果',
  `sparse_hits_json` longtext DEFAULT NULL COMMENT '稀疏召回结果',
  `fused_hits_json` longtext DEFAULT NULL COMMENT '融合结果',
  `final_hits_json` longtext DEFAULT NULL COMMENT '最终命中结果',
  `metadata_json` longtext DEFAULT NULL COMMENT '附加元数据',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trace_id` (`trace_id`),
  KEY `idx_run_id` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI检索追踪表';

CREATE TABLE IF NOT EXISTS `d_ai_trace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `trace_id` varchar(64) NOT NULL COMMENT '追踪ID',
  `conversation_id` varchar(225) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '工作流ID',
  `step_key` varchar(64) DEFAULT NULL COMMENT '工作流步骤',
  `tool_name` varchar(128) DEFAULT NULL COMMENT '工具名称',
  `retrieval_trace_id` varchar(128) DEFAULT NULL COMMENT '检索追踪ID',
  `approval_id` varchar(128) DEFAULT NULL COMMENT '审批ID',
  `model_name` varchar(64) DEFAULT NULL COMMENT '模型名称',
  `request_type` varchar(32) DEFAULT NULL COMMENT '请求类型',
  `prompt_tokens` int DEFAULT NULL COMMENT '输入Token数',
  `completion_tokens` int DEFAULT NULL COMMENT '输出Token数',
  `total_tokens` int DEFAULT NULL COMMENT '总Token数',
  `latency_ms` bigint DEFAULT NULL COMMENT '响应延迟（毫秒）',
  `estimated_cost` decimal(10,6) DEFAULT NULL COMMENT '预估费用（元）',
  `user_input` text DEFAULT NULL COMMENT '用户输入（截断）',
  `ai_output` text DEFAULT NULL COMMENT 'AI输出（截断）',
  `success` tinyint(1) DEFAULT 1 COMMENT '是否成功',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `metadata` json DEFAULT NULL COMMENT '附加元数据',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用追踪表';

CREATE TABLE IF NOT EXISTS `d_ai_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `conversation_id` varchar(128) NOT NULL COMMENT '统一会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `title` varchar(512) DEFAULT NULL COMMENT '会话标题',
  `route_type` varchar(32) DEFAULT NULL COMMENT '最近一次路由类型',
  `latest_run_id` varchar(128) DEFAULT NULL COMMENT '最近一次Run ID',
  `latest_status` varchar(32) DEFAULT NULL COMMENT '最近一次Run状态',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_user` (`conversation_id`,`user_id`),
  KEY `idx_conversation_user` (`user_id`,`edit_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手会话表';

CREATE TABLE IF NOT EXISTS `d_ai_conversation_memory_summary` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `conversation_id` varchar(128) NOT NULL COMMENT '统一会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `covered_run_id` varchar(128) DEFAULT NULL COMMENT '摘要覆盖到的Run ID',
  `summary` longtext DEFAULT NULL COMMENT '会话压缩摘要',
  `memory_json` longtext DEFAULT NULL COMMENT '结构化会话记忆',
  `summary_version` int DEFAULT 1 COMMENT '摘要结构版本',
  `compression_count` int DEFAULT 1 COMMENT '压缩次数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_memory_conversation` (`conversation_id`,`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手会话摘要记忆表';

CREATE TABLE IF NOT EXISTS `d_ai_user_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `profile_summary` longtext DEFAULT NULL COMMENT '用户偏好画像摘要',
  `preference_tags_json` longtext DEFAULT NULL COMMENT '偏好标签JSON',
  `latest_covered_run_id` varchar(128) DEFAULT NULL COMMENT '最近覆盖Run ID',
  `version` int DEFAULT 1 COMMENT '画像版本',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_user_profile_user` (`user_id`),
  KEY `idx_ai_user_profile_edit` (`user_id`,`edit_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手用户偏好画像表';

CREATE TABLE IF NOT EXISTS `d_ai_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `run_id` varchar(128) NOT NULL COMMENT '统一Run ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '统一会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `route_type` varchar(32) DEFAULT NULL COMMENT '路由类型',
  `skill_id` varchar(128) DEFAULT NULL COMMENT 'Skill ID',
  `skill_version` varchar(32) DEFAULT NULL COMMENT 'Skill版本',
  `skill_snapshot_json` longtext DEFAULT NULL COMMENT 'Skill执行快照',
  `run_status` varchar(32) NOT NULL COMMENT 'Run状态',
  `current_stage` varchar(64) DEFAULT NULL COMMENT '当前阶段',
  `client_context_json` longtext DEFAULT NULL COMMENT '客户端上下文',
  `user_message` longtext DEFAULT NULL COMMENT '用户输入',
  `response_summary` longtext DEFAULT NULL COMMENT '响应摘要',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `event_seq` int DEFAULT 0 COMMENT '当前事件序号',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `resumable_state_json` longtext DEFAULT NULL COMMENT '可恢复执行状态JSON',
  `resumed` int DEFAULT 0 COMMENT '恢复执行次数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_run_id` (`run_id`),
  KEY `idx_ai_run_conversation` (`conversation_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手运行表';

CREATE TABLE IF NOT EXISTS `d_ai_run_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `event_id` varchar(128) NOT NULL COMMENT '事件ID',
  `run_id` varchar(128) NOT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `event_order` int NOT NULL COMMENT '事件顺序',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型',
  `payload_json` longtext DEFAULT NULL COMMENT '事件载荷',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_event_id` (`event_id`),
  KEY `idx_ai_run_event` (`run_id`,`event_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手事件表';

CREATE TABLE IF NOT EXISTS `d_ai_run_graph_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `node_id` varchar(128) NOT NULL COMMENT '图节点ID',
  `run_id` varchar(128) NOT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `node_type` varchar(64) NOT NULL COMMENT '节点类型',
  `node_label` varchar(128) DEFAULT NULL COMMENT '节点展示名称',
  `graph_order` int DEFAULT 0 COMMENT '图节点排序',
  `node_status` varchar(32) NOT NULL COMMENT '节点状态',
  `latest_event_id` varchar(128) DEFAULT NULL COMMENT '最近事件ID',
  `latest_event_type` varchar(64) DEFAULT NULL COMMENT '最近事件类型',
  `latest_event_order` int DEFAULT NULL COMMENT '最近事件顺序',
  `checkpoint_id` varchar(192) DEFAULT NULL COMMENT '检查点ID',
  `checkpoint_stage` varchar(64) DEFAULT NULL COMMENT '检查点阶段',
  `state_json` longtext DEFAULT NULL COMMENT '节点状态JSON',
  `input_summary` text DEFAULT NULL COMMENT '输入摘要',
  `output_summary` text DEFAULT NULL COMMENT '输出摘要',
  `risk_level` varchar(32) DEFAULT 'LOW' COMMENT '风险等级',
  `trace_ref` varchar(128) DEFAULT NULL COMMENT '链路引用',
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_run_graph_node` (`run_id`,`node_id`),
  KEY `idx_ai_run_graph_status` (`run_id`,`node_status`,`graph_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手运行图节点表';

CREATE TABLE IF NOT EXISTS `d_ai_action` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `action_id` varchar(128) NOT NULL COMMENT '动作ID',
  `run_id` varchar(128) NOT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `action_type` varchar(64) NOT NULL COMMENT '动作类型',
  `action_status` varchar(32) NOT NULL COMMENT '动作状态',
  `preview_json` longtext DEFAULT NULL COMMENT '动作预览',
  `preview_summary` text DEFAULT NULL COMMENT '动作摘要',
  `snapshot_hash` varchar(128) DEFAULT NULL COMMENT '审批快照摘要Hash',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '下单幂等键',
  `result_json` longtext DEFAULT NULL COMMENT '动作结果',
  `order_number` varchar(64) DEFAULT NULL COMMENT '创建出的订单号',
  `failure_code` varchar(64) DEFAULT NULL COMMENT '失败代码',
  `failure_message` text DEFAULT NULL COMMENT '失败信息',
  `version` int DEFAULT 0 COMMENT '乐观锁版本',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间',
  `approved_at` datetime DEFAULT NULL COMMENT '批准时间',
  `processing_started_at` datetime DEFAULT NULL COMMENT '开始处理时间',
  `completed_at` datetime DEFAULT NULL COMMENT '处理完成时间',
  `rejected_at` datetime DEFAULT NULL COMMENT '拒绝时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_action_id` (`action_id`),
  KEY `idx_ai_action_run` (`run_id`,`action_status`),
  KEY `idx_ai_action_idempotency` (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手动作表';

CREATE TABLE IF NOT EXISTS `d_ai_guardrail_hit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `hit_id` varchar(128) NOT NULL COMMENT '命中ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `stage` varchar(64) NOT NULL COMMENT '命中阶段',
  `guardrail_action` varchar(32) NOT NULL COMMENT '处置动作',
  `rule_names` text DEFAULT NULL COMMENT '命中规则',
  `content_preview` text DEFAULT NULL COMMENT '脱敏后的内容摘要',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '更新时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_guardrail_hit_id` (`hit_id`),
  KEY `idx_ai_guardrail_run_stage` (`run_id`,`stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手Guardrail命中审计表';

CREATE TABLE IF NOT EXISTS `d_ai_tool_call` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `call_id` varchar(128) NOT NULL COMMENT '工具调用ID',
  `run_id` varchar(128) NOT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `skill_id` varchar(128) DEFAULT NULL COMMENT 'Skill ID',
  `tool_name` varchar(128) NOT NULL COMMENT '工具名称',
  `tool_type` varchar(64) DEFAULT NULL COMMENT '工具类型',
  `input_json` longtext DEFAULT NULL COMMENT '入参',
  `output_json` longtext DEFAULT NULL COMMENT '出参',
  `duration_ms` bigint DEFAULT NULL COMMENT '耗时',
  `success` tinyint(1) DEFAULT 1 COMMENT '是否成功',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_tool_call_id` (`call_id`),
  KEY `idx_ai_tool_run` (`run_id`,`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手工具调用表';

CREATE TABLE IF NOT EXISTS `d_ai_retrieval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `retrieval_id` varchar(128) NOT NULL COMMENT '检索ID',
  `run_id` varchar(128) NOT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `original_query` text DEFAULT NULL COMMENT '原始问题',
  `normalized_query` text DEFAULT NULL COMMENT '规范化问题',
  `rewritten_query` text DEFAULT NULL COMMENT '改写问题',
  `dense_hits_json` longtext DEFAULT NULL COMMENT 'dense命中',
  `sparse_hits_json` longtext DEFAULT NULL COMMENT 'sparse命中',
  `fused_hits_json` longtext DEFAULT NULL COMMENT '融合命中',
  `final_hits_json` longtext DEFAULT NULL COMMENT '最终命中',
  `confidence_score` decimal(10,4) DEFAULT NULL COMMENT '置信度分数',
  `confidence_level` varchar(16) DEFAULT NULL COMMENT '置信度等级',
  `corrective_action` varchar(64) DEFAULT NULL COMMENT '纠错动作',
  `retrieval_plan_json` longtext DEFAULT NULL COMMENT '检索计划',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_retrieval_id` (`retrieval_id`),
  KEY `idx_ai_retrieval_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手检索表';

CREATE TABLE IF NOT EXISTS `d_ai_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `skill_id` varchar(128) NOT NULL COMMENT 'Skill ID',
  `name` varchar(128) NOT NULL COMMENT 'Skill名称',
  `description` varchar(1024) DEFAULT NULL COMMENT 'Skill描述',
  `version` varchar(32) DEFAULT '1.0.0' COMMENT '版本',
  `goal` varchar(1024) DEFAULT NULL COMMENT 'Skill目标',
  `instructions` longtext DEFAULT NULL COMMENT 'Skill执行指令',
  `route_type` varchar(32) NOT NULL COMMENT '所属路由',
  `category` varchar(64) DEFAULT NULL COMMENT '分类',
  `trigger_keywords_json` longtext DEFAULT NULL COMMENT '触发关键词JSON',
  `tool_allowlist_json` longtext DEFAULT NULL COMMENT '工具白名单JSON',
  `examples_json` longtext DEFAULT NULL COMMENT '示例JSON',
  `eval_cases_json` longtext DEFAULT NULL COMMENT '评测用例JSON',
  `input_schema_json` longtext DEFAULT NULL COMMENT '输入Schema',
  `output_schema_json` longtext DEFAULT NULL COMMENT '输出Schema',
  `risk_level` varchar(32) DEFAULT 'LOW' COMMENT '风险等级',
  `requires_admin` tinyint(1) DEFAULT 0 COMMENT '是否要求管理员',
  `requires_approval` tinyint(1) DEFAULT 0 COMMENT '是否要求审批',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `executor_type` varchar(64) DEFAULT 'java' COMMENT '执行器类型',
  `frontend_selectable` tinyint(1) DEFAULT 1 COMMENT '是否允许前端直选',
  `model_selectable` tinyint(1) DEFAULT 1 COMMENT '是否允许模型自动选择',
  `primary_skill` tinyint(1) DEFAULT 0 COMMENT '是否为路由默认Skill',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_id` (`skill_id`),
  KEY `idx_ai_skill_route` (`route_type`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Skill定义表';

CREATE TABLE IF NOT EXISTS `d_ai_skill_resource` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `resource_id` varchar(128) NOT NULL COMMENT '资源ID',
  `skill_id` varchar(128) NOT NULL COMMENT 'Skill ID',
  `resource_type` varchar(64) NOT NULL COMMENT '资源类型',
  `title` varchar(256) DEFAULT NULL COMMENT '资源标题',
  `content` longtext DEFAULT NULL COMMENT '资源内容',
  `metadata_json` longtext DEFAULT NULL COMMENT '资源元数据',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_resource_id` (`resource_id`),
  KEY `idx_ai_skill_resource_skill` (`skill_id`,`resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Skill资源表';

CREATE TABLE IF NOT EXISTS `d_ai_skill_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `case_id` varchar(128) NOT NULL COMMENT '评测用例ID',
  `skill_id` varchar(128) NOT NULL COMMENT 'Skill ID',
  `question` longtext NOT NULL COMMENT '评测问题',
  `expected_output_json` longtext DEFAULT NULL COMMENT '期望输出',
  `tags_json` longtext DEFAULT NULL COMMENT '标签JSON',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_eval_case_id` (`case_id`),
  KEY `idx_ai_skill_eval_case_skill` (`skill_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Skill评测用例表';

CREATE TABLE IF NOT EXISTS `d_ai_skill_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评测运行ID',
  `skill_id` varchar(128) NOT NULL COMMENT 'Skill ID',
  `user_id` bigint DEFAULT NULL COMMENT '发起用户ID',
  `run_status` varchar(32) NOT NULL COMMENT '评测状态',
  `case_count` int DEFAULT 0 COMMENT '用例数',
  `passed_count` int DEFAULT 0 COMMENT '通过数',
  `result_json` longtext DEFAULT NULL COMMENT '结果JSON',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_eval_run_id` (`eval_run_id`),
  KEY `idx_ai_skill_eval_run_skill` (`skill_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Skill评测运行表';

CREATE TABLE IF NOT EXISTS `d_ai_skill_change_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `change_id` varchar(128) NOT NULL COMMENT '变更ID',
  `skill_id` varchar(128) NOT NULL COMMENT 'Skill ID',
  `operator_user_id` bigint DEFAULT NULL COMMENT '操作用户ID',
  `change_type` varchar(32) NOT NULL COMMENT '变更类型',
  `before_json` longtext DEFAULT NULL COMMENT '变更前',
  `after_json` longtext DEFAULT NULL COMMENT '变更后',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_change_id` (`change_id`),
  KEY `idx_ai_skill_change_skill` (`skill_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Skill变更审计表';

-- ============================================================
-- 全量优化新增表
-- ============================================================

CREATE TABLE IF NOT EXISTS `d_ai_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `feedback_id` varchar(128) NOT NULL COMMENT '反馈ID',
  `run_id` varchar(128) NOT NULL COMMENT '关联Run ID',
  `conversation_id` varchar(191) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `rating` varchar(16) NOT NULL COMMENT '评分: up/down',
  `comment` text DEFAULT NULL COMMENT '文字反馈',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_feedback_id` (`feedback_id`),
  KEY `idx_ai_feedback_run` (`run_id`),
  KEY `idx_ai_feedback_user` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI用户反馈表';

CREATE TABLE IF NOT EXISTS `d_ai_sentiment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `record_id` varchar(128) NOT NULL COMMENT '情绪记录ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '关联Run ID',
  `conversation_id` varchar(191) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `user_message` varchar(500) DEFAULT NULL COMMENT '用户消息',
  `sentiment` varchar(32) DEFAULT 'NEUTRAL' COMMENT '情绪:POSITIVE/NEUTRAL/NEGATIVE',
  `intensity` double DEFAULT 0 COMMENT '情绪强度',
  `is_urgent` tinyint(1) DEFAULT 0 COMMENT '是否紧急',
  `emotion_tags_json` text DEFAULT NULL COMMENT '情绪标签JSON',
  `escalation_triggered` tinyint(1) DEFAULT 0 COMMENT '是否触发升级',
  `escalation_reason` varchar(1000) DEFAULT NULL COMMENT '升级原因',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_sentiment_record_id` (`record_id`),
  KEY `idx_ai_sentiment_user` (`user_id`,`create_time`),
  KEY `idx_ai_sentiment_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI客服情绪记录表';

CREATE TABLE IF NOT EXISTS `d_ai_escalation_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `ticket_id` varchar(128) NOT NULL COMMENT '工单ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '关联Run ID',
  `conversation_id` varchar(191) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `escalation_type` varchar(64) DEFAULT NULL COMMENT '升级类型',
  `priority` varchar(32) DEFAULT 'LOW' COMMENT '优先级:LOW/MEDIUM/HIGH/CRITICAL',
  `ticket_status` varchar(32) DEFAULT 'OPEN' COMMENT '状态:OPEN/ASSIGNED/IN_PROGRESS/RESOLVED',
  `ai_diagnosis` text DEFAULT NULL COMMENT 'AI诊断',
  `dialogue_summary` mediumtext DEFAULT NULL COMMENT '对话摘要',
  `context_json` mediumtext DEFAULT NULL COMMENT '客服上下文JSON',
  `sentiment` varchar(32) DEFAULT NULL COMMENT '情绪标签',
  `intent_code` varchar(64) DEFAULT NULL COMMENT '客服意图',
  `suggested_reply` text DEFAULT NULL COMMENT '建议人工回复',
  `assigned_to` bigint DEFAULT NULL COMMENT '分配坐席',
  `resolution` text DEFAULT NULL COMMENT '处理结果',
  `resolved_at` datetime DEFAULT NULL COMMENT '解决时间',
  `resolved_by` bigint DEFAULT NULL COMMENT '解决人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_escalation_ticket_id` (`ticket_id`),
  KEY `idx_ai_escalation_run` (`run_id`,`ticket_status`),
  KEY `idx_ai_escalation_user` (`user_id`,`create_time`),
  KEY `idx_ai_escalation_customer` (`priority`,`sentiment`,`intent_code`,`ticket_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI客服升级工单表';

CREATE TABLE IF NOT EXISTS `d_ai_customer_hot_question` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `question_id` varchar(128) NOT NULL COMMENT '高频问题ID',
  `scene` varchar(64) NOT NULL DEFAULT 'customer_service' COMMENT '场景',
  `display_text` varchar(255) NOT NULL COMMENT '前端展示文本',
  `query_text` varchar(500) DEFAULT NULL COMMENT '实际提问文本',
  `intent_code` varchar(64) DEFAULT NULL COMMENT '客服意图',
  `route_hint` varchar(64) DEFAULT NULL COMMENT '路由提示',
  `answer_mode` varchar(32) DEFAULT 'RUN_ASSISTANT' COMMENT 'CACHED_ANSWER/RUN_ASSISTANT/LINK',
  `cached_answer_json` mediumtext DEFAULT NULL COMMENT '缓存答案卡片JSON',
  `source_refs_json` text DEFAULT NULL COMMENT '引用来源JSON',
  `tags_json` text DEFAULT NULL COMMENT '标签JSON',
  `priority` int DEFAULT 100 COMMENT '优先级',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `cache_version` int DEFAULT 1 COMMENT '缓存版本',
  `expire_at` datetime DEFAULT NULL COMMENT '过期时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_customer_hot_question_id` (`question_id`),
  KEY `idx_ai_customer_hot_scene` (`scene`,`enabled`,`priority`),
  KEY `idx_ai_customer_hot_intent` (`intent_code`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI客服高频问题与秒答缓存表';

CREATE TABLE IF NOT EXISTS `d_ai_customer_service_metric_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `metric_id` varchar(128) NOT NULL COMMENT '指标事件ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '关联Run ID',
  `conversation_id` varchar(191) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `metric_type` varchar(64) NOT NULL COMMENT '指标类型',
  `metric_value` double DEFAULT 0 COMMENT '指标值',
  `latency_ms` bigint DEFAULT NULL COMMENT '耗时ms',
  `dimensions_json` text DEFAULT NULL COMMENT '维度JSON',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_customer_metric_id` (`metric_id`),
  KEY `idx_ai_customer_metric_type` (`metric_type`,`create_time`),
  KEY `idx_ai_customer_metric_run` (`run_id`),
  KEY `idx_ai_customer_metric_user` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI客服体验指标事件表';

CREATE TABLE IF NOT EXISTS `d_ai_prompt_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `prompt_key` varchar(128) NOT NULL COMMENT 'Prompt标识',
  `version` int NOT NULL DEFAULT 1 COMMENT '版本号',
  `template` longtext NOT NULL COMMENT 'Prompt模板内容',
  `description` varchar(512) DEFAULT NULL COMMENT '描述',
  `active` tinyint(1) DEFAULT '0' COMMENT '是否活跃',
  `traffic_percent` int DEFAULT '0' COMMENT '灰度流量比例',
  `rollout_status` varchar(32) DEFAULT 'DRAFT' COMMENT '发布状态:DRAFT/STABLE/GRADUAL/SUPERSEDED/ROLLED_BACK',
  `baseline_eval_run_id` varchar(128) DEFAULT NULL COMMENT '发布关联baseline评测Run',
  `release_note` varchar(1000) DEFAULT NULL COMMENT '发布说明',
  `released_by` bigint DEFAULT NULL COMMENT '发布人',
  `released_at` datetime DEFAULT NULL COMMENT '发布时间',
  `rollback_from_version` varchar(32) DEFAULT NULL COMMENT '回滚前版本',
  `rollback_reason` varchar(1000) DEFAULT NULL COMMENT '回滚原因',
  `created_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_prompt_key_version` (`prompt_key`,`version`),
  KEY `idx_ai_prompt_active` (`prompt_key`,`active`,`rollout_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Prompt版本管理表';

CREATE TABLE IF NOT EXISTS `d_ai_prompt_release_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `release_id` varchar(128) NOT NULL COMMENT '发布记录ID',
  `prompt_key` varchar(128) NOT NULL COMMENT 'Prompt标识',
  `from_version` int DEFAULT NULL COMMENT '来源版本',
  `to_version` int NOT NULL COMMENT '目标版本',
  `action_type` varchar(32) NOT NULL COMMENT '动作:PUBLISH/ROLLBACK',
  `rollout_status` varchar(32) DEFAULT NULL COMMENT '发布状态',
  `traffic_percent` int DEFAULT NULL COMMENT '流量比例',
  `baseline_eval_run_id` varchar(128) DEFAULT NULL COMMENT 'baseline评测Run',
  `release_note` varchar(1000) DEFAULT NULL COMMENT '发布说明',
  `release_evidence_json` json DEFAULT NULL COMMENT '发布质量门禁与回滚证据',
  `rollback_reason` varchar(1000) DEFAULT NULL COMMENT '回滚原因',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_prompt_release_id` (`release_id`),
  KEY `idx_ai_prompt_release_key` (`prompt_key`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Prompt发布回滚记录表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text NOT NULL COMMENT '问题',
  `expected_answer` text DEFAULT NULL COMMENT '期望答案',
  `expected_chunks` text DEFAULT NULL COMMENT '期望命中的chunkId列表(JSON)',
  `category` varchar(64) DEFAULT NULL COMMENT '分类',
  `difficulty` varchar(16) DEFAULT NULL COMMENT '难度: easy/medium/hard',
  `dataset_id` varchar(128) DEFAULT 'default-golden' COMMENT '数据集ID',
  `dataset_version` varchar(64) DEFAULT 'v1' COMMENT '数据集版本',
  `case_type` varchar(64) DEFAULT 'single_hop' COMMENT '用例类型: single_hop/multi_hop/negative/ambiguous/adversarial',
  `tags` varchar(1000) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `required_facts` text DEFAULT NULL COMMENT '必须覆盖事实点(JSON)',
  `forbidden_facts` text DEFAULT NULL COMMENT '禁止编造事实点(JSON)',
  `expected_citations` text DEFAULT NULL COMMENT '期望引用chunkId列表(JSON)',
  `review_status` varchar(32) DEFAULT 'APPROVED' COMMENT '审核状态',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_case_id` (`case_id`),
  KEY `idx_ai_rag_eval_case_dataset` (`dataset_id`,`dataset_version`),
  KEY `idx_ai_rag_eval_case_type` (`case_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估用例表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_dataset` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `dataset_id` varchar(128) NOT NULL COMMENT '数据集ID',
  `dataset_name` varchar(255) DEFAULT NULL COMMENT '数据集名称',
  `dataset_version` varchar(64) NOT NULL COMMENT '数据集版本',
  `description` text DEFAULT NULL COMMENT '说明',
  `case_count` int DEFAULT 0 COMMENT '用例数量',
  `kb_version` varchar(128) DEFAULT NULL COMMENT '知识库版本',
  `chunk_version` varchar(128) DEFAULT NULL COMMENT '切块版本',
  `embedding_model` varchar(128) DEFAULT NULL COMMENT 'Embedding模型',
  `created_by` varchar(128) DEFAULT NULL COMMENT '创建人',
  `reviewed_by` varchar(128) DEFAULT NULL COMMENT '审核人',
  `review_status` varchar(32) DEFAULT 'DRAFT' COMMENT '审核状态',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_dataset` (`dataset_id`,`dataset_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评测数据集版本表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_retrieval_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `config_id` varchar(128) NOT NULL COMMENT '配置ID',
  `config_name` varchar(255) DEFAULT NULL COMMENT '配置名称',
  `dense_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用Dense召回',
  `sparse_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用Sparse召回',
  `hyde_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用HyDE',
  `query_rewrite_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用查询改写',
  `sub_question_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用子问题分解',
  `rerank_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用精排',
  `rerank_model` varchar(128) DEFAULT NULL COMMENT '精排模型',
  `embedding_model` varchar(128) DEFAULT NULL COMMENT 'Embedding模型',
  `top_k` int DEFAULT 5 COMMENT 'TopK',
  `candidate_k` int DEFAULT NULL COMMENT '候选集大小',
  `dense_weight` double DEFAULT NULL COMMENT 'Dense权重',
  `sparse_weight` double DEFAULT NULL COMMENT 'Sparse权重',
  `hyde_weight` double DEFAULT NULL COMMENT 'HyDE权重',
  `rrf_k` int DEFAULT NULL COMMENT 'RRF参数',
  `chunk_size` int DEFAULT NULL COMMENT '切块大小',
  `chunk_overlap` int DEFAULT NULL COMMENT '切块重叠',
  `parent_child_enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用父子块',
  `metadata_json` text DEFAULT NULL COMMENT '扩展配置JSON',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_retrieval_config` (`config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评测检索配置表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_judge_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `judge_config_id` varchar(128) NOT NULL COMMENT '评委配置ID',
  `config_name` varchar(255) DEFAULT NULL COMMENT '配置名称',
  `judge_model` varchar(128) DEFAULT NULL COMMENT '评估模型',
  `temperature` double DEFAULT 0.1 COMMENT '温度',
  `prompt_version` varchar(128) DEFAULT NULL COMMENT 'Prompt版本',
  `metric_name` varchar(128) DEFAULT NULL COMMENT '指标名称',
  `rubric` text DEFAULT NULL COMMENT '评分Rubric',
  `output_schema` text DEFAULT NULL COMMENT '输出Schema',
  `active` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_judge_config` (`judge_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评测LLM Judge配置表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评估运行ID',
  `total_cases` int DEFAULT 0 COMMENT '总用例数',
  `completed_cases` int DEFAULT 0 COMMENT '完成数',
  `avg_recall` double DEFAULT NULL COMMENT '平均Recall@5',
  `avg_precision` double DEFAULT NULL COMMENT '平均Precision@5',
  `avg_hit_rate` double DEFAULT NULL COMMENT '平均HitRate@5',
  `avg_mrr` double DEFAULT NULL COMMENT '平均MRR',
  `avg_ndcg` double DEFAULT NULL COMMENT '平均NDCG@5',
  `avg_faithfulness` double DEFAULT NULL COMMENT '平均Faithfulness',
  `avg_answer_relevancy` double DEFAULT NULL COMMENT '平均答案相关性',
  `avg_completeness` double DEFAULT NULL COMMENT '平均完整性',
  `avg_context_relevance` double DEFAULT NULL COMMENT '平均上下文相关性',
  `avg_ctx_precision` double DEFAULT NULL COMMENT '平均上下文精确度',
  `avg_ctx_recall` double DEFAULT NULL COMMENT '平均上下文召回率',
  `avg_answer_correctness` double DEFAULT NULL COMMENT '平均答案正确性',
  `dataset_id` varchar(128) DEFAULT NULL COMMENT '数据集ID',
  `dataset_version` varchar(64) DEFAULT NULL COMMENT '数据集版本',
  `retrieval_config_id` varchar(128) DEFAULT NULL COMMENT '检索配置ID',
  `judge_config_id` varchar(128) DEFAULT NULL COMMENT 'Judge配置ID',
  `baseline_run_id` varchar(128) DEFAULT NULL COMMENT '基线运行ID',
  `request_json` text DEFAULT NULL COMMENT '请求参数JSON',
  `report_json` mediumtext DEFAULT NULL COMMENT '评测报告JSON',
  `quality_gate_json` text DEFAULT NULL COMMENT '质量门禁JSON',
  `git_commit` varchar(64) DEFAULT NULL COMMENT '代码Commit',
  `model_version` varchar(128) DEFAULT NULL COMMENT '模型版本',
  `prompt_version` varchar(128) DEFAULT NULL COMMENT 'Prompt版本',
  `run_status` varchar(32) DEFAULT 'RUNNING' COMMENT '状态',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_run_id` (`eval_run_id`),
  KEY `idx_ai_rag_eval_run_dataset` (`dataset_id`,`dataset_version`),
  KEY `idx_ai_rag_eval_run_baseline` (`baseline_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估运行表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评估运行ID',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text DEFAULT NULL COMMENT '问题',
  `retrieved_chunks` text DEFAULT NULL COMMENT '检索到的chunkId列表(JSON)',
  `generated_answer` text DEFAULT NULL COMMENT '生成的答案',
  `dataset_id` varchar(128) DEFAULT NULL COMMENT '数据集ID',
  `dataset_version` varchar(64) DEFAULT NULL COMMENT '数据集版本',
  `retrieval_config_id` varchar(128) DEFAULT NULL COMMENT '检索配置ID',
  `judge_config_id` varchar(128) DEFAULT NULL COMMENT 'Judge配置ID',
  `case_type` varchar(64) DEFAULT NULL COMMENT '用例类型',
  `tags` varchar(1000) DEFAULT NULL COMMENT '标签',
  `recall_at_1` double DEFAULT NULL COMMENT 'Recall@1',
  `recall_at_3` double DEFAULT NULL COMMENT 'Recall@3',
  `recall_at_5` double DEFAULT NULL COMMENT 'Recall@5',
  `recall_at_10` double DEFAULT NULL COMMENT 'Recall@10',
  `precision_at_1` double DEFAULT NULL COMMENT 'Precision@1',
  `precision_at_3` double DEFAULT NULL COMMENT 'Precision@3',
  `precision_at_5` double DEFAULT NULL COMMENT 'Precision@5',
  `precision_at_10` double DEFAULT NULL COMMENT 'Precision@10',
  `hit_rate_at_1` double DEFAULT NULL COMMENT 'HitRate@1',
  `hit_rate_at_3` double DEFAULT NULL COMMENT 'HitRate@3',
  `hit_rate_at_5` double DEFAULT NULL COMMENT 'HitRate@5',
  `hit_rate_at_10` double DEFAULT NULL COMMENT 'HitRate@10',
  `mrr` double DEFAULT NULL COMMENT 'MRR',
  `ndcg_at_1` double DEFAULT NULL COMMENT 'NDCG@1',
  `ndcg_at_3` double DEFAULT NULL COMMENT 'NDCG@3',
  `ndcg_at_5` double DEFAULT NULL COMMENT 'NDCG@5',
  `ndcg_at_10` double DEFAULT NULL COMMENT 'NDCG@10',
  `faithfulness_score` double DEFAULT NULL COMMENT 'Faithfulness得分',
  `context_precision` double DEFAULT NULL COMMENT 'RAGAS上下文精度',
  `context_recall` double DEFAULT NULL COMMENT 'RAGAS上下文召回',
  `context_relevance` double DEFAULT NULL COMMENT 'RAGAS上下文相关性',
  `answer_relevancy_score` double DEFAULT NULL COMMENT 'RAGAS答案相关性',
  `answer_correctness_score` double DEFAULT NULL COMMENT 'RAGAS答案正确性',
  `unsupported_claim_rate` double DEFAULT NULL COMMENT '无证据声明占比',
  `supported_claim_count` int DEFAULT NULL COMMENT '有证据声明数',
  `unsupported_claim_count` int DEFAULT NULL COMMENT '无证据声明数',
  `required_fact_coverage` double DEFAULT NULL COMMENT '必须事实覆盖率',
  `citation_precision` double DEFAULT NULL COMMENT '引用精确率',
  `citation_recall` double DEFAULT NULL COMMENT '引用召回率',
  `citation_coverage` double DEFAULT NULL COMMENT '引用覆盖率',
  `refusal_correctness` double DEFAULT NULL COMMENT '拒答正确性',
  `safety_score` double DEFAULT NULL COMMENT '安全合规评分',
  `judge_relevance` double DEFAULT NULL COMMENT '结构化Judge相关性',
  `judge_coverage` double DEFAULT NULL COMMENT '结构化Judge覆盖度',
  `judge_contradiction` double DEFAULT NULL COMMENT '结构化Judge矛盾风险',
  `judge_citation_support` double DEFAULT NULL COMMENT '结构化Judge引用支撑',
  `judge_answerability` double DEFAULT NULL COMMENT '结构化Judge可回答性',
  `judge_refusal_reason` text DEFAULT NULL COMMENT '结构化Judge拒答原因',
  `judge_structured_output` mediumtext DEFAULT NULL COMMENT '结构化Judge输出',
  `input_tokens` int DEFAULT NULL COMMENT '输入Token',
  `output_tokens` int DEFAULT NULL COMMENT '输出Token',
  `total_tokens` int DEFAULT NULL COMMENT '总Token',
  `estimated_cost` double DEFAULT NULL COMMENT '预估成本',
  `retrieval_latency_ms` bigint DEFAULT NULL COMMENT '检索延迟ms',
  `rerank_latency_ms` bigint DEFAULT NULL COMMENT '精排延迟ms',
  `generation_latency_ms` bigint DEFAULT NULL COMMENT '生成延迟ms',
  `total_latency_ms` bigint DEFAULT NULL COMMENT '总延迟ms',
  `dense_hits_json` text DEFAULT NULL COMMENT 'Dense命中JSON',
  `sparse_hits_json` text DEFAULT NULL COMMENT 'Sparse命中JSON',
  `fused_hits_json` text DEFAULT NULL COMMENT '融合命中JSON',
  `final_hits_json` text DEFAULT NULL COMMENT '最终命中JSON',
  `retrieval_trace_id` varchar(128) DEFAULT NULL COMMENT '检索Trace ID',
  `retrieval_path` varchar(64) DEFAULT NULL COMMENT '检索路径',
  `judge_raw_output` mediumtext DEFAULT NULL COMMENT 'Judge原始输出',
  `failure_type` varchar(64) DEFAULT NULL COMMENT '失败类型',
  `eval_method` varchar(32) DEFAULT NULL COMMENT '评估方法: HEURISTIC/LLM_JUDGE/RAGAS_LLM_JUDGE',
  `latency_ms` bigint DEFAULT NULL COMMENT '延迟ms',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_rag_eval_result_run` (`eval_run_id`),
  KEY `idx_ai_rag_eval_result_case` (`case_id`),
  KEY `idx_ai_rag_eval_result_dataset` (`dataset_id`,`dataset_version`),
  KEY `idx_ai_rag_eval_result_config` (`retrieval_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估结果表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_online_trace` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `trace_id` varchar(128) NOT NULL COMMENT 'Trace ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '助手Run ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `question` text DEFAULT NULL COMMENT '用户问题',
  `rewritten_query` text DEFAULT NULL COMMENT '改写查询',
  `sub_questions_json` text DEFAULT NULL COMMENT '子问题JSON',
  `retrieved_chunks_json` text DEFAULT NULL COMMENT '召回Chunk JSON',
  `final_chunks_json` text DEFAULT NULL COMMENT '最终Chunk JSON',
  `generated_answer` mediumtext DEFAULT NULL COMMENT '生成答案',
  `citations_json` text DEFAULT NULL COMMENT '引用JSON',
  `retrieval_config_id` varchar(128) DEFAULT NULL COMMENT '检索配置ID',
  `model_name` varchar(128) DEFAULT NULL COMMENT '模型名称',
  `prompt_version` varchar(128) DEFAULT NULL COMMENT 'Prompt版本',
  `rewrite_latency_ms` bigint DEFAULT NULL COMMENT '改写延迟',
  `retrieval_latency_ms` bigint DEFAULT NULL COMMENT '检索延迟',
  `rerank_latency_ms` bigint DEFAULT NULL COMMENT '精排延迟',
  `generation_latency_ms` bigint DEFAULT NULL COMMENT '生成延迟',
  `total_latency_ms` bigint DEFAULT NULL COMMENT '总延迟',
  `input_tokens` int DEFAULT NULL COMMENT '输入Token',
  `output_tokens` int DEFAULT NULL COMMENT '输出Token',
  `total_tokens` int DEFAULT NULL COMMENT '总Token',
  `estimated_cost` double DEFAULT NULL COMMENT '预估成本',
  `cache_hit` tinyint(1) DEFAULT NULL COMMENT '是否命中缓存',
  `confidence_level` varchar(32) DEFAULT NULL COMMENT '置信等级',
  `confidence_score` double DEFAULT NULL COMMENT '置信分',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `feedback_type` varchar(32) DEFAULT NULL COMMENT '反馈类型',
  `metadata_json` text DEFAULT NULL COMMENT '扩展元数据',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_online_trace` (`trace_id`),
  KEY `idx_ai_rag_online_trace_user` (`user_id`),
  KEY `idx_ai_rag_online_trace_feedback` (`feedback_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG线上Trace表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_bad_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `bad_case_id` varchar(128) NOT NULL COMMENT 'BadCase ID',
  `trace_id` varchar(128) DEFAULT NULL COMMENT '来源Trace ID',
  `question` text DEFAULT NULL COMMENT '问题',
  `generated_answer` mediumtext DEFAULT NULL COMMENT '生成答案',
  `retrieved_chunks_json` text DEFAULT NULL COMMENT '召回Chunk JSON',
  `expected_answer` text DEFAULT NULL COMMENT '期望答案',
  `expected_chunks` text DEFAULT NULL COMMENT '期望Chunk',
  `feedback_type` varchar(32) DEFAULT NULL COMMENT '反馈类型',
  `failure_type` varchar(64) DEFAULT NULL COMMENT '失败类型',
  `category` varchar(64) DEFAULT NULL COMMENT '分类',
  `difficulty` varchar(16) DEFAULT NULL COMMENT '难度',
  `case_type` varchar(64) DEFAULT NULL COMMENT '用例类型',
  `operator_note` text DEFAULT NULL COMMENT '人工备注',
  `converted_to_eval_case` tinyint(1) DEFAULT 0 COMMENT '是否已转评测用例',
  `converted_case_id` varchar(128) DEFAULT NULL COMMENT '转换后的用例ID',
  `review_status` varchar(32) DEFAULT 'PENDING' COMMENT '审核状态',
  `reviewed_by` bigint DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `review_note` text DEFAULT NULL COMMENT '审核说明',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_bad_case` (`bad_case_id`),
  KEY `idx_ai_rag_bad_case_trace` (`trace_id`),
  KEY `idx_ai_rag_bad_case_failure` (`failure_type`),
  KEY `idx_ai_rag_bad_case_converted` (`converted_to_eval_case`),
  KEY `idx_ai_rag_bad_case_review` (`review_status`,`reviewed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG Bad Case回流表';

CREATE TABLE IF NOT EXISTS `d_ai_nl2sql_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text NOT NULL COMMENT '自然语言问题',
  `expected_sql` text DEFAULT NULL COMMENT '期望SQL(可选,用于精确匹配评测)',
  `expected_result_json` text DEFAULT NULL COMMENT '期望结果集JSON(用于结果等价评测)',
  `expected_table_names` varchar(512) DEFAULT NULL COMMENT '期望涉及的表名(逗号分隔)',
  `category` varchar(64) DEFAULT NULL COMMENT '分类: order/sales/pay/refund/api/mq/cost',
  `difficulty` varchar(16) DEFAULT 'medium' COMMENT '难度: easy/medium/hard',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_nl2sql_eval_case_id` (`case_id`),
  KEY `idx_ai_nl2sql_eval_case_cat` (`category`),
  KEY `idx_ai_nl2sql_eval_case_diff` (`difficulty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NL2SQL评测用例表';

CREATE TABLE IF NOT EXISTS `d_ai_nl2sql_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评估运行ID',
  `total_cases` int DEFAULT 0 COMMENT '总用例数',
  `completed_cases` int DEFAULT 0 COMMENT '完成数',
  `sql_validity_rate` double DEFAULT NULL COMMENT 'SQL合法性通过率(SQL Validity)',
  `execution_accuracy` double DEFAULT NULL COMMENT '执行准确率(Execution Accuracy)',
  `exact_match_rate` double DEFAULT NULL COMMENT 'SQL精确匹配率(Exact Set Match)',
  `result_set_equivalence_rate` double DEFAULT NULL COMMENT '结果集等价率',
  `schema_link_precision` double DEFAULT NULL COMMENT 'Schema Linking Precision',
  `schema_link_recall` double DEFAULT NULL COMMENT 'Schema Linking Recall',
  `repair_success_rate` double DEFAULT NULL COMMENT '修复成功率',
  `unsafe_rejection_rate` double DEFAULT NULL COMMENT '危险请求拒绝率',
  `low_confidence_clarification_rate` double DEFAULT NULL COMMENT '低置信澄清率',
  `avg_latency_ms` double DEFAULT NULL COMMENT '平均延迟ms',
  `avg_estimated_cost` double DEFAULT NULL COMMENT '平均预估成本',
  `run_status` varchar(32) DEFAULT 'RUNNING' COMMENT '状态',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_nl2sql_eval_run_id` (`eval_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NL2SQL评测运行表';

CREATE TABLE IF NOT EXISTS `d_ai_nl2sql_eval_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评估运行ID',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text DEFAULT NULL COMMENT '问题',
  `generated_sql` text DEFAULT NULL COMMENT '生成的SQL',
  `is_valid_sql` tinyint(1) DEFAULT NULL COMMENT 'SQL是否通过安全校验',
  `execute_success` tinyint(1) DEFAULT NULL COMMENT 'SQL是否执行成功',
  `exact_match` tinyint(1) DEFAULT NULL COMMENT '是否与expected_sql精确匹配',
  `result_set_equivalent` tinyint(1) DEFAULT NULL COMMENT '结果集是否等价',
  `unsafe_rejected` tinyint(1) DEFAULT NULL COMMENT '是否拒绝危险请求',
  `low_confidence_clarified` tinyint(1) DEFAULT NULL COMMENT '是否低置信澄清',
  `repair_attempted` tinyint(1) DEFAULT NULL COMMENT '是否尝试修复',
  `repair_succeeded` tinyint(1) DEFAULT NULL COMMENT '是否修复成功',
  `schema_link_precision` double DEFAULT NULL COMMENT 'Schema Linking Precision',
  `schema_link_recall` double DEFAULT NULL COMMENT 'Schema Linking Recall',
  `estimated_cost` double DEFAULT NULL COMMENT '预估成本',
  `latency_ms` bigint DEFAULT NULL COMMENT '延迟ms',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `eval_method` varchar(32) DEFAULT NULL COMMENT '评估方法',
  `safety_report_json` text DEFAULT NULL COMMENT '安全报告JSON',
  `schema_linking_evidence_json` text DEFAULT NULL COMMENT 'Schema Linking证据JSON',
  `repair_trace_json` text DEFAULT NULL COMMENT '修复轨迹JSON',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_nl2sql_eval_result_run` (`eval_run_id`),
  KEY `idx_ai_nl2sql_eval_result_case` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NL2SQL评测结果表';

CREATE TABLE IF NOT EXISTS `d_ai_purchase_agent_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '购票Agent评测运行ID',
  `dataset_id` varchar(128) DEFAULT 'purchase-agent-golden' COMMENT '数据集ID',
  `dataset_version` varchar(64) DEFAULT 'v1' COMMENT '数据集版本',
  `total_cases` int DEFAULT 0 COMMENT '总用例数',
  `completed_cases` int DEFAULT 0 COMMENT '完成用例数',
  `slot_accuracy` double DEFAULT NULL COMMENT '槽位命中率',
  `tool_call_accuracy` double DEFAULT NULL COMMENT '工具调用准确率',
  `parameter_accuracy` double DEFAULT NULL COMMENT '工具参数准确率',
  `trajectory_pass_rate` double DEFAULT NULL COMMENT '轨迹通过率',
  `idempotency_pass_rate` double DEFAULT NULL COMMENT '幂等检查通过率',
  `reservation_release_rate` double DEFAULT NULL COMMENT '预留释放通过率',
  `approval_bypass_count` int DEFAULT 0 COMMENT '审批绕过次数',
  `p95_latency_ms` double DEFAULT NULL COMMENT 'P95端到端耗时',
  `run_status` varchar(32) DEFAULT 'RUNNING' COMMENT '状态',
  `quality_gate_json` text DEFAULT NULL COMMENT '质量门禁JSON',
  `request_json` text DEFAULT NULL COMMENT '请求参数JSON',
  `report_json` mediumtext DEFAULT NULL COMMENT '评测报告JSON',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_purchase_agent_eval_run_id` (`eval_run_id`),
  KEY `idx_ai_purchase_agent_eval_dataset` (`dataset_id`,`dataset_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购票Agent评测运行表';

CREATE TABLE IF NOT EXISTS `d_ai_purchase_agent_eval_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '购票Agent评测运行ID',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text DEFAULT NULL COMMENT '用户问题',
  `final_status` varchar(32) DEFAULT NULL COMMENT '最终状态',
  `slot_accuracy` double DEFAULT NULL COMMENT '槽位命中率',
  `tool_call_accuracy` double DEFAULT NULL COMMENT '工具调用准确率',
  `parameter_accuracy` double DEFAULT NULL COMMENT '工具参数准确率',
  `trajectory_passed` tinyint(1) DEFAULT NULL COMMENT '轨迹是否通过',
  `approval_bypassed` tinyint(1) DEFAULT 0 COMMENT '是否绕过审批',
  `idempotency_passed` tinyint(1) DEFAULT NULL COMMENT '幂等检查是否通过',
  `reservation_released` tinyint(1) DEFAULT NULL COMMENT '预留释放是否通过',
  `latency_ms` bigint DEFAULT NULL COMMENT '耗时ms',
  `expected_trace_json` text DEFAULT NULL COMMENT '期望轨迹JSON',
  `actual_trace_json` text DEFAULT NULL COMMENT '实际轨迹JSON',
  `expected_slots_json` text DEFAULT NULL COMMENT '期望槽位JSON',
  `actual_slots_json` text DEFAULT NULL COMMENT '实际槽位JSON',
  `failure_reason` text DEFAULT NULL COMMENT '失败原因',
  `judge_raw_output` mediumtext DEFAULT NULL COMMENT 'Judge原始输出',
  `eval_method` varchar(32) DEFAULT NULL COMMENT '评估方法',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_purchase_agent_eval_result_run` (`eval_run_id`),
  KEY `idx_ai_purchase_agent_eval_result_case` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购票Agent评测结果表';

CREATE TABLE IF NOT EXISTS `d_ai_episodic_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型',
  `entity_json` text DEFAULT NULL COMMENT '实体JSON',
  `summary` text DEFAULT NULL COMMENT '摘要',
  `weight` double DEFAULT 1.0 COMMENT '权重(衰减)',
  `run_id` varchar(128) DEFAULT NULL COMMENT '关联Run ID',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_episodic_user` (`user_id`,`create_time`),
  KEY `idx_ai_episodic_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI情景记忆表';

-- ============================================================
-- 兼容旧库的增量迁移
-- 说明：上面的 CREATE TABLE IF NOT EXISTS 只会初始化新库。
--      下面这段确保已有 damai_ai 库补齐本次改造需要的新字段和索引。
-- ============================================================

SET @damai_ai_schema := DATABASE();

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_escalation_ticket'
      AND COLUMN_NAME = 'context_json'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_escalation_ticket` ADD COLUMN `context_json` mediumtext DEFAULT NULL COMMENT ''客服上下文JSON'' AFTER `dialogue_summary`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_escalation_ticket'
      AND COLUMN_NAME = 'sentiment'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_escalation_ticket` ADD COLUMN `sentiment` varchar(32) DEFAULT NULL COMMENT ''情绪标签'' AFTER `context_json`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_escalation_ticket'
      AND COLUMN_NAME = 'intent_code'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_escalation_ticket` ADD COLUMN `intent_code` varchar(64) DEFAULT NULL COMMENT ''客服意图'' AFTER `sentiment`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_escalation_ticket'
      AND COLUMN_NAME = 'suggested_reply'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_escalation_ticket` ADD COLUMN `suggested_reply` text DEFAULT NULL COMMENT ''建议人工回复'' AFTER `intent_code`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_escalation_ticket'
      AND INDEX_NAME = 'idx_ai_escalation_customer'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_escalation_ticket` ADD KEY `idx_ai_escalation_customer` (`priority`,`sentiment`,`intent_code`,`ticket_status`)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_run'
      AND COLUMN_NAME = 'event_seq'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_run` ADD COLUMN `event_seq` int DEFAULT 0 COMMENT ''当前事件序号'' AFTER `error_message`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_run'
      AND COLUMN_NAME = 'resumable_state_json'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_run` ADD COLUMN `resumable_state_json` longtext DEFAULT NULL COMMENT ''可恢复执行状态JSON'' AFTER `completed_at`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_run'
      AND COLUMN_NAME = 'resumed'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_run` ADD COLUMN `resumed` int DEFAULT 0 COMMENT ''恢复执行次数'' AFTER `resumable_state_json`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'traffic_percent'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `traffic_percent` int DEFAULT 0 COMMENT ''灰度流量比例'' AFTER `active`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'rollout_status'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `rollout_status` varchar(32) DEFAULT ''DRAFT'' COMMENT ''发布状态:DRAFT/STABLE/GRADUAL/SUPERSEDED/ROLLED_BACK'' AFTER `traffic_percent`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'baseline_eval_run_id'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `baseline_eval_run_id` varchar(128) DEFAULT NULL COMMENT ''发布关联baseline评测Run'' AFTER `rollout_status`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'release_note'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `release_note` varchar(1000) DEFAULT NULL COMMENT ''发布说明'' AFTER `baseline_eval_run_id`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'released_by'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `released_by` bigint DEFAULT NULL COMMENT ''发布人'' AFTER `release_note`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'released_at'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `released_at` datetime DEFAULT NULL COMMENT ''发布时间'' AFTER `released_by`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'rollback_from_version'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `rollback_from_version` varchar(32) DEFAULT NULL COMMENT ''回滚前版本'' AFTER `released_at`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_version'
      AND COLUMN_NAME = 'rollback_reason'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_version` ADD COLUMN `rollback_reason` varchar(1000) DEFAULT NULL COMMENT ''回滚原因'' AFTER `rollback_from_version`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_prompt_release_record'
      AND COLUMN_NAME = 'release_evidence_json'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_prompt_release_record` ADD COLUMN `release_evidence_json` json DEFAULT NULL COMMENT ''发布质量门禁与回滚证据'' AFTER `release_note`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_rag_bad_case'
      AND COLUMN_NAME = 'reviewed_by'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_bad_case` ADD COLUMN `reviewed_by` bigint DEFAULT NULL COMMENT ''审核人'' AFTER `review_status`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_rag_bad_case'
      AND COLUMN_NAME = 'reviewed_at'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_bad_case` ADD COLUMN `reviewed_at` datetime DEFAULT NULL COMMENT ''审核时间'' AFTER `reviewed_by`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_rag_bad_case'
      AND COLUMN_NAME = 'review_note'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_bad_case` ADD COLUMN `review_note` text DEFAULT NULL COMMENT ''审核说明'' AFTER `reviewed_at`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_nl2sql_eval_case'
      AND COLUMN_NAME = 'expected_result_json'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_nl2sql_eval_case` ADD COLUMN `expected_result_json` text DEFAULT NULL COMMENT ''期望结果集JSON(用于结果等价评测)'' AFTER `expected_sql`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'result_set_equivalence_rate'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `result_set_equivalence_rate` double DEFAULT NULL COMMENT ''结果集等价率'' AFTER `exact_match_rate`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'schema_link_precision'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `schema_link_precision` double DEFAULT NULL COMMENT ''Schema Linking Precision'' AFTER `result_set_equivalence_rate`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'schema_link_recall'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `schema_link_recall` double DEFAULT NULL COMMENT ''Schema Linking Recall'' AFTER `schema_link_precision`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'repair_success_rate'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `repair_success_rate` double DEFAULT NULL COMMENT ''修复成功率'' AFTER `schema_link_recall`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'unsafe_rejection_rate'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `unsafe_rejection_rate` double DEFAULT NULL COMMENT ''危险请求拒绝率'' AFTER `repair_success_rate`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'low_confidence_clarification_rate'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `low_confidence_clarification_rate` double DEFAULT NULL COMMENT ''低置信澄清率'' AFTER `unsafe_rejection_rate`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_run' AND COLUMN_NAME = 'avg_estimated_cost'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_run` ADD COLUMN `avg_estimated_cost` double DEFAULT NULL COMMENT ''平均预估成本'' AFTER `avg_latency_ms`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'result_set_equivalent'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `result_set_equivalent` tinyint(1) DEFAULT NULL COMMENT ''结果集是否等价'' AFTER `exact_match`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'unsafe_rejected'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `unsafe_rejected` tinyint(1) DEFAULT NULL COMMENT ''是否拒绝危险请求'' AFTER `result_set_equivalent`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'low_confidence_clarified'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `low_confidence_clarified` tinyint(1) DEFAULT NULL COMMENT ''是否低置信澄清'' AFTER `unsafe_rejected`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'repair_attempted'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `repair_attempted` tinyint(1) DEFAULT NULL COMMENT ''是否尝试修复'' AFTER `low_confidence_clarified`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'repair_succeeded'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `repair_succeeded` tinyint(1) DEFAULT NULL COMMENT ''是否修复成功'' AFTER `repair_attempted`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'schema_link_precision'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `schema_link_precision` double DEFAULT NULL COMMENT ''Schema Linking Precision'' AFTER `repair_succeeded`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'schema_link_recall'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `schema_link_recall` double DEFAULT NULL COMMENT ''Schema Linking Recall'' AFTER `schema_link_precision`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'estimated_cost'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `estimated_cost` double DEFAULT NULL COMMENT ''预估成本'' AFTER `schema_link_recall`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'safety_report_json'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `safety_report_json` text DEFAULT NULL COMMENT ''安全报告JSON'' AFTER `eval_method`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'schema_linking_evidence_json'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `schema_linking_evidence_json` text DEFAULT NULL COMMENT ''Schema Linking证据JSON'' AFTER `safety_report_json`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_nl2sql_eval_result' AND COLUMN_NAME = 'repair_trace_json'), 'SELECT 1', 'ALTER TABLE `d_ai_nl2sql_eval_result` ADD COLUMN `repair_trace_json` text DEFAULT NULL COMMENT ''修复轨迹JSON'' AFTER `schema_linking_evidence_json`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'preview_summary'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `preview_summary` text DEFAULT NULL COMMENT ''动作摘要'' AFTER `preview_json`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'snapshot_hash'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `snapshot_hash` varchar(128) DEFAULT NULL COMMENT ''审批快照摘要Hash'' AFTER `preview_summary`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'idempotency_key'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `idempotency_key` varchar(128) DEFAULT NULL COMMENT ''下单幂等键'' AFTER `snapshot_hash`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'order_number'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `order_number` varchar(64) DEFAULT NULL COMMENT ''创建出的订单号'' AFTER `result_json`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'failure_code'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `failure_code` varchar(64) DEFAULT NULL COMMENT ''失败代码'' AFTER `order_number`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'failure_message'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `failure_message` text DEFAULT NULL COMMENT ''失败信息'' AFTER `failure_code`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'version'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `version` int DEFAULT 0 COMMENT ''乐观锁版本'' AFTER `failure_message`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'processing_started_at'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `processing_started_at` datetime DEFAULT NULL COMMENT ''开始处理时间'' AFTER `approved_at`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND COLUMN_NAME = 'completed_at'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD COLUMN `completed_at` datetime DEFAULT NULL COMMENT ''处理完成时间'' AFTER `processing_started_at`'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @damai_ai_schema
      AND TABLE_NAME = 'd_ai_action'
      AND INDEX_NAME = 'idx_ai_action_idempotency'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_action` ADD KEY `idx_ai_action_idempotency` (`idempotency_key`)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `d_ai_run`
SET `event_seq` = 0
WHERE `event_seq` IS NULL;

UPDATE `d_ai_action`
SET `version` = 0
WHERE `version` IS NULL;

-- RAGAS 评测指标列 (d_ai_rag_eval_result)
SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'context_precision'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `context_precision` double DEFAULT NULL COMMENT ''RAGAS上下文精度'' AFTER `faithfulness_score`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_relevance'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_relevance` double DEFAULT NULL COMMENT ''结构化Judge相关性'' AFTER `safety_score`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_coverage'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_coverage` double DEFAULT NULL COMMENT ''结构化Judge覆盖度'' AFTER `judge_relevance`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_contradiction'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_contradiction` double DEFAULT NULL COMMENT ''结构化Judge矛盾风险'' AFTER `judge_coverage`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_citation_support'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_citation_support` double DEFAULT NULL COMMENT ''结构化Judge引用支撑'' AFTER `judge_contradiction`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_answerability'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_answerability` double DEFAULT NULL COMMENT ''结构化Judge可回答性'' AFTER `judge_citation_support`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_refusal_reason'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_refusal_reason` text DEFAULT NULL COMMENT ''结构化Judge拒答原因'' AFTER `judge_answerability`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'judge_structured_output'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `judge_structured_output` mediumtext DEFAULT NULL COMMENT ''结构化Judge输出'' AFTER `judge_refusal_reason`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'context_recall'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `context_recall` double DEFAULT NULL COMMENT ''RAGAS上下文召回'' AFTER `context_precision`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'answer_relevancy_score'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `answer_relevancy_score` double DEFAULT NULL COMMENT ''RAGAS答案相关性'' AFTER `context_recall`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'eval_method'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `eval_method` varchar(32) DEFAULT NULL COMMENT ''评估方法: HEURISTIC/LLM_JUDGE'' AFTER `answer_relevancy_score`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 新增 RAGAS 扩展指标列 (d_ai_rag_eval_result)
SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'context_relevance'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `context_relevance` double DEFAULT NULL COMMENT ''RAGAS上下文相关性'' AFTER `context_recall`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_result' AND COLUMN_NAME = 'answer_correctness_score'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_result` ADD COLUMN `answer_correctness_score` double DEFAULT NULL COMMENT ''RAGAS答案正确性'' AFTER `answer_relevancy_score`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 新增运行汇总指标列 (d_ai_rag_eval_run)
SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_run' AND COLUMN_NAME = 'avg_answer_relevancy'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_run` ADD COLUMN `avg_answer_relevancy` double DEFAULT NULL COMMENT ''平均答案相关性'' AFTER `avg_faithfulness`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_run' AND COLUMN_NAME = 'avg_completeness'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_run` ADD COLUMN `avg_completeness` double DEFAULT NULL COMMENT ''平均完整性'' AFTER `avg_answer_relevancy`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_run' AND COLUMN_NAME = 'avg_context_relevance'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_run` ADD COLUMN `avg_context_relevance` double DEFAULT NULL COMMENT ''平均上下文相关性'' AFTER `avg_completeness`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_run' AND COLUMN_NAME = 'avg_answer_correctness'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_run` ADD COLUMN `avg_answer_correctness` double DEFAULT NULL COMMENT ''平均答案正确性'' AFTER `avg_context_relevance`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_run' AND COLUMN_NAME = 'avg_ctx_precision'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_run` ADD COLUMN `avg_ctx_precision` double DEFAULT NULL COMMENT ''平均上下文精确度'' AFTER `avg_context_relevance`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @damai_ai_schema AND TABLE_NAME = 'd_ai_rag_eval_run' AND COLUMN_NAME = 'avg_ctx_recall'
  ),
  'SELECT 1',
  'ALTER TABLE `d_ai_rag_eval_run` ADD COLUMN `avg_ctx_recall` double DEFAULT NULL COMMENT ''平均上下文召回率'' AFTER `avg_ctx_precision`'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- RAG 评测用例种子数据
INSERT IGNORE INTO `d_ai_rag_eval_case` (`case_id`, `question`, `expected_answer`, `expected_chunks`, `category`, `difficulty`, `create_time`, `edit_time`, `status`) VALUES
('eval_case_001', '如何申请退票？', '退票需要在演出开始前48小时通过大麦APP或官网提交申请，退票手续费根据距离开演时间阶梯收取。', '["5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159"]', 'refund', 'easy', NOW(), NOW(), 1),
('eval_case_002', '如果演出取消了我的票会自动退款吗？需要多长时间到账？', '演出取消时无需手动申请退票，系统会自动退款至原支付账户。退款到账时间一般为1-15个工作日，具体取决于支付方式。银行卡1-3个工作日，微信/支付宝1-7个工作日，信用卡1-15个工作日。','["f89fa1a59410a25b8a0e0f961727e00d","52a9bf11971454cd90fdb4dc30cdb5c5","5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159"]', 'refund', 'hard', NOW(), NOW(), 1),
('eval_case_003', '电子票怎么取票？', '电子票无需取票，演出当天凭购票时使用的身份证件和电子票二维码入场即可。', '["9edde96f909989de41fbe770856a5e17","1f667d740455687e84374634352c3d48","d5db536f6b3372d35d3166cec1f68944"]', 'delivery', 'easy', NOW(), NOW(), 1),
('eval_case_004', '购买时选了快递配送，最晚什么时候能收到票？', '快递配送一般在演出前7-10天陆续发出，演出开始前3天停止配送。若超过演出前5天仍未收到，建议联系客服核实物流状态。', '["d0e0fe4da689691b548344ca08f98c1a","4db55f25b5f68aee4188979b5bf08eac","fc7481f0d1f4c1a606cc0ec41ea7d466"]', 'delivery', 'medium', NOW(), NOW(), 1),
('eval_case_005', '订单支付超时了怎么办？', '订单支付超时后会自动取消，需要重新下单。建议在下单后15分钟内完成支付。', '["edf5c7514ed7e1b9be5921816e714dbb","d5fe3c91a6e279ed4ca94e2d54e86f34","d0bbc1ff4909a7c24c50e20abb4b3f10"]', 'payment', 'medium', NOW(), NOW(), 1),
('eval_case_006', '重复支付了同一笔订单会怎样？银行卡被扣了两次款。', '系统会自动检测重复支付，多扣的款项会在1-3个工作日内原路退回。如果超过3个工作日未收到退款，请在订单详情页提交重复支付申诉或联系人工客服处理。', '["ea1447ae201f689b20d408912716e47b","40b2466adccc388bf0bae7067f8b5dd6","4b74f15d47d4f57ea70042d53aba419e"]', 'payment', 'hard', NOW(), NOW(), 1),
('eval_case_007', '儿童需要买票吗？', '儿童也需要购票入场，1.2米以下儿童谢绝入场（儿童剧除外），1.2米以上儿童凭票入场。具体以演出页面说明为准。', '["c67a29b86c07dd12a29380cb4cf8245a","ea767fee13a66a9599105f397096535a"]', 'entry', 'easy', NOW(), NOW(), 1),
('eval_case_008', '观演人信息填错了能改吗？', '观演人信息在订单支付成功后不支持修改。如确实填写错误，建议取消订单重新购买。部分演出支持转赠功能，可通过票夹将电子票转赠给他人。', '["b9f2e8972ebb628d7c5770ff1576f903","0014def686eb408669788e270ab251b1","8eed40754ad5398b4367240b416c8eb7"]', 'entry', 'medium', NOW(), NOW(), 1),
('eval_case_009', '能带相机进场吗？', '一般演出禁止携带专业摄影摄像设备入场，手机拍照不受限制。具体以演出页面的观演须知为准。', '["978e3e4126d916f5c573486af9ba1f51","217d08cc51009befaad0c61cc6d87f86"]', 'venue', 'easy', NOW(), NOW(), 1),
('eval_case_010', '我在非官方渠道买的票被拦在门口了，有什么办法吗？', '非官方渠道购买的票存在假票风险，大麦不承担非官方渠道购票的损失。建议通过官方渠道重新购票，并向购票平台投诉维权，保留交易记录作为证据。', '["00143f082fa1f4c759f0464e0152ecfe","6e8ef763b0e90bfe361a0e49aea93f63","9b8cc07a54139a6cda283544aa11a4c2","9f6e8eecefae6305715a888ec19e8b25"]', 'venue', 'hard', NOW(), NOW(), 1),

-- ============================================================
-- 扩展评测用例 (P5: 40 additional cases, total 50+)
-- ============================================================

-- ===== 退票/退款 (refund) =====
('eval_case_011', '退票申请后多久能到账？', '退票款项将在1-7个工作日内退回原支付账户，具体到账时间取决于银行处理速度。银行卡通常1-3个工作日，第三方支付1-7个工作日。', '["5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159","f89fa1a59410a25b8a0e0f961727e00d"]', 'refund', 'easy', NOW(), NOW(), 1),
('eval_case_012', '退票手续费怎么算？', '退票手续费根据距离开演时间阶梯收取。开演前48小时以上手续费较低，48小时内手续费较高。具体费率以演出页面退票规则为准。', '["5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159"]', 'refund', 'medium', NOW(), NOW(), 1),
('eval_case_013', '帮朋友买的票他能自己退吗？', '退票需要使用购票账户登录并在订单详情页点击申请退票，非购票账户无法操作。建议让购票人自行操作或提供账户信息。', '["5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159"]', 'refund', 'medium', NOW(), NOW(), 1),
('eval_case_014', '已经过了退票截止时间还能退吗？', '超过退票截止时间的订单一般不支持退票。特殊情况下（如突发疾病、自然灾害）可联系客服申请特殊处理，但不保证通过。', '["5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159","52a9bf11971454cd90fdb4dc30cdb5c5"]', 'refund', 'hard', NOW(), NOW(), 1),
('eval_case_015', '退票后优惠券会退回吗？', '退票成功后，订单中使用的优惠券是否退还需根据优惠券使用规则判断。部分限时优惠券过期后不予退还。', '["5b93c5c7162facc8ef2d112f9b72e509","f89fa1a59410a25b8a0e0f961727e00d"]', 'refund', 'medium', NOW(), NOW(), 1),
('eval_case_016', '买的连座票能只退一张吗？', '连座票一般作为一个订单整体处理，不支持部分退票。如有特殊需求建议联系客服咨询。', '["5b93c5c7162facc8ef2d112f9b72e509","abda442228f338d5210be369dbe96159"]', 'refund', 'medium', NOW(), NOW(), 1),
('eval_case_017', '演出延期了能退票吗？', '演出延期时，购票平台一般会提供退票通道。用户可选择保留订单等待延期演出或申请全额退款。具体以平台公告为准。', '["f89fa1a59410a25b8a0e0f961727e00d","52a9bf11971454cd90fdb4dc30cdb5c5"]', 'refund', 'easy', NOW(), NOW(), 1),
('eval_case_018', '退票申请提交错了能撤销吗？', '退票申请提交后无法撤销，请谨慎操作。如需帮助请联系客服，但一旦系统已处理则无法恢复订单。', '["5b93c5c7162facc8ef2d112f9b72e509"]', 'refund', 'hard', NOW(), NOW(), 1),

-- ===== 配送/取票 (delivery) =====
('eval_case_019', '快递票可以改成电子票吗？', '订单支付完成后配送方式一般不支持修改。如需更改请取消订单重新购买并选择所需配送方式。', '["9edde96f909989de41fbe770856a5e17","d0e0fe4da689691b548344ca08f98c1a"]', 'delivery', 'medium', NOW(), NOW(), 1),
('eval_case_020', '快递票丢了怎么办？', '快递票丢失后建议第一时间联系客服。根据演出类型不同，可申请补票或凭购票凭证及身份证件现场核实身份后入场。', '["d0e0fe4da689691b548344ca08f98c1a","4db55f25b5f68aee4188979b5bf08eac"]', 'delivery', 'hard', NOW(), NOW(), 1),
('eval_case_021', '收货地址填错了怎么改？', '订单支付成功后收货地址不支持修改。如尚未发货可尝试联系客服，但无法保证成功。建议及时关注物流状态。', '["d0e0fe4da689691b548344ca08f98c1a","4db55f25b5f68aee4188979b5bf08eac"]', 'delivery', 'medium', NOW(), NOW(), 1),
('eval_case_022', '现场取票和快递哪个好？', '电子票最便捷无需等待。快递配送适合需要纸质票留念的用户。现场取票需要提前到现场，具体以演出页面可选配送方式为准。', '["9edde96f909989de41fbe770856a5e17","1f667d740455687e84374634352c3d48","d5db536f6b3372d35d3166cec1f68944","d0e0fe4da689691b548344ca08f98c1a"]', 'delivery', 'easy', NOW(), NOW(), 1),
('eval_case_023', '我的票什么时候发货？', '快递票一般在演出前7-10天陆续发出，发货后会短信通知快递单号。演出前3天停止配送，临近演出将改为现场取票。', '["4db55f25b5f68aee4188979b5bf08eac","fc7481f0d1f4c1a606cc0ec41ea7d466"]', 'delivery', 'easy', NOW(), NOW(), 1),
('eval_case_024', '国外地址能配送吗？', '快递配送仅支持中国大陆地区。海外用户建议选择电子票，或委托国内亲友代收后转交。', '["d0e0fe4da689691b548344ca08f98c1a","4db55f25b5f68aee4188979b5bf08eac"]', 'delivery', 'medium', NOW(), NOW(), 1),
('eval_case_025', '怎么查看快递单号？', '可在订单详情页查看物流信息，包括快递公司和快递单号。发货后也会通过短信通知购票人。', '["4db55f25b5f68aee4188979b5bf08eac","fc7481f0d1f4c1a606cc0ec41ea7d466"]', 'delivery', 'easy', NOW(), NOW(), 1),
('eval_case_026', '买了三张票快递来了两张怎么办？', '建议先核对待收货数量和订单信息，确认是否为分批配送。如确实少发，保存快递包装并在订单详情页报备，联系客服核实处理。', '["d0e0fe4da689691b548344ca08f98c1a","4db55f25b5f68aee4188979b5bf08eac","fc7481f0d1f4c1a606cc0ec41ea7d466"]', 'delivery', 'hard', NOW(), NOW(), 1),

-- ===== 支付 (payment) =====
('eval_case_027', '支持哪些支付方式？', '支持银行卡、微信支付、支付宝、花呗分期、信用卡分期等多种支付方式。具体以结算页面展示为准。', '["edf5c7514ed7e1b9be5921816e714dbb","d5fe3c91a6e279ed4ca94e2d54e86f34"]', 'payment', 'easy', NOW(), NOW(), 1),
('eval_case_028', '支付时提示余额不足但我卡里有钱？', '建议检查是否开通了快捷支付限额。部分银行对大额支付有限额，可分多张卡支付或联系银行调整限额。', '["edf5c7514ed7e1b9be5921816e714dbb","d5fe3c91a6e279ed4ca94e2d54e86f34"]', 'payment', 'medium', NOW(), NOW(), 1),
('eval_case_029', '为什么一直显示支付处理中？', '支付处理中通常是因为银行系统延迟，一般5-10分钟内会有结果。如超过15分钟仍无反应建议联系银行客服确认扣款状态。', '["edf5c7514ed7e1b9be5921816e714dbb","d0bbc1ff4909a7c24c50e20abb4b3f10"]', 'payment', 'medium', NOW(), NOW(), 1),
('eval_case_030', '可以花呗分期吗？', '部分演出支持花呗分期付款，具体以结算页面是否显示分期选项为准。免息分期活动以花呗官方活动规则为准。', '["edf5c7514ed7e1b9be5921816e714dbb"]', 'payment', 'easy', NOW(), NOW(), 1),
('eval_case_031', '支付成功后订单还是显示待支付？', '支付成功但状态未更新可能是因为银行通知延迟。可在订单详情页手动刷新或等待5-10分钟。如长时间未更新请提供付款凭证联系客服。', '["d0bbc1ff4909a7c24c50e20abb4b3f10","ea1447ae201f689b20d408912716e47b"]', 'payment', 'hard', NOW(), NOW(), 1),
('eval_case_032', '微信支付的扣款记录和订单金额不一致？', '如有金额差异请截图保存微信支付记录和订单页面。联系客服提供两边的对比截图，客服会核实是否存在多扣或系统错误。', '["ea1447ae201f689b20d408912716e47b","40b2466adccc388bf0bae7067f8b5dd6","4b74f15d47d4f57ea70042d53aba419e"]', 'payment', 'hard', NOW(), NOW(), 1),
('eval_case_033', '组合支付怎么操作？', '部分订单支持组合支付（余额+银行卡或余额+微信等），在结算页面选择"组合支付"后按提示操作即可。', '["edf5c7514ed7e1b9be5921816e714dbb","d5fe3c91a6e279ed4ca94e2d54e86f34"]', 'payment', 'medium', NOW(), NOW(), 1),
('eval_case_034', '支付时优惠券怎么没用上？', '请检查优惠券的使用条件（满减门槛、适用演出、有效期限）。确认符合条件后在提交订单页面手动勾选优惠券。', '["edf5c7514ed7e1b9be5921816e714dbb","d0bbc1ff4909a7c24c50e20abb4b3f10"]', 'payment', 'easy', NOW(), NOW(), 1),

-- ===== 入场/观演 (entry) =====
('eval_case_035', '电子票用什么证件入场？', '电子票需凭购票时填写的身份证件入场，支持身份证、护照、港澳通行证、台胞证。入场时需同时出示电子票二维码和证件。', '["1f667d740455687e84374634352c3d48","d5db536f6b3372d35d3166cec1f68944","c67a29b86c07dd12a29380cb4cf8245a"]', 'entry', 'easy', NOW(), NOW(), 1),
('eval_case_036', '可以带小孩去看演唱会吗？', '大部分演唱会1.2米以下儿童谢绝入场，儿童剧除外。1.2米以上儿童需购票入场。具体以演出页面的"儿童入场说明"为准。', '["c67a29b86c07dd12a29380cb4cf8245a","ea767fee13a66a9599105f397096535a"]', 'entry', 'easy', NOW(), NOW(), 1),
('eval_case_037', '购票后身份证丢了怎么入场？', '身份证丢失可使用临时身份证、户口本或护照等有效证件。也可在演出前到公安机关办理临时身份证明。建议尽快补办并联系客服确认替代方案。', '["b9f2e8972ebb628d7c5770ff1576f903","1f667d740455687e84374634352c3d48"]', 'entry', 'hard', NOW(), NOW(), 1),
('eval_case_038', '可以把票转给朋友吗？', '部分演出支持电子票转赠功能，可在票夹中选择"转赠"将电子票发送给朋友。转赠后原购票人将无法使用该票。快递票不支持线上转赠。', '["b9f2e8972ebb628d7c5770ff1576f903","0014def686eb408669788e270ab251b1","8eed40754ad5398b4367240b416c8eb7"]', 'entry', 'medium', NOW(), NOW(), 1),
('eval_case_039', '进场后发现座位有人坐了怎么办？', '请先核对双方票面信息确认座位归属。如有争议，联系现场工作人员协调处理，不要自行解决以免影响其他观众。', '["b9f2e8972ebb628d7c5770ff1576f903","0014def686eb408669788e270ab251b1"]', 'entry', 'medium', NOW(), NOW(), 1),
('eval_case_040', '可以强实名制的票用别人的身份证能进吗？', '实行强实名制的演出必须人、证、票一致方可入场。使用他人身份证购买的门票持票人本人无法入场，建议确认演出是否为强实名制后再购票。', '["b9f2e8972ebb628d7c5770ff1576f903","0014def686eb408669788e270ab251b1","c67a29b86c07dd12a29380cb4cf8245a"]', 'entry', 'hard', NOW(), NOW(), 1),
('eval_case_041', '检票口在哪里？', '检票口位置可在电子票页面查看，或到达现场后根据场馆导引指示找到对应检票口。部分大型场馆有多个检票口，根据座位区域选择最近的入口。', '["1f667d740455687e84374634352c3d48","d5db536f6b3372d35d3166cec1f68944"]', 'entry', 'easy', NOW(), NOW(), 1),
('eval_case_042', '迟到还能进场吗？', '一般演出开始后仍可入场，但部分演出（如古典音乐会、话剧）为不影响其他观众和演出秩序，迟到观众需等待曲目间隙或幕间休息时方可入场。', '["c67a29b86c07dd12a29380cb4cf8245a","8eed40754ad5398b4367240b416c8eb7"]', 'entry', 'medium', NOW(), NOW(), 1),

-- ===== 场馆/观演须知 (venue) =====
('eval_case_043', '演出大概时长多久？', '演出时长以演出页面说明为准，通常演唱会在1.5-3小时之间。演出开始时间为票面标注时间，建议提前30-60分钟到场。', '["978e3e4126d916f5c573486af9ba1f51","217d08cc51009befaad0c61cc6d87f86"]', 'venue', 'easy', NOW(), NOW(), 1),
('eval_case_044', '场馆有寄存处吗？', '大部分大型场馆设有寄存处，但收费标准和容量有限。建议尽量减少随身物品。寄存费用以场馆规定为准。', '["978e3e4126d916f5c573486af9ba1f51","217d08cc51009befaad0c61cc6d87f86"]', 'venue', 'easy', NOW(), NOW(), 1),
('eval_case_045', '场馆附近有停车场吗？', '大型演出场馆一般配备停车场或周边有商业停车场。但演出日车位紧张，建议乘坐公共交通前往。具体停车场信息可在场馆官网查询。', '["978e3e4126d916f5c573486af9ba1f51"]', 'venue', 'medium', NOW(), NOW(), 1),
('eval_case_046', '座位图怎么看？', '选座购票时系统会显示座位图，可按区域和价格筛选。座位图标注了舞台位置、各区域价格、已售/可选状态。建议结合舞台位置选择最佳观演区域。', '["217d08cc51009befaad0c61cc6d87f86"]', 'venue', 'easy', NOW(), NOW(), 1),
('eval_case_047', '黄牛票和官方票怎么区分？', '官方票通过大麦APP/官网/小程序购买，票面有防伪标识并可在大麦票夹中验证。黄牛票无法通过官方渠道验证，且存在假票、重复售票等风险。', '["00143f082fa1f4c759f0464e0152ecfe","6e8ef763b0e90bfe361a0e49aea93f63","9b8cc07a54139a6cda283544aa11a4c2"]', 'venue', 'medium', NOW(), NOW(), 1),
('eval_case_048', '场馆内可以吃东西吗？', '大部分演出场馆内禁止饮食，但部分设有餐饮区。建议在演出开始前在指定区域用餐。入场安检时食品和饮料可能会被要求寄存或丢弃。', '["978e3e4126d916f5c573486af9ba1f51","217d08cc51009befaad0c61cc6d87f86"]', 'venue', 'medium', NOW(), NOW(), 1),
('eval_case_049', '演出当天天气不好会不会取消？', '一般天气不影响室内演出。户外演出如遇极端天气（台风、暴雨、暴雪），主办方会评估后发布公告，请关注官方通知。演出取消会有短信通知。', '["978e3e4126d916f5c573486af9ba1f51","f89fa1a59410a25b8a0e0f961727e00d","52a9bf11971454cd90fdb4dc30cdb5c5"]', 'venue', 'medium', NOW(), NOW(), 1),
('eval_case_050', '无障碍设施在哪里？', '大型场馆一般配备无障碍通道、无障碍卫生间和轮椅专用观演区域。如需协助可提前联系场馆或现场联系工作人员。', '["978e3e4126d916f5c573486af9ba1f51","217d08cc51009befaad0c61cc6d87f86"]', 'venue', 'hard', NOW(), NOW(), 1);

-- ============================================================
-- RAG 文档管理相关表
-- ============================================================

CREATE TABLE IF NOT EXISTS `d_ai_rag_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `doc_uid` varchar(128) NOT NULL COMMENT '文档全局唯一ID',
  `title` varchar(500) DEFAULT NULL COMMENT '文档标题',
  `source` varchar(100) DEFAULT 'manual' COMMENT '来源: manual/upload/api/crawl',
  `source_file` varchar(500) DEFAULT NULL COMMENT '源文件名',
  `category` varchar(100) DEFAULT NULL COMMENT '分类: refund/entry/payment/delivery/venue',
  `tags` varchar(1000) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `doc_status` varchar(32) DEFAULT 'draft' COMMENT '状态: draft/published/expired/archived',
  `file_type` varchar(20) DEFAULT 'md' COMMENT '文件类型',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '文档内容MD5',
  `metadata_json` text DEFAULT NULL COMMENT '扩展元数据JSON',
  `chunk_count` int DEFAULT 0 COMMENT '切片数量',
  `version` int DEFAULT 1 COMMENT '版本号',
  `valid_from` datetime DEFAULT NULL COMMENT '生效时间',
  `valid_until` datetime DEFAULT NULL COMMENT '失效时间',
  `region` varchar(500) DEFAULT NULL COMMENT '适用地区',
  `audience` varchar(200) DEFAULT 'all' COMMENT '目标受众',
  `priority` int DEFAULT 0 COMMENT '优先级(0-100)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_uid` (`doc_uid`),
  KEY `idx_rag_doc_source_file` (`source_file`),
  KEY `idx_rag_doc_status` (`doc_status`),
  KEY `idx_rag_doc_category` (`category`),
  KEY `idx_rag_doc_valid` (`valid_from`,`valid_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG文档主表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `chunk_uid` varchar(128) NOT NULL COMMENT 'Chunk全局唯一ID(MD5)',
  `doc_id` bigint DEFAULT NULL COMMENT '所属文档ID',
  `parent_chunk_id` bigint DEFAULT NULL COMMENT '父块ID(子块→父块)',
  `chunk_type` varchar(32) DEFAULT 'faq' COMMENT '类型: faq/faq_part/summary/table',
  `chunk_index` int DEFAULT 0 COMMENT '块序号',
  `total_chunks` int DEFAULT 1 COMMENT '文档总块数',
  `heading_path` varchar(1000) DEFAULT NULL COMMENT '标题路径',
  `question` varchar(1000) DEFAULT NULL COMMENT 'FAQ问题',
  `text` mediumtext COMMENT '原始文本',
  `context_text` mediumtext COMMENT '附加上下文的文本(用于embedding)',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '文本内容MD5',
  `metadata_json` text DEFAULT NULL COMMENT '扩展元数据JSON',
  `qdrant_point_id` bigint DEFAULT NULL COMMENT 'Qdrant Point ID',
  `es_doc_id` varchar(128) DEFAULT NULL COMMENT 'ES文档ID',
  `prev_chunk_id` bigint DEFAULT NULL COMMENT '前一块ID',
  `next_chunk_id` bigint DEFAULT NULL COMMENT '后一块ID',
  `embedding_cached` tinyint(1) DEFAULT 0 COMMENT '是否已缓存Embedding',
  `hypothetical_questions_json` text DEFAULT NULL COMMENT 'LLM生成的假设性问题JSON数组',
  `summary_text` varchar(2000) DEFAULT NULL COMMENT 'LLM生成的摘要',
  `entities_json` text DEFAULT NULL COMMENT 'LLM提取的实体JSON',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chunk_uid` (`chunk_uid`),
  KEY `idx_rag_chunk_doc` (`doc_id`),
  KEY `idx_rag_chunk_parent` (`parent_chunk_id`),
  KEY `idx_rag_chunk_type` (`chunk_type`),
  KEY `idx_rag_chunk_qdrant` (`qdrant_point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG文档Chunk表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_ingestion_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `task_id` varchar(128) NOT NULL COMMENT '任务ID',
  `task_type` varchar(32) DEFAULT 'full' COMMENT '任务类型: full/incremental/single',
  `task_status` varchar(32) DEFAULT 'pending' COMMENT '状态: pending/parsing/chunking/embedding/indexing/completed/failed',
  `source_file` varchar(500) DEFAULT NULL COMMENT '源文件(单文件任务)',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '源文件Hash',
  `total_chunks` int DEFAULT 0 COMMENT '总块数',
  `completed_chunks` int DEFAULT 0 COMMENT '已完成块数',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `result_json` text DEFAULT NULL COMMENT '结果JSON',
  `started_at` varchar(30) DEFAULT NULL COMMENT '开始时间',
  `finished_at` varchar(30) DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_rag_task_status` (`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG入库任务表';
