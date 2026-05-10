package org.javaup.ai.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiSpanService {

    private final Tracer aiTracer;

    public Span startSpan(String operationName) {
        return aiTracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
    }

    public Span startSpan(String operationName, Span parent) {
        return aiTracer.spanBuilder(operationName)
                .setParent(io.opentelemetry.context.Context.current().with(parent))
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
    }

    public Span startLlmSpan(String operationName, String model) {
        Span span = aiTracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.setAttribute("gen_ai.system", "dashscope");
        span.setAttribute("gen_ai.request.model", model);
        return span;
    }

    public void endSpanSuccess(Span span) {
        if (span != null) {
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    public void endSpanError(Span span, Throwable error) {
        if (span != null) {
            span.setStatus(StatusCode.ERROR, error.getMessage());
            span.recordException(error);
            span.end();
        }
    }

    public void setLlmUsage(Span span, int inputTokens, int outputTokens) {
        if (span != null) {
            span.setAttribute("gen_ai.usage.input_tokens", inputTokens);
            span.setAttribute("gen_ai.usage.output_tokens", outputTokens);
            span.setAttribute("gen_ai.usage.total_tokens", inputTokens + outputTokens);
        }
    }

    public void setRunAttributes(Span span, String runId, String routeType, String skillId) {
        if (span != null) {
            span.setAttribute("damai.run_id", runId);
            if (routeType != null) span.setAttribute("damai.route_type", routeType);
            if (skillId != null) span.setAttribute("damai.skill_id", skillId);
        }
    }
}
