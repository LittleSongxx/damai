package org.javaup.ai.security;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AccessDomainPolicyService {

    public AccessDomain resolve(String uri) {
        String path = normalize(uri);
        if (path.startsWith("/actuator")) {
            return AccessDomain.ADMIN_AI_GOVERNANCE;
        }
        if (path.startsWith("/assistant/admin/customer-service/feedback/knowledge-gaps")) {
            return AccessDomain.ADMIN_KNOWLEDGE_GOVERNANCE;
        }
        if (path.startsWith("/assistant/admin/customer-service")) {
            return AccessDomain.ADMIN_CUSTOMER_OPS;
        }
        if (path.startsWith("/assistant/admin/ops") || path.startsWith("/assistant/admin/observability")) {
            return AccessDomain.ADMIN_OBSERVABILITY;
        }
        if (path.startsWith("/assistant/admin/dataops") || path.startsWith("/assistant/admin/nl2sql-eval")) {
            return AccessDomain.ADMIN_DATA_QUERY;
        }
        if (path.startsWith("/assistant/admin/rag-eval") || path.startsWith("/assistant/admin/knowledge")
                || path.startsWith("/assistant/admin/faq")) {
            return AccessDomain.ADMIN_KNOWLEDGE_GOVERNANCE;
        }
        if (path.startsWith("/assistant/admin/prompt-versions") || path.startsWith("/assistant/evals/")
                || path.startsWith("/assistant/admin/")) {
            return AccessDomain.ADMIN_AI_GOVERNANCE;
        }
        if (path.startsWith("/assistant/customer-service") || path.startsWith("/assistant/notifications")) {
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
