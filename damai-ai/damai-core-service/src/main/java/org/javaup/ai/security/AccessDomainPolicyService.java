package org.javaup.ai.security;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AccessDomainPolicyService {

    public AccessDomain resolve(String uri) {
        String path = normalize(uri);
        if (path.startsWith("/actuator") || path.startsWith("/admin/")) {
            return AccessDomain.ADMIN_AI_GOVERNANCE;
        }
        if (path.startsWith("/assistant/admin/customer-service") || path.startsWith("/admin/analytics")
                || path.startsWith("/admin/escalations")) {
            return AccessDomain.ADMIN_CUSTOMER_OPS;
        }
        if (path.startsWith("/assistant/admin/aiops") || path.startsWith("/ai/enhance/observability")) {
            return AccessDomain.ADMIN_OBSERVABILITY;
        }
        if (path.startsWith("/api/nl2sql-eval")) {
            return AccessDomain.ADMIN_DATA_QUERY;
        }
        if (path.startsWith("/api/rag-eval") || path.startsWith("/ai/rag/")
                || path.startsWith("/admin/faq") || path.startsWith("/admin/knowledge")
                || path.startsWith("/api/feedback/knowledge-gaps")) {
            return AccessDomain.ADMIN_KNOWLEDGE_GOVERNANCE;
        }
        if (path.startsWith("/api/prompt-versions") || path.startsWith("/assistant/evals/")
                || path.startsWith("/assistant/admin/")) {
            return AccessDomain.ADMIN_AI_GOVERNANCE;
        }
        if (path.startsWith("/ai/enhance/structured/")) {
            return AccessDomain.INTERNAL_DEV;
        }
        if (path.startsWith("/program/")) {
            return AccessDomain.CUSTOMER_TICKETING;
        }
        if (path.startsWith("/assistant/customer-service") || path.startsWith("/api/feedback")
                || path.startsWith("/api/notifications") || path.startsWith("/chat/")) {
            return AccessDomain.CUSTOMER_SUPPORT;
        }
        if (path.startsWith("/assistant/runs") || path.startsWith("/assistant/conversations")
                || path.startsWith("/assistant/capabilities") || path.startsWith("/assistant/skills")) {
            return AccessDomain.CUSTOMER_SUPPORT;
        }
        return AccessDomain.CUSTOMER_SUPPORT;
    }

    public boolean requiresAdmin(String uri) {
        return resolve(uri).adminOnly();
    }

    public boolean isInternalDev(String uri) {
        return resolve(uri) == AccessDomain.INTERNAL_DEV;
    }

    public boolean isAdminRateLimited(String uri) {
        return requiresAdmin(uri);
    }

    private String normalize(String uri) {
        if (!StringUtils.hasText(uri)) {
            return "/";
        }
        int queryIndex = uri.indexOf('?');
        String path = queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
        return path.startsWith("/") ? path : "/" + path;
    }
}
