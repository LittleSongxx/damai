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
  `original_query` text DEFAULT NULL COMMENT '原始Query',
  `rewritten_query` text DEFAULT NULL COMMENT '改写Query',
  `dense_hits_json` longtext DEFAULT NULL COMMENT '向量召回结果',
  `sparse_hits_json` longtext DEFAULT NULL COMMENT '稀疏召回结果',
  `fused_hits_json` longtext DEFAULT NULL COMMENT '融合结果',
  `final_hits_json` longtext DEFAULT NULL COMMENT '最终命中结果',
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
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
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

CREATE TABLE IF NOT EXISTS `d_ai_action` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `action_id` varchar(128) NOT NULL COMMENT '动作ID',
  `run_id` varchar(128) NOT NULL COMMENT 'Run ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `action_type` varchar(64) NOT NULL COMMENT '动作类型',
  `action_status` varchar(32) NOT NULL COMMENT '动作状态',
  `preview_json` longtext DEFAULT NULL COMMENT '动作预览',
  `result_json` longtext DEFAULT NULL COMMENT '动作结果',
  `expires_at` datetime DEFAULT NULL COMMENT '过期时间',
  `approved_at` datetime DEFAULT NULL COMMENT '批准时间',
  `rejected_at` datetime DEFAULT NULL COMMENT '拒绝时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_action_id` (`action_id`),
  KEY `idx_ai_action_run` (`run_id`,`action_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一助手动作表';

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

CREATE TABLE IF NOT EXISTS `d_ai_prompt_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `prompt_key` varchar(128) NOT NULL COMMENT 'Prompt标识',
  `version` int NOT NULL DEFAULT 1 COMMENT '版本号',
  `template` longtext NOT NULL COMMENT 'Prompt模板内容',
  `description` varchar(512) DEFAULT NULL COMMENT '描述',
  `active` tinyint(1) DEFAULT '1' COMMENT '是否活跃',
  `created_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_prompt_key_version` (`prompt_key`,`version`),
  KEY `idx_ai_prompt_active` (`prompt_key`,`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Prompt版本管理表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text NOT NULL COMMENT '问题',
  `expected_answer` text DEFAULT NULL COMMENT '期望答案',
  `expected_chunks` text DEFAULT NULL COMMENT '期望命中的chunkId列表(JSON)',
  `category` varchar(64) DEFAULT NULL COMMENT '分类',
  `difficulty` varchar(16) DEFAULT NULL COMMENT '难度: easy/medium/hard',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_case_id` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估用例表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评估运行ID',
  `total_cases` int DEFAULT 0 COMMENT '总用例数',
  `completed_cases` int DEFAULT 0 COMMENT '完成数',
  `avg_recall` double DEFAULT NULL COMMENT '平均Recall@5',
  `avg_mrr` double DEFAULT NULL COMMENT '平均MRR',
  `avg_ndcg` double DEFAULT NULL COMMENT '平均NDCG@5',
  `avg_faithfulness` double DEFAULT NULL COMMENT '平均Faithfulness',
  `run_status` varchar(32) DEFAULT 'RUNNING' COMMENT '状态',
  `error_message` text DEFAULT NULL COMMENT '错误信息',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_rag_eval_run_id` (`eval_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估运行表';

CREATE TABLE IF NOT EXISTS `d_ai_rag_eval_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `eval_run_id` varchar(128) NOT NULL COMMENT '评估运行ID',
  `case_id` varchar(128) NOT NULL COMMENT '用例ID',
  `question` text DEFAULT NULL COMMENT '问题',
  `retrieved_chunks` text DEFAULT NULL COMMENT '检索到的chunkId列表(JSON)',
  `generated_answer` text DEFAULT NULL COMMENT '生成的答案',
  `recall_at_5` double DEFAULT NULL COMMENT 'Recall@5',
  `mrr` double DEFAULT NULL COMMENT 'MRR',
  `ndcg_at_5` double DEFAULT NULL COMMENT 'NDCG@5',
  `faithfulness_score` double DEFAULT NULL COMMENT 'Faithfulness得分',
  `latency_ms` bigint DEFAULT NULL COMMENT '延迟ms',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_rag_eval_result_run` (`eval_run_id`),
  KEY `idx_ai_rag_eval_result_case` (`case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评估结果表';

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
