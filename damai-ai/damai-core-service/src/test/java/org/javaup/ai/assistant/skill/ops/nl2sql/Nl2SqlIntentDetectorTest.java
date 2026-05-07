package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Nl2SqlIntentDetectorTest {

    private final Nl2SqlIntentDetector detector = new Nl2SqlIntentDetector();

    @Test
    void shouldDetectOpsDataQuestions() {
        assertTrue(detector.isNl2SqlQuestion("今天订单量和支付成功率怎么样"));
        assertTrue(detector.isNl2SqlQuestion("哪个接口错误最多"));
        assertTrue(detector.isNl2SqlQuestion("AI 调用 token 和成本趋势"));
    }

    @Test
    void shouldIgnorePlainOpsQuestions() {
        assertFalse(detector.isNl2SqlQuestion("看一下 gateway trace"));
        assertFalse(detector.isNl2SqlQuestion("查一下 damai-ai 服务健康"));
    }
}
