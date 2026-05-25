-- ============================================================
-- AI客服系统增强：FAQ匹配、对话状态机、情感升级、主动推送、运营后台、分析看板、反馈闭环
-- ============================================================

-- 1. FAQ精确匹配表 (参考 Dify 的 dataset_segments + hit_testing 设计)
CREATE TABLE IF NOT EXISTS `d_ai_faq_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `faq_id` varchar(128) NOT NULL COMMENT 'FAQ条目ID',
  `question` varchar(1000) NOT NULL COMMENT '标准问题',
  `similar_questions_json` longtext DEFAULT NULL COMMENT '相似问法JSON数组',
  `answer` longtext NOT NULL COMMENT '标准答案',
  `category` varchar(100) DEFAULT NULL COMMENT '分类: refund/entry/payment/delivery/venue/account',
  `tags` varchar(1000) DEFAULT NULL COMMENT '标签(逗号分隔)',
  `keywords` varchar(1000) DEFAULT NULL COMMENT '关键词(逗号分隔)',
  `embedding_cached` tinyint(1) DEFAULT 0 COMMENT '是否已缓存问题Embedding',
  `priority` int DEFAULT 0 COMMENT '优先级(0-100), 越高越优先匹配',
  `hit_count` int DEFAULT 0 COMMENT '命中次数',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `valid_from` datetime DEFAULT NULL COMMENT '生效时间',
  `valid_until` datetime DEFAULT NULL COMMENT '失效时间',
  `region` varchar(500) DEFAULT NULL COMMENT '适用地区',
  `audience` varchar(200) DEFAULT 'all' COMMENT '目标受众',
  `created_by` bigint DEFAULT NULL COMMENT '创建者',
  `updated_by` bigint DEFAULT NULL COMMENT '最后更新者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_faq_id` (`faq_id`),
  KEY `idx_faq_category` (`category`),
  KEY `idx_faq_enabled` (`enabled`),
  KEY `idx_faq_valid` (`valid_from`,`valid_until`),
  KEY `idx_faq_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FAQ精确匹配条目表';

-- 2. 对话状态机表
CREATE TABLE IF NOT EXISTS `d_ai_dialogue_state` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `state_id` varchar(128) NOT NULL COMMENT '状态ID',
  `conversation_id` varchar(128) NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `intent` varchar(64) DEFAULT NULL COMMENT '当前意图: BUY_TICKET/QUERY_PROGRAM/REFUND/FAQ/GENERAL',
  `dialogue_phase` varchar(32) NOT NULL COMMENT '对话阶段: INTENT_IDENTIFIED/SLOT_FILLING/CONFIRMATION/EXECUTION/CLOSED',
  `slots_json` longtext DEFAULT NULL COMMENT '槽位填充JSON',
  `missing_slots_json` longtext DEFAULT NULL COMMENT '待填充槽位JSON',
  `turn_count` int DEFAULT 0 COMMENT '当前轮次',
  `max_turns` int DEFAULT 10 COMMENT '最大轮次',
  `last_user_message` text DEFAULT NULL COMMENT '最近用户消息',
  `last_assistant_message` text DEFAULT NULL COMMENT '最近助手消息',
  `resolved` tinyint(1) DEFAULT 0 COMMENT '是否已解决',
  `resolve_time` datetime DEFAULT NULL COMMENT '解决时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_state_id` (`state_id`),
  KEY `idx_dialogue_conv` (`conversation_id`,`user_id`),
  KEY `idx_dialogue_phase` (`dialogue_phase`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话状态机表';

-- 3. 情感分析记录表
CREATE TABLE IF NOT EXISTS `d_ai_sentiment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `record_id` varchar(128) NOT NULL COMMENT '记录ID',
  `run_id` varchar(128) NOT NULL COMMENT '关联Run ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `user_message` text DEFAULT NULL COMMENT '用户消息',
  `sentiment` varchar(16) NOT NULL COMMENT '情感: POSITIVE/NEUTRAL/NEGATIVE',
  `intensity` double DEFAULT 0.5 COMMENT '情感强度(0-1)',
  `is_urgent` tinyint(1) DEFAULT 0 COMMENT '是否紧急',
  `emotion_tags_json` text DEFAULT NULL COMMENT '情绪标签JSON: ["angry","anxious","satisfied"]',
  `escalation_triggered` tinyint(1) DEFAULT 0 COMMENT '是否触发升级',
  `escalation_reason` varchar(256) DEFAULT NULL COMMENT '升级原因',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sentiment_record_id` (`record_id`),
  KEY `idx_sentiment_run` (`run_id`),
  KEY `idx_sentiment_user` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='情感分析记录表';

-- 4. 智能升级工单表
CREATE TABLE IF NOT EXISTS `d_ai_escalation_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `ticket_id` varchar(128) NOT NULL COMMENT '工单ID',
  `run_id` varchar(128) NOT NULL COMMENT '关联Run ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `escalation_type` varchar(32) NOT NULL COMMENT '升级类型: SENTIMENT/UNRESOLVED/COMPLEX/MANUAL',
  `priority` varchar(16) DEFAULT 'MEDIUM' COMMENT '优先级: LOW/MEDIUM/HIGH/CRITICAL',
  `ticket_status` varchar(32) DEFAULT 'OPEN' COMMENT '状态: OPEN/ASSIGNED/IN_PROGRESS/RESOLVED/CLOSED',
  `ai_diagnosis` text DEFAULT NULL COMMENT 'AI诊断摘要',
  `dialogue_summary` text DEFAULT NULL COMMENT '对话上下文摘要',
  `assigned_to` bigint DEFAULT NULL COMMENT '指派的客服ID',
  `resolution` text DEFAULT NULL COMMENT '解决结果',
  `resolved_at` datetime DEFAULT NULL COMMENT '解决时间',
  `resolved_by` bigint DEFAULT NULL COMMENT '解决人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_id` (`ticket_id`),
  KEY `idx_escalation_user` (`user_id`,`ticket_status`),
  KEY `idx_escalation_status` (`ticket_status`,`priority`),
  KEY `idx_escalation_assigned` (`assigned_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能升级工单表';

-- 5. 主动推送通知表
CREATE TABLE IF NOT EXISTS `d_ai_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `notification_id` varchar(128) NOT NULL COMMENT '通知ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `notification_type` varchar(32) NOT NULL COMMENT '通知类型: TICKET_ON_SALE/ORDER_STATUS/REFUND_PROGRESS/SHOW_REMINDER/SYSTEM',
  `title` varchar(256) NOT NULL COMMENT '通知标题',
  `content` text DEFAULT NULL COMMENT '通知内容',
  `action_url` varchar(500) DEFAULT NULL COMMENT '操作链接',
  `action_text` varchar(128) DEFAULT NULL COMMENT '操作文案',
  `read_status` tinyint(1) DEFAULT 0 COMMENT '是否已读',
  `read_at` datetime DEFAULT NULL COMMENT '阅读时间',
  `send_channel` varchar(32) DEFAULT 'in_app' COMMENT '发送渠道: in_app/push/email/sms',
  `scheduled_for` datetime DEFAULT NULL COMMENT '定时发送时间',
  `sent_at` datetime DEFAULT NULL COMMENT '实际发送时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_id` (`notification_id`),
  KEY `idx_notif_user_read` (`user_id`,`read_status`),
  KEY `idx_notif_scheduled` (`scheduled_for`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动推送通知表';

-- 6. 用户订阅偏好表
CREATE TABLE IF NOT EXISTS `d_ai_user_subscription` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `subscription_id` varchar(128) DEFAULT NULL COMMENT '订阅业务ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `subscription_type` varchar(64) NOT NULL COMMENT '订阅类型: artist_news/city_show/price_alert/pre_sale',
  `subscription_key` varchar(256) DEFAULT NULL COMMENT '订阅关键字: 艺人名/城市/价格区间',
  `subscription_value` text DEFAULT NULL COMMENT '订阅值JSON',
  `enabled` tinyint(1) DEFAULT 1 COMMENT '是否启用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  KEY `idx_sub_user_type` (`user_id`,`subscription_type`),
  KEY `idx_sub_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户订阅偏好表';

-- 7. 知识库文档管理增强表 (扩展现有RAG文档管理)
CREATE TABLE IF NOT EXISTS `d_ai_knowledge_base_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `version_id` varchar(128) NOT NULL COMMENT '版本ID',
  `doc_uid` varchar(128) DEFAULT NULL COMMENT '文档UID (NULL表示全局)',
  `version_number` int NOT NULL DEFAULT 1 COMMENT '版本号',
  `content` mediumtext COMMENT '版本内容',
  `change_summary` varchar(500) DEFAULT NULL COMMENT '变更摘要',
  `changed_by` bigint DEFAULT NULL COMMENT '变更人',
  `publish_status` varchar(16) DEFAULT 'draft' COMMENT '发布状态: draft/pending_review/published/rejected',
  `reviewed_by` bigint DEFAULT NULL COMMENT '审核人',
  `review_comment` text DEFAULT NULL COMMENT '审核意见',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_id` (`version_id`),
  KEY `idx_kb_version_doc` (`doc_uid`,`version_number`),
  KEY `idx_kb_version_status` (`publish_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档版本表';

-- 8. 反馈自动分析表
CREATE TABLE IF NOT EXISTS `d_ai_feedback_analysis` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `analysis_id` varchar(128) NOT NULL COMMENT '分析ID',
  `feedback_id` varchar(128) DEFAULT NULL COMMENT '关联反馈ID',
  `run_id` varchar(128) DEFAULT NULL COMMENT '关联Run ID',
  `analysis_type` varchar(32) NOT NULL COMMENT '分析类型: BAD_CASE_CLUSTER/KNOWLEDGE_GAP/SENTIMENT_DRIFT',
  `issue_summary` varchar(500) DEFAULT NULL COMMENT '问题摘要',
  `suggested_action` varchar(256) DEFAULT NULL COMMENT '建议行动',
  `action_taken` varchar(256) DEFAULT NULL COMMENT '已采取行动',
  `action_status` varchar(32) DEFAULT 'PENDING' COMMENT '行动状态: PENDING/IN_PROGRESS/RESOLVED/DISMISSED',
  `cluster_key` varchar(256) DEFAULT NULL COMMENT '聚类键(用于同类问题聚合)',
  `affected_feedback_count` int DEFAULT 1 COMMENT '影响的反馈数',
  `resolved_by` bigint DEFAULT NULL COMMENT '处理人',
  `resolved_at` datetime DEFAULT NULL COMMENT '处理时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `edit_time` datetime DEFAULT NULL COMMENT '编辑时间',
  `status` tinyint(1) DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_analysis_id` (`analysis_id`),
  KEY `idx_fa_feedback` (`feedback_id`),
  KEY `idx_fa_cluster` (`cluster_key`),
  KEY `idx_fa_status` (`action_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反馈自动分析表';
