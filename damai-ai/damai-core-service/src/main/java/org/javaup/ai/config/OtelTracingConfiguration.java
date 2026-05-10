package org.javaup.ai.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "damai.ai.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OtelTracingConfiguration {

    @Bean
    public OpenTelemetry openTelemetry(AiTracingProperties properties) {
        Resource resource = Resource.getDefault().toBuilder()
                .put("service.name", properties.getServiceName())
                .build();

        OtlpGrpcSpanExporter otlpExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(properties.getOtlpEndpoint())
                .build();

        SdkTracerProvider tracerProvider;
        if (properties.isLogExporterEnabled()) {
            tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
                    .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                    .build();
        } else {
            tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
                    .build();
        }

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @Bean
    public Tracer aiTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("damai-ai", "1.0.0");
    }
}
