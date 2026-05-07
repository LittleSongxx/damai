package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.ai.function.dto.CreateOrderFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramRecommendFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.structured.StructuredOutputService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BusinessSkillParameterExtractor {

    private static final List<String> CITIES = List.of("北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "重庆", "武汉", "西安", "天津", "苏州");
    private static final Pattern MOBILE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_NUMBER_PATTERN = Pattern.compile("(?i)\\b\\d{15}\\b|\\b\\d{17}[0-9x]\\b");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:元|块|票档|价位|价格)?");
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d+)\\s*(?:张|份|个)");

    private final StructuredOutputService structuredOutputService;
    private final ChatClient extractionChatClient;

    public BusinessSkillParameterExtractor(StructuredOutputService structuredOutputService,
                                           @Qualifier("titleChatClient") ChatClient extractionChatClient) {
        this.structuredOutputService = structuredOutputService;
        this.extractionChatClient = extractionChatClient;
    }

    public ProgramSearchFunctionDto extractSearch(String message) {
        try {
            return structuredOutputService.getStructuredOutput(extractionChatClient, """
                    从用户购票问题中抽取节目查询参数，只抽取确定出现的信息，不要猜测。
                    用户问题：%s
                    """.formatted(message), ProgramSearchFunctionDto.class);
        } catch (RuntimeException ex) {
            ProgramSearchFunctionDto dto = new ProgramSearchFunctionDto();
            dto.setCityName(firstCity(message));
            dto.setActor(extractActor(message));
            return dto;
        }
    }

    public ProgramRecommendFunctionDto extractRecommend(String message) {
        try {
            return structuredOutputService.getStructuredOutput(extractionChatClient, """
                    从用户购票问题中抽取节目推荐参数，只抽取确定出现的信息，不要猜测。
                    用户问题：%s
                    """.formatted(message), ProgramRecommendFunctionDto.class);
        } catch (RuntimeException ex) {
            ProgramRecommendFunctionDto dto = new ProgramRecommendFunctionDto();
            dto.setAreaName(firstCity(message));
            dto.setProgramCategory(extractCategory(message));
            return dto;
        }
    }

    public CreateOrderFunctionDto extractPurchase(String message) {
        try {
            return structuredOutputService.getStructuredOutput(extractionChatClient, """
                    从用户购票问题中抽取下单预览参数。不要补充用户没有明确提供的信息。
                    用户问题：%s
                    """.formatted(message), CreateOrderFunctionDto.class);
        } catch (RuntimeException ex) {
            CreateOrderFunctionDto dto = new CreateOrderFunctionDto();
            dto.setCityName(firstCity(message));
            dto.setActor(extractActor(message));
            dto.setMobile(firstMatch(MOBILE_PATTERN, message));
            dto.setTicketUserNumberList(allMatches(ID_NUMBER_PATTERN, message));
            dto.setTicketCategoryPrice(extractPrice(message));
            dto.setTicketCount(extractCount(message));
            return dto;
        }
    }

    private String firstCity(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return CITIES.stream().filter(message::contains).findFirst().orElse(null);
    }

    private String extractCategory(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        if (message.contains("演唱会")) {
            return "演唱会";
        }
        if (message.contains("脱口秀")) {
            return "脱口秀";
        }
        if (message.contains("话剧")) {
            return "话剧";
        }
        if (message.contains("音乐节")) {
            return "音乐节";
        }
        return null;
    }

    private String extractActor(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String compact = message.replaceAll("\\s+", "");
        for (String city : CITIES) {
            compact = compact.replace(city, "");
        }
        String actor = compact.replaceAll("(我想|我要|帮我|查询|看看|找|搜索|推荐|买|购买|下单|订|两张|一张|三张|演唱会|节目|门票|票|票档|价格|多少钱|详情|时间|在哪|什么时候|周末|最近|下周|今天|明天|的)", "");
        return StringUtils.hasText(actor) && actor.length() <= 20 ? actor : null;
    }

    private String firstMatch(Pattern pattern, String message) {
        Matcher matcher = pattern.matcher(message == null ? "" : message);
        return matcher.find() ? matcher.group() : null;
    }

    private List<String> allMatches(Pattern pattern, String message) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(message == null ? "" : message);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private BigDecimal extractPrice(String message) {
        Matcher matcher = PRICE_PATTERN.matcher(message == null ? "" : message);
        BigDecimal candidate = null;
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw.length() > 6) {
                continue;
            }
            BigDecimal value = new BigDecimal(raw);
            if (value.compareTo(BigDecimal.valueOf(50)) >= 0) {
                candidate = value;
            }
        }
        return candidate;
    }

    private Integer extractCount(String message) {
        Matcher matcher = COUNT_PATTERN.matcher(message == null ? "" : message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }
}
