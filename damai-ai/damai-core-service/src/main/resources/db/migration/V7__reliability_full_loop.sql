ALTER TABLE d_ai_purchase_reservation_action
    ADD COLUMN saga_status VARCHAR(32) NULL AFTER failure_message,
    ADD COLUMN failure_category VARCHAR(32) NULL AFTER saga_status,
    ADD COLUMN compensation_status VARCHAR(32) NULL AFTER failure_category,
    ADD COLUMN confirm_attempt_id VARCHAR(160) NULL AFTER compensation_status,
    ADD COLUMN last_checked_at DATETIME NULL AFTER confirm_attempt_id,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER last_checked_at,
    ADD COLUMN request_hash VARCHAR(64) NULL AFTER retry_count,
    ADD KEY idx_ai_purchase_saga (saga_status, compensation_status, last_checked_at),
    ADD KEY idx_ai_purchase_confirm_attempt (confirm_attempt_id);

CREATE TABLE IF NOT EXISTS d_ai_ops_event_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(96) NOT NULL,
    consume_status VARCHAR(32) NOT NULL,
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    duplicate_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_ops_event_inbox_event (event_id),
    KEY idx_ai_ops_event_inbox_status (consume_status, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
