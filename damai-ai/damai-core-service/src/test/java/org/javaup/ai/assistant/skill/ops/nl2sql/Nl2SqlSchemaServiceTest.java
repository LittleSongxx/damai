package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.service.Nl2SqlSemanticCatalogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Nl2SqlSchemaServiceTest {

    private final CacheManager cacheManager = mock(CacheManager.class);

    @Test
    void shouldRetrieveRelevantOrderSchema() {
        Nl2SqlSchemaService schemaService = schemaService();

        Nl2SqlSchemaContext context = schemaService.retrieve("今天订单量和支付成功率怎么样");

        assertFalse(context.tables().isEmpty());
        assertTrue(context.tables().stream().anyMatch(table -> "v_order_daily_summary".equals(table.getName())));
        assertTrue(context.formattedSchema().contains("pay_success_rate"));
    }

    @Test
    void shouldExposeAllowedTableNames() {
        Nl2SqlSchemaService schemaService = schemaService();

        assertTrue(schemaService.allowedTableNames().contains("v_api_call_stats"));
    }

    @Test
    void shouldNotReuseFilteredSchemaAcrossDifferentQuestions() {
        Nl2SqlSchemaService schemaService = schemaService();

        schemaService.retrieve("今天订单量和支付成功率怎么样", "admin:1");
        schemaService.retrieve("接口错误率和平均耗时怎么样", "admin:1");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cacheManager, times(2)).putNl2sqlSchema(keyCaptor.capture(), org.mockito.ArgumentMatchers.any());

        assertNotEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));
    }

    private Nl2SqlSchemaService schemaService() {
        Nl2SqlSemanticCatalogService catalogService = mock(Nl2SqlSemanticCatalogService.class);
        when(catalogService.activeSnapshot()).thenReturn(Nl2SqlTestCatalog.snapshot());
        return new Nl2SqlSchemaService(new Nl2SqlProperties(), cacheManager, catalogService);
    }
}
