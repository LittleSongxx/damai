package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.vo.AssistantProgramDetailView;
import org.javaup.ai.vo.AssistantTicketCategoryView;
import org.javaup.ai.vo.ProgramDetailVo;
import org.javaup.ai.vo.TicketCategoryVo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AssistantResponsePolicyService {

    public AssistantProgramDetailView sanitizeProgramDetail(ProgramDetailVo detailVo) {
        AssistantProgramDetailView safe = new AssistantProgramDetailView();
        safe.setId(detailVo.getId());
        safe.setTitle(detailVo.getTitle());
        safe.setActor(detailVo.getActor());
        safe.setPlace(detailVo.getPlace());
        safe.setAreaName(detailVo.getAreaName());
        safe.setShowTime(detailVo.getShowTime());
        safe.setTicketCategories(toSafeTicketCategories(detailVo.getTicketCategoryVoList()));
        return safe;
    }

    public List<AssistantTicketCategoryView> toSafeTicketCategories(List<TicketCategoryVo> ticketCategoryVoList) {
        if (ticketCategoryVoList == null) {
            return List.of();
        }
        return ticketCategoryVoList.stream()
                .map(ticket -> AssistantTicketCategoryView.builder()
                        .id(ticket.getId())
                        .introduce(ticket.getIntroduce())
                        .price(ticket.getPrice())
                        .availabilityStatus(availabilityStatus(ticket.getRemainNumber()))
                        .available(ticket.getRemainNumber() != null && ticket.getRemainNumber() > 0)
                        .build())
                .toList();
    }

    private String availabilityStatus(Long remainNumber) {
        if (remainNumber == null) {
            return "UNKNOWN";
        }
        if (remainNumber <= 0) {
            return "SOLD_OUT";
        }
        if (remainNumber <= 10) {
            return "LOW_STOCK";
        }
        return "AVAILABLE";
    }
}
