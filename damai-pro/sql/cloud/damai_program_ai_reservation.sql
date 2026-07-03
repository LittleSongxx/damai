USE damai_program_0;

CREATE TABLE IF NOT EXISTS `d_program_ai_reservation` (
  `id` bigint NOT NULL COMMENT '主键id',
  `reservation_id` varchar(96) NOT NULL COMMENT 'AI预留业务id',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `program_id` bigint NOT NULL COMMENT '节目id',
  `ticket_category_id` bigint NOT NULL COMMENT '票档id',
  `ticket_count` int NOT NULL COMMENT '购票数量',
  `ticket_user_ids_json` text NOT NULL COMMENT '购票人id快照',
  `purchase_seats_json` text NOT NULL COMMENT '锁定座位快照',
  `identifier_id` bigint NOT NULL COMMENT '库存扣减流水标识',
  `reservation_status` varchar(32) NOT NULL COMMENT 'RESERVED/CONFIRMED/RELEASED/EXPIRED',
  `expires_at` datetime NOT NULL COMMENT '预留过期时间',
  `confirmed_order_number` varchar(64) DEFAULT NULL COMMENT '确认后的订单号',
  `idempotency_key` varchar(160) NOT NULL COMMENT '幂等键',
  `request_hash` varchar(64) DEFAULT NULL COMMENT '请求快照hash',
  `confirm_idempotency_key` varchar(180) DEFAULT NULL COMMENT '确认订单幂等键',
  `failure_category` varchar(32) DEFAULT NULL COMMENT 'TRANSIENT/DETERMINISTIC/SIDE_EFFECT_UNKNOWN',
  `saga_status` varchar(32) DEFAULT NULL COMMENT 'AI reservation saga状态',
  `last_error` varchar(1024) DEFAULT NULL COMMENT '最近错误',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '状态推进/重试次数',
  `source_run_id` varchar(64) DEFAULT NULL COMMENT 'AI run id',
  `source_action_id` varchar(64) DEFAULT NULL COMMENT 'AI action id',
  `released_at` datetime DEFAULT NULL COMMENT '释放时间',
  `release_reason` varchar(255) DEFAULT NULL COMMENT '释放原因',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `edit_time` datetime NOT NULL COMMENT '编辑时间',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_reservation_id` (`reservation_id`),
  UNIQUE KEY `uk_ai_reservation_idempotency` (`idempotency_key`),
  KEY `idx_ai_reservation_user_ticket` (`user_id`,`program_id`,`ticket_category_id`,`reservation_status`),
  KEY `idx_ai_reservation_expires` (`expires_at`,`reservation_status`),
  KEY `idx_ai_reservation_saga` (`saga_status`,`edit_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI内部票务预留表';

CREATE TABLE IF NOT EXISTS `d_ops_event_outbox` (
  `id` bigint NOT NULL COMMENT '主键id',
  `event_id` varchar(96) NOT NULL COMMENT '事件id',
  `routing_key` varchar(160) NOT NULL COMMENT 'RabbitMQ routing key',
  `event_type` varchar(96) NOT NULL COMMENT '事件类型',
  `event_json` longtext NOT NULL COMMENT '事件JSON',
  `publish_status` varchar(32) NOT NULL COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '发布重试次数',
  `next_retry_at` datetime NOT NULL COMMENT '下次重试时间',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  `last_error` varchar(1024) DEFAULT NULL COMMENT '最近错误',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `edit_time` datetime NOT NULL COMMENT '编辑时间',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '1:正常 0:删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ops_event_outbox_event` (`event_id`),
  KEY `idx_ops_event_outbox_status_retry` (`publish_status`,`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维事件Outbox表';

ALTER TABLE `d_program_ai_reservation`
  ADD COLUMN IF NOT EXISTS `request_hash` varchar(64) DEFAULT NULL COMMENT '请求快照hash' AFTER `idempotency_key`,
  ADD COLUMN IF NOT EXISTS `confirm_idempotency_key` varchar(180) DEFAULT NULL COMMENT '确认订单幂等键' AFTER `request_hash`,
  ADD COLUMN IF NOT EXISTS `failure_category` varchar(32) DEFAULT NULL COMMENT 'TRANSIENT/DETERMINISTIC/SIDE_EFFECT_UNKNOWN' AFTER `confirm_idempotency_key`,
  ADD COLUMN IF NOT EXISTS `saga_status` varchar(32) DEFAULT NULL COMMENT 'AI reservation saga状态' AFTER `failure_category`,
  ADD COLUMN IF NOT EXISTS `last_error` varchar(1024) DEFAULT NULL COMMENT '最近错误' AFTER `saga_status`,
  ADD COLUMN IF NOT EXISTS `retry_count` int NOT NULL DEFAULT 0 COMMENT '状态推进/重试次数' AFTER `last_error`;
