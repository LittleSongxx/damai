package org.javaup.ai.assistant.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具参数 poka-yoke 校验器 —— 遵循 Anthropic ACI 设计原则。
 *
 * <p>"Make it easy to do the right thing and hard to do the wrong thing."
 * 校验失败时返回 LLM 可读的纠正建议，而非仅有错误码。
 *
 * <p>与 {@link ToolGuardrailService} 的区别: 本类关注参数格式/业务约束，
 * Guardrail 关注安全/注入检测。二者互补。
 */
public final class ToolParameterValidator {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^\\d{17}[0-9Xx]$");
    private static final Pattern CHINESE_CITY_PATTERN = Pattern.compile("^[一-龥]{2,10}$");

    private ToolParameterValidator() {
    }

    /**
     * 校验手机号格式。
     *
     * @return 校验错误列表，为空表示通过
     */
    public static List<String> validateMobile(String mobile, String paramName) {
        List<String> errors = new ArrayList<>();
        if (mobile == null || mobile.isBlank()) {
            errors.add(paramName + " 不能为空，请提供用户手机号（11位中国大陆手机号，如 13800138000）");
            return errors;
        }
        String trimmed = mobile.trim();
        if (!MOBILE_PATTERN.matcher(trimmed).matches()) {
            errors.add(paramName + " 格式错误: '" + mobile + "' 不是有效的11位中国大陆手机号（1开头）。请确认号码长度和首位数是否正确");
        }
        return errors;
    }

    /**
     * 校验身份证号格式。
     */
    public static List<String> validateIdCard(String idCard, String paramName) {
        List<String> errors = new ArrayList<>();
        if (idCard == null || idCard.isBlank()) {
            errors.add(paramName + " 不能为空");
            return errors;
        }
        String trimmed = idCard.trim();
        if (!ID_CARD_PATTERN.matcher(trimmed).matches()) {
            errors.add(paramName + " 格式错误: '" + idCard + "' 不是18位身份证号。应为17位数字 + 1位数字或X（如 110101199001011234）");
        }
        return errors;
    }

    /**
     * 校验城市名称格式（中文城市名）。
     */
    public static List<String> validateCityName(String cityName, String paramName) {
        List<String> errors = new ArrayList<>();
        if (cityName == null || cityName.isBlank()) {
            return errors;
        }
        String trimmed = cityName.trim();
        if (!CHINESE_CITY_PATTERN.matcher(trimmed).matches()) {
            errors.add(paramName + " 格式可疑: '" + cityName + "'。请使用标准中文城市名（如「北京」、「上海」）。如果是英文城市名如「San Francisco」，请确认数据库中存储的对应中文名");
        }
        return errors;
    }

    /**
     * 校验艺人名不为空且不含明显非艺人内容。
     */
    public static List<String> validateActorName(String actor, String paramName) {
        List<String> errors = new ArrayList<>();
        if (actor == null || actor.isBlank()) {
            return errors;
        }
        String trimmed = actor.trim();
        if (trimmed.length() > 100) {
            errors.add(paramName + " 过长（超过100字符），请使用简明的艺人名或团体名");
        }
        if (trimmed.matches(".*[<>{}].*")) {
            errors.add(paramName + " 包含非法字符（<>{}），请确认艺人名是否正确");
        }
        return errors;
    }

    /**
     * 校验票档价格是否在合理范围。
     */
    public static List<String> validateTicketPrice(java.math.BigDecimal price, String paramName) {
        List<String> errors = new ArrayList<>();
        if (price == null) {
            errors.add(paramName + " 不能为空，请从 getProgramDetail 返回的票档中选择一个价格");
            return errors;
        }
        if (price.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            errors.add(paramName + " 必须大于0: " + price + "。请从节目详情中获取真实票档价格");
        }
        if (price.compareTo(new java.math.BigDecimal("100000")) > 0) {
            errors.add(paramName + " 异常高: " + price + " 元。请确认是否为正确价格（单位: 元）");
        }
        return errors;
    }

    /**
     * 校验购买数量。
     */
    public static List<String> validateTicketCount(Integer count, int maxCount, String paramName) {
        List<String> errors = new ArrayList<>();
        if (count == null) {
            errors.add(paramName + " 不能为空，请指定购买数量");
            return errors;
        }
        if (count <= 0) {
            errors.add(paramName + " 必须为正整数: " + count + "。购买数量至少为1张");
        }
        if (count > maxCount) {
            errors.add(paramName + " 超过上限: " + count + " > " + maxCount + "。每人每次限购" + maxCount + "张");
        }
        return errors;
    }

    /**
     * 将校验错误列表合并为 LLM 可读的错误消息。
     *
     * <p>消息格式设计为 LLM 可直接解析并用于 self-correction。
     */
    public static String formatErrors(String toolName, List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("工具 [" + toolName + "] 参数校验不通过，请修正后重试:\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 批量校验并抛出含纠正建议的异常。
     *
     * @throws IllegalArgumentException 如果存在校验错误
     */
    public static void validateOrThrow(String toolName, List<String> errors) {
        if (errors != null && !errors.isEmpty()) {
            throw new IllegalArgumentException(formatErrors(toolName, errors));
        }
    }
}
