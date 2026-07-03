package org.javaup.ai.assistant.skill.business;

import lombok.Data;
import org.javaup.ai.dto.ProgramOrderCreateDto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class PurchaseActionSnapshot {

    private Long programId;

    private Long userId;

    private String userMobile;

    private String programTitle;

    private String actor;

    private String cityName;

    private Long ticketCategoryId;

    private BigDecimal ticketCategoryPrice;

    private Integer ticketCount;

    private List<String> ticketUsers;

    private List<Long> ticketUserIds;

    private String previewSummary;

    private String snapshotHash;

    private Date generatedAt;

    private Date expiresAt;

    private String reservationId;

    private Date reservationExpiresAt;

    private Boolean reservationLocked;

    private ProgramOrderCreateDto programOrderCreateDto;
}
