package org.javaup.ai.assistant.profile;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiUserProfile;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.AiUserProfileMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantUserProfileService {

    private static final int RECENT_RUN_LIMIT = 12;
    private static final int SUMMARY_LIMIT = 800;

    private final AiUserProfileMapper profileMapper;
    private final AiRunMapper runMapper;
    private final OpenAiChatModel chatModel;

    public AssistantUserProfileContext load(Long userId) {
        AiUserProfile profile = latestProfile(userId);
        if (profile == null || (!StringUtils.hasText(profile.getProfileSummary()) && !StringUtils.hasText(profile.getPreferenceTagsJson()))) {
            return AssistantUserProfileContext.empty();
        }
        return new AssistantUserProfileContext(profile.getProfileSummary(), profile.getPreferenceTagsJson(), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshAfterRun(AiRun completedRun) {
        if (completedRun == null || completedRun.getUserId() == null || !StringUtils.hasText(completedRun.getRunId())) {
            return;
        }
        List<AiRun> runs = runMapper.selectList(Wrappers.lambdaQuery(AiRun.class)
                .eq(AiRun::getUserId, completedRun.getUserId())
                .eq(AiRun::getStatus, 1)
                .orderByDesc(AiRun::getCreateTime)
                .last("limit " + RECENT_RUN_LIMIT));
        if (runs.isEmpty()) {
            return;
        }
        AiUserProfile profile = latestProfile(completedRun.getUserId());
        if (profile == null) {
            profile = new AiUserProfile();
            profile.setUserId(completedRun.getUserId());
            profile.setVersion(1);
            profile.setStatus(1);
            fillProfile(profile, completedRun, runs);
            profileMapper.insert(profile);
            return;
        }
        profile.setVersion(profile.getVersion() == null ? 1 : profile.getVersion() + 1);
        fillProfile(profile, completedRun, runs);
        profileMapper.updateById(profile);
    }

    private AiUserProfile latestProfile(Long userId) {
        if (userId == null) {
            return null;
        }
        return profileMapper.selectOne(Wrappers.lambdaQuery(AiUserProfile.class)
                .eq(AiUserProfile::getUserId, userId)
                .eq(AiUserProfile::getStatus, 1)
                .orderByDesc(AiUserProfile::getId)
                .last("limit 1"));
    }

    private void fillProfile(AiUserProfile profile, AiRun completedRun, List<AiRun> runs) {
        Set<String> tags = new LinkedHashSet<>();
        StringBuilder summary = new StringBuilder();
        StringBuilder allMessages = new StringBuilder();
        for (AiRun run : runs) {
            collectTags(run.getUserMessage(), tags);
            if (StringUtils.hasText(run.getUserMessage())) {
                appendSummary(summary, run.getUserMessage());
                allMessages.append(run.getUserMessage()).append("\n");
            }
        }
        Set<String> llmTags = extractTagsWithLLM(allMessages.toString());
        if (!llmTags.isEmpty()) {
            tags.addAll(llmTags);
        }
        profile.setLatestCoveredRunId(completedRun.getRunId());
        profile.setPreferenceTagsJson(JSON.toJSONString(tags));
        profile.setProfileSummary(summary.isEmpty() ? "暂无稳定偏好" : summary.toString());
    }

    private Set<String> extractTagsWithLLM(String messages) {
        if (!StringUtils.hasText(messages) || messages.length() < 10) {
            return Set.of();
        }
        try {
            String result = ChatClient.builder(chatModel).build().prompt()
                    .user("""
                            根据以下用户消息，提取用户偏好标签。标签格式为 "类型:值"，例如 "城市:北京"、"偏好:演唱会"、"关注:退票规则"、"票档:VIP"、"时间:周末"、"艺人:周杰伦"。
                            最多返回10个标签，用英文逗号分隔，不要其他格式。

                            用户消息：
                            %s
                            """.formatted(messages.length() > 2000 ? messages.substring(0, 2000) : messages))
                    .call()
                    .content();
            if (StringUtils.hasText(result)) {
                return Arrays.stream(result.split(","))
                        .map(String::trim)
                        .filter(t -> t.contains(":") || t.contains("："))
                        .map(t -> t.replace("：", ":"))
                        .limit(10)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        } catch (Exception ex) {
            log.warn("LLM 偏好标签提取失败，仅使用关键词标签", ex);
        }
        return Set.of();
    }

    private void appendSummary(StringBuilder summary, String message) {
        if (summary.length() >= SUMMARY_LIMIT) {
            return;
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        if (summary.length() > 0) {
            summary.append("；");
        }
        summary.append(normalized);
    }

    private void collectTags(String text, Set<String> tags) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String value = text.toLowerCase();
        addIfContains(value, tags, "北京", "城市:北京");
        addIfContains(value, tags, "上海", "城市:上海");
        addIfContains(value, tags, "演唱会", "偏好:演唱会");
        addIfContains(value, tags, "脱口秀", "偏好:脱口秀");
        addIfContains(value, tags, "话剧", "偏好:话剧");
        addIfContains(value, tags, "退票", "关注:退票规则");
        addIfContains(value, tags, "vip", "票档:VIP");
        addIfContains(value, tags, "周末", "时间:周末");
    }

    private void addIfContains(String value, Set<String> tags, String keyword, String tag) {
        if (value.contains(keyword.toLowerCase())) {
            tags.add(tag);
        }
    }
}
