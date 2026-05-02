package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AssistantTicketCategoryView {

    private Long id;

    private String introduce;

    private BigDecimal price;

    private String availabilityStatus;

    private Boolean available;
}
