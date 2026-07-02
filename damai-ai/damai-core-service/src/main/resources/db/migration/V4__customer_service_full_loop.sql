ALTER TABLE d_ai_purchase_reservation_action
    ADD COLUMN order_number VARCHAR(64) NULL AFTER release_reason,
    ADD COLUMN failure_message VARCHAR(512) NULL AFTER order_number;

CREATE TABLE IF NOT EXISTS d_ai_customer_work_item_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    work_item_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NULL,
    event_payload_json JSON NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_customer_work_item_event (event_id),
    KEY idx_ai_customer_work_item_event_item (work_item_id, create_time),
    KEY idx_ai_customer_work_item_event_type (event_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_customer_knowledge_gap (
    id BIGINT NOT NULL AUTO_INCREMENT,
    gap_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(96) NOT NULL,
    conversation_id VARCHAR(64) NULL,
    user_id BIGINT NULL,
    intent_code VARCHAR(64) NULL,
    issue_category VARCHAR(64) NULL,
    question VARCHAR(1024) NULL,
    evidence_json JSON NULL,
    gap_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    draft_id VARCHAR(64) NULL,
    eval_case_id VARCHAR(64) NULL,
    operator_id VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_customer_knowledge_gap (gap_id),
    KEY idx_ai_customer_knowledge_gap_source (source_type, source_id),
    KEY idx_ai_customer_knowledge_gap_status (gap_status, status),
    KEY idx_ai_customer_knowledge_gap_intent (intent_code, issue_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
