package org.javaup.ai.assistant.skill.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CorrectiveQueryServiceTest {

    private final CorrectiveQueryService service = new CorrectiveQueryService(mock(ChatClient.class));

    @Test
    void shouldCreateMissingSlotRewriteForAmbiguousQuestions() {
        CorrectiveQueryService.CorrectiveQueryPlan plan = service.planAmbiguous(
                "开演前还能退票吗", "缺少开演时间和退票手续费规则");

        assertTrue(plan.query().contains("开演前还能退票吗"));
        assertTrue(plan.query().contains("缺少开演时间和退票手续费规则"));
        assertEquals("缺少开演时间和退票手续费规则", plan.missingInfo());
        assertTrue(plan.reason().contains("missing-slot"));
    }

    @Test
    void shouldCreateStepBackAndReformulatedQueryForIncorrectQuestions() {
        CorrectiveQueryService.CorrectiveQueryPlan plan = service.planIncorrect(
                "退款多久到账", "缺少到账时间规则");

        assertTrue(plan.stepBackQuery().contains("票务退票退款规则"));
        assertTrue(plan.query().contains("退款多久到账"));
        assertTrue(plan.query().contains("缺少到账时间规则"));
        assertTrue(plan.query().contains(plan.stepBackQuery()));
        assertTrue(plan.reason().contains("step-back"));
    }

    @Test
    void shouldUseDomainSpecificStepBackFallbacks() {
        assertEquals("实名购票 观演人证件 入场核验规则", service.stepBackQuery("身份证丢了能入场吗", ""));
        assertEquals("电子票二维码 入场检票 凭证规则", service.stepBackQuery("二维码打不开怎么办", ""));
        assertEquals("订单支付 扣款 退款 支付超时规则", service.stepBackQuery("支付成功但没订单", ""));
    }
}
