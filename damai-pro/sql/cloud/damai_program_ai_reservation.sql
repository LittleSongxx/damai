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
  KEY `idx_ai_reservation_expires` (`expires_at`,`reservation_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI内部票务预留表';
