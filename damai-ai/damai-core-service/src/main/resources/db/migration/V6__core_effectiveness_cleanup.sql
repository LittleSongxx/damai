ALTER TABLE d_ai_purchase_reservation_action
    ADD COLUMN user_id BIGINT NULL AFTER run_id,
    ADD KEY idx_ai_purchase_reservation_user_ticket (user_id, program_id, ticket_category_id, reservation_status, create_time);
