package org.javaup.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class DefaultFeedbackClusterer implements FeedbackClusterer {

    @Override
    public String clusterKey(FeedbackClusterInput input) {
        String route = normalize(input == null ? null : input.routeType(), "unknown-route");
        String category = normalize(input == null ? null : input.issueCategory(), "unknown-issue");
        String text = normalize((input == null ? "" : input.userMessage()) + " " + (input == null ? "" : input.feedbackComment()), "");
        String fingerprint = sha256(text).substring(0, 12);
        return route + ":" + category + ":" + fingerprint;
    }

    private String normalize(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }
}
