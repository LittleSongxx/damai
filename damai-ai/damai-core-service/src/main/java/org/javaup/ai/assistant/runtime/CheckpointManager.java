package org.javaup.ai.assistant.runtime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.mapper.AiRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行断点管理 —— 遵循 LangGraph "durable execution" 设计。
 *
 * <p>LangGraph 核心特性: "agents that persist through failures and can run for
 * extended periods, automatically resuming from exactly where they left off."
 *
 * <p>每个关键阶段完成后保存中间产物快照。Run 失败后可从此处恢复，而非从零开始。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointManager {

    private final AiRunMapper runMapper;

    /**
     * 在关键节点保存可恢复状态。
     *
     * @param runId   Run ID
     * @param stage   当前阶段名（如 RETRIEVAL_COMPLETED, SQL_GENERATED, ACTION_PREVIEWED）
     * @param payload 中间产物（节目信息、SQL、购票预览等）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveCheckpoint(String runId, String stage, Map<String, Object> payload) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("stage", stage);
        checkpoint.put("savedAt", System.currentTimeMillis());
        checkpoint.put("payload", payload);

        runMapper.update(null, Wrappers.lambdaUpdate(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .set(AiRun::getResumableStateJson, JSON.toJSONString(checkpoint)));
        log.debug("Checkpoint saved: runId={}, stage={}", runId, stage);
    }

    /**
     * 尝试加载最近 checkpoint。若存在则构建 ResumeContext，否则返回 null。
     */
    public ResumeContext tryResume(AiRun run) {
        String json = run.getResumableStateJson();
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JSONObject checkpoint = JSON.parseObject(json);
            String stage = checkpoint.getString("stage");
            JSONObject payload = checkpoint.getJSONObject("payload");
            if (!StringUtils.hasText(stage) || payload == null) {
                return null;
            }
            return new ResumeContext(stage, payload);
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint for runId={}: {}", run.getRunId(), e.getMessage());
            return null;
        }
    }

    /**
     * Run 成功完成后清空 checkpoint。
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearCheckpoint(String runId) {
        runMapper.update(null, Wrappers.lambdaUpdate(AiRun.class)
                .eq(AiRun::getRunId, runId)
                .set(AiRun::getResumableStateJson, null));
    }

    /**
     * 从 checkpoint 恢复的上下文。
     */
    public record ResumeContext(String stage, JSONObject payload) {

        public boolean canSkipRouting() {
            return "ROUTED".equals(stage) || "SKILL_STARTED".equals(stage)
                    || "RETRIEVAL_COMPLETED".equals(stage) || "SQL_GENERATED".equals(stage)
                    || "ACTION_PREVIEWED".equals(stage);
        }

        public boolean canSkipRetrieval() {
            return "RETRIEVAL_COMPLETED".equals(stage)
                    || "ACTION_PREVIEWED".equals(stage);
        }

        public String getString(String key) {
            return payload.getString(key);
        }

        public String getString(String key, String defaultValue) {
            String value = payload.getString(key);
            return value == null ? defaultValue : value;
        }

        public Map<String, Object> getMap(String key) {
            JSONObject obj = payload.getJSONObject(key);
            return obj == null ? Map.of() : obj;
        }
    }
}
