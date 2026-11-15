package com.fitnesscoaching.platform.modules.measurement.adapter.in.web;

import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.MetricDefinitionResponse;
import com.fitnesscoaching.platform.modules.measurement.application.service.MetricCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricCatalogController {

    private final MetricCatalogService metricCatalogService;

    public MetricCatalogController(MetricCatalogService metricCatalogService) {
        this.metricCatalogService = metricCatalogService;
    }

    @GetMapping
    public ResponseEntity<List<MetricDefinitionResponse>> listMetrics() {
        return ResponseEntity.ok(metricCatalogService.listActiveMetrics());
    }
}
