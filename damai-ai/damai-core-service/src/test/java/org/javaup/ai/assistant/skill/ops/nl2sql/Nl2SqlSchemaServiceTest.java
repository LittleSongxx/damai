package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Nl2SqlSchemaServiceTest {

    @Test
    void shouldRetrieveRelevantOrderSchema() {
        Nl2SqlSchemaService schemaService = new Nl2SqlSchemaService(new Nl2SqlProperties());

        Nl2SqlSchemaContext context = schemaService.retrieve("今天订单量和支付成功率怎么样");

        assertFalse(context.tables().isEmpty());
        assertTrue(context.tables().stream().anyMatch(table -> "v_order_daily_summary".equals(table.getName())));
        assertTrue(context.formattedSchema().contains("pay_success_rate"));
    }

    @Test
    void shouldExposeAllowedTableNames() {
        Nl2SqlSchemaService schemaService = new Nl2SqlSchemaService(new Nl2SqlProperties());

        assertTrue(schemaService.allowedTableNames().contains("v_api_call_stats"));
    }
}
