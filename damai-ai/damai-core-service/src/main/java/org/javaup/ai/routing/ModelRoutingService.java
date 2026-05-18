package org.javaup.ai.routing;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ModelRoutingService {

    private static final Set<String> TRIVIAL_PATTERNS = Set.of(
            "你好", "您好", "hello", "hi", "谢谢", "感谢", "再见", "拜拜",
            "你是谁", "你能做什么", "帮助", "help"
    );

    private static final Set<String> CLARIFICATION_PATTERNS = Set.of(
            "第一个", "第二个", "第三个", "选这个", "就这个",
            "是的", "对的", "可以", "好的", "行", "嗯", "对",
            "选项", "哪一个", "什么意思"
    );

    private static final int SHORT_QUERY_MAX_LENGTH = 15;
    private static final int SIMPLE_QUERY_MAX_LENGTH = 50;

    private final String cheapModel;
    private final String primaryModel;

    public ModelRoutingService(
            @org.springframework.beans.factory.annotation.Value("${damai.ai.routing.cheap-model:qwen-turbo-latest}") String cheapModel,
            @org.springframework.beans.factory.annotation.Value("${damai.ai.routing.primary-model:qwen3.6-plus}") String primaryModel) {
        this.cheapModel = cheapModel;
        this.primaryModel = primaryModel;
    }

    public ModelRoutingDecision route(String userMessage, String conversationContext) {
        String normalized = userMessage == null ? "" : userMessage.trim();

        if (StrUtil.isBlank(normalized)) {
            return ModelRoutingDecision.cheap(cheapModel, "empty message");
        }

        if (isTrivial(normalized)) {
            return ModelRoutingDecision.cheap(cheapModel, "trivial:greeting_or_ack");
        }

        if (isClarificationResponse(normalized)) {
            return ModelRoutingDecision.cheap(cheapModel, "trivial:clarification_response");
        }

        if (normalized.length() <= SHORT_QUERY_MAX_LENGTH) {
            return ModelRoutingDecision.cheap(cheapModel, "trivial:short_query");
        }

        if (isSimpleLookup(normalized)) {
            return ModelRoutingDecision.cheap(cheapModel, "trivial:simple_lookup");
        }

        return ModelRoutingDecision.primary("complex query routing to primary model");
    }

    public String resolveModelName(ModelRoutingDecision decision) {
        return "primary".equals(decision.selectedModel()) ? primaryModel : cheapModel;
    }

    private boolean isTrivial(String normalized) {
        return TRIVIAL_PATTERNS.stream().anyMatch(normalized::contains);
    }

    private boolean isClarificationResponse(String normalized) {
        if (normalized.length() > SIMPLE_QUERY_MAX_LENGTH) {
            return false;
        }
        return CLARIFICATION_PATTERNS.stream().anyMatch(normalized::contains);
    }

    private boolean isSimpleLookup(String normalized) {
        if (normalized.length() > SIMPLE_QUERY_MAX_LENGTH) {
            return false;
        }
        String lower = normalized.toLowerCase();
        return lower.startsWith("什么是") || lower.startsWith("怎么") || lower.startsWith("如何")
                || lower.startsWith("查询") || lower.startsWith("帮我查");
    }
}
