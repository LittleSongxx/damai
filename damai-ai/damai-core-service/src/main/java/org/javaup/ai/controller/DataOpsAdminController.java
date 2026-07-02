package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.entity.AiNl2SqlSemanticCatalog;
import org.javaup.ai.service.Nl2SqlSemanticCatalogService;
import org.javaup.ai.service.OpsMetricAggregationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/assistant/admin/dataops")
public class DataOpsAdminController {

    private final Nl2SqlSemanticCatalogService semanticCatalogService;
    private final OpsMetricAggregationService metricAggregationService;

    @PostMapping("/catalog/reload")
    public ApiResponse<Map<String, Object>> reloadCatalog() {
        Nl2SqlSemanticCatalogService.CatalogSnapshot snapshot = semanticCatalogService.reload();
        return ApiResponse.ok(Map.of(
                "datasourceKey", snapshot.datasourceKey(),
                "schemaVersion", snapshot.schemaVersion(),
                "tableCount", snapshot.tables().size(),
                "termCount", snapshot.terms().size(),
                "exampleCount", snapshot.examples().size()));
    }

    @GetMapping("/catalog")
    public ApiResponse<List<AiNl2SqlSemanticCatalog>> catalog() {
        return ApiResponse.ok(semanticCatalogService.activeRows());
    }

    @PostMapping("/metrics/rebuild")
    public ApiResponse<Map<String, Object>> rebuildMetrics() {
        return ApiResponse.ok(metricAggregationService.rebuildFromRawEvents());
    }
}
