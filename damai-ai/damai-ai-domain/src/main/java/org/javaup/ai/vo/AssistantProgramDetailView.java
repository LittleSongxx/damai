package org.javaup.ai.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class AssistantProgramDetailView {

    private Long id;

    private String title;

    private String actor;

    private String place;

    private String areaName;

    private Date showTime;

    private List<AssistantTicketCategoryView> ticketCategories;
}
