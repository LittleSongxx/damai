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
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(?![\\d.])");
    private static final Pattern PHYSICAL_ADDRESS_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fff]{2,10}(省|市|区|县|镇|乡|路|街|巷|道|弄)[\\u4e00-\\u9fff0-9]{2,20}(号|楼|层|栋|单元|室|幢)");
    private static final Pattern PASSPORT_PATTERN = Pattern.compile(
            "(?i)(E\\d{7,8}|G\\d{7,8}|[Pp]\\d{7})");

    // Chinese name: surname (top ~50) + 1-2 given-name characters
    private static final Pattern CHINESE_NAME_PATTERN = Pattern.compile(
            "[王李张刘陈杨黄赵周吴徐孙马胡朱郭何罗高林郑梁谢唐许冯宋韩邓彭曹曾田萧潘袁蔡蒋余于杜叶程魏苏吕丁任卢姚沈钟姜崔谭陆范汪廖石金贾夏韦付方白邹孟熊秦邱江尹薛阎段雷侯龙史陶黎贺顾毛郝龚邵万]"
            + "[\\u4e00-\\u9fff]{1,2}");

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
        if (IPV4_PATTERN.matcher(text).find()) {
            findings.add("PII:ip_address");
        }
        if (PHYSICAL_ADDRESS_PATTERN.matcher(text).find()) {
            findings.add("PII:physical_address");
        }
        if (PASSPORT_PATTERN.matcher(text).find()) {
            findings.add("PII:passport_number");
        }
        if (CHINESE_NAME_PATTERN.matcher(text).find()) {
            findings.add("PII:chinese_name");
        }
        return findings;
    }

    public String mask(String text) {
        if (text == null) return null;
        String masked = PHONE_PATTERN.matcher(text).replaceAll("1**********");
        masked = ID_CARD_PATTERN.matcher(masked).replaceAll("****");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("****@****.***");
        masked = BANK_CARD_PATTERN.matcher(masked).replaceAll("****");
        masked = IPV4_PATTERN.matcher(masked).replaceAll("***.***.***.***");
        masked = PHYSICAL_ADDRESS_PATTERN.matcher(masked).replaceAll("****");
        masked = PASSPORT_PATTERN.matcher(masked).replaceAll("****");
        masked = CHINESE_NAME_PATTERN.matcher(masked).replaceAll("***");
        return masked;
    }
}
