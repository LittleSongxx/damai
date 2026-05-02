package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.vo.AssistantProgramDetailView;
import org.javaup.ai.vo.AssistantTicketCategoryView;
import org.javaup.ai.vo.ProgramDetailVo;
import org.javaup.ai.vo.TicketCategoryVo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantResponsePolicyServiceTest {

    private final AssistantResponsePolicyService responsePolicyService = new AssistantResponsePolicyService();

    @Test
    void shouldSanitizeTicketInventoryIntoAvailabilityOnly() {
        TicketCategoryVo soldOut = new TicketCategoryVo();
        soldOut.setId(1L);
        soldOut.setIntroduce("看台");
        soldOut.setPrice(new BigDecimal("380"));
        soldOut.setRemainNumber(0L);
        soldOut.setTotalNumber(200L);

        TicketCategoryVo lowStock = new TicketCategoryVo();
        lowStock.setId(2L);
        lowStock.setIntroduce("内场");
        lowStock.setPrice(new BigDecimal("880"));
        lowStock.setRemainNumber(3L);
        lowStock.setTotalNumber(100L);

        TicketCategoryVo available = new TicketCategoryVo();
        available.setId(3L);
        available.setIntroduce("VIP");
        available.setPrice(new BigDecimal("1280"));
        available.setRemainNumber(32L);
        available.setTotalNumber(60L);

        ProgramDetailVo detailVo = new ProgramDetailVo();
        detailVo.setId(99L);
        detailVo.setTitle("测试演出");
        detailVo.setTicketCategoryVoList(List.of(soldOut, lowStock, available));

        AssistantProgramDetailView safe = responsePolicyService.sanitizeProgramDetail(detailVo);

        assertEquals(3, safe.getTicketCategories().size());
        AssistantTicketCategoryView first = safe.getTicketCategories().get(0);
        AssistantTicketCategoryView second = safe.getTicketCategories().get(1);
        AssistantTicketCategoryView third = safe.getTicketCategories().get(2);

        assertEquals("SOLD_OUT", first.getAvailabilityStatus());
        assertFalse(first.getAvailable());
        assertEquals("LOW_STOCK", second.getAvailabilityStatus());
        assertTrue(second.getAvailable());
        assertEquals("AVAILABLE", third.getAvailabilityStatus());
        assertTrue(third.getAvailable());
    }

    @Test
    void shouldKeepSafeProgramFieldsOnly() {
        ProgramDetailVo detailVo = new ProgramDetailVo();
        detailVo.setId(7L);
        detailVo.setTitle("安全节目");
        detailVo.setActor("歌手");
        detailVo.setPlace("场馆");

        AssistantProgramDetailView safe = responsePolicyService.sanitizeProgramDetail(detailVo);

        assertEquals(7L, safe.getId());
        assertEquals("安全节目", safe.getTitle());
        assertEquals("歌手", safe.getActor());
        assertEquals("场馆", safe.getPlace());
        assertNull(safe.getShowTime());
    }
}
