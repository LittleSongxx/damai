package org.javaup.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class PiiDetector {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    public List<String> detect(String text) {
        List<String> findings = new ArrayList<>();
        if (text == null || text.isEmpty()) return findings;

        if (PHONE_PATTERN.matcher(text).find()) {
            findings.add("PII:phone_number");
        }
        if (ID_CARD_PATTERN.matcher(text).find()) {
            findings.add("PII:id_card");
        }
        if (EMAIL_PATTERN.matcher(text).find()) {
            findings.add("PII:email");
        }
        if (BANK_CARD_PATTERN.matcher(text).find()) {
            findings.add("PII:bank_card");
        }
        return findings;
    }

    public String mask(String text) {
        if (text == null) return null;
        String masked = PHONE_PATTERN.matcher(text).replaceAll("1**********");
        masked = ID_CARD_PATTERN.matcher(masked).replaceAll("****");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("****@****.***");
        masked = BANK_CARD_PATTERN.matcher(masked).replaceAll("****");
        return masked;
    }
}
