package org.javaup.ai.assistant.skill.business;

import cn.hutool.core.collection.CollectionUtil;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.function.call.UserCall;
import org.javaup.ai.ai.function.dto.CreateOrderFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.assistant.AssistantActionType;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.dto.ProgramOrderCreateDto;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.vo.AssistantActionPreviewVo;
import org.javaup.ai.vo.ProgramDetailVo;
import org.javaup.ai.vo.TicketCategoryVo;
import org.javaup.ai.vo.TicketUserVo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PurchasePreparationService {

    private final ProgramQueryService programQueryService;
    private final UserCall userCall;
    private final AssistantRunService assistantRunService;
    private final PurchaseRiskPolicyService riskPolicyService;

    public AssistantActionPreviewVo prepare(CreateOrderFunctionDto request) {
        riskPolicyService.validatePreviewRequest(request);

        ProgramSearchFunctionDto searchFunctionDto = new ProgramSearchFunctionDto();
        BeanUtils.copyProperties(request, searchFunctionDto);
        ProgramDetailVo programDetailVo = programQueryService.getProgramDetailRaw(searchFunctionDto);
        if (programDetailVo == null) {
            throw new RuntimeException("没有查询到可购买的节目");
        }

        List<TicketUserVo> ticketUsers = userCall.ticketUserList(AiRequestContextHolder.getRequiredUser().getUserId());
        if (CollectionUtil.isEmpty(ticketUsers)) {
            throw new RuntimeException("当前账号没有可用购票人");
        }
        List<TicketUserVo> matchedUsers = matchTicketUsers(ticketUsers, request.getTicketUserNumberList());
        Long ticketCategoryId = matchTicketCategory(programDetailVo, request);

        ProgramOrderCreateDto orderCreateDto = new ProgramOrderCreateDto();
        orderCreateDto.setProgramId(programDetailVo.getId());
        orderCreateDto.setUserId(AiRequestContextHolder.getRequiredUser().getUserId());
        orderCreateDto.setTicketUserIdList(matchedUsers.stream().map(TicketUserVo::getId).toList());
        orderCreateDto.setTicketCategoryId(ticketCategoryId);
        orderCreateDto.setTicketCount(request.getTicketCount());

        String runId = AiRequestContextHolder.getOptional().map(context -> context.getRunId()).orElseThrow(() -> new RuntimeException("缺少 run 上下文"));
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + 15 * 60 * 1000L);
        PurchaseActionSnapshot snapshot = buildSnapshot(request, programDetailVo, matchedUsers, ticketCategoryId, orderCreateDto, now, expiresAt);
        AiAction action = assistantRunService.createAction(
                runId,
                AssistantActionType.PURCHASE_APPROVAL,
                snapshot,
                snapshot.getPreviewSummary(),
                snapshot.getSnapshotHash(),
                buildIdempotencyKey(runId, snapshot.getSnapshotHash()),
                expiresAt
        );

        AssistantActionPreviewVo previewVo = new AssistantActionPreviewVo();
        previewVo.setActionRequired(true);
        previewVo.setActionId(action.getActionId());
        previewVo.setActionType(action.getActionType());
        previewVo.setPreviewSummary(snapshot.getPreviewSummary());
        return previewVo;
    }

    private List<TicketUserVo> matchTicketUsers(List<TicketUserVo> ticketUsers, List<String> requestNumbers) {
        List<TicketUserVo> matches = new ArrayList<>();
        for (String requestNumber : requestNumbers) {
            String normalizedRequest = normalizeIdNumber(requestNumber);
            TicketUserVo matched = ticketUsers.stream()
                    .filter(candidate -> normalizeIdNumber(candidate.getIdNumber()).equalsIgnoreCase(normalizedRequest))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                throw new RuntimeException("购票人证件号码未匹配到实名购票人: " + requestNumber);
            }
            matches.add(matched);
        }
        return matches;
    }

    private Long matchTicketCategory(ProgramDetailVo programDetailVo, CreateOrderFunctionDto request) {
        for (TicketCategoryVo ticketCategoryVo : programDetailVo.getTicketCategoryVoList()) {
            if (request.getTicketCategoryPrice().compareTo(ticketCategoryVo.getPrice()) == 0) {
                return ticketCategoryVo.getId();
            }
        }
        throw new RuntimeException("没有查询到对应票档");
    }

    private String normalizeIdNumber(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private PurchaseActionSnapshot buildSnapshot(CreateOrderFunctionDto request,
                                                 ProgramDetailVo programDetailVo,
                                                 List<TicketUserVo> matchedUsers,
                                                 Long ticketCategoryId,
                                                 ProgramOrderCreateDto orderCreateDto,
                                                 Date generatedAt,
                                                 Date expiresAt) {
        PurchaseActionSnapshot snapshot = new PurchaseActionSnapshot();
        snapshot.setProgramId(programDetailVo.getId());
        snapshot.setUserId(AiRequestContextHolder.getRequiredUser().getUserId());
        snapshot.setUserMobile(AiRequestContextHolder.getRequiredUser().getMobile());
        snapshot.setProgramTitle(programDetailVo.getTitle());
        snapshot.setActor(programDetailVo.getActor());
        snapshot.setCityName(programDetailVo.getAreaName());
        snapshot.setTicketCategoryId(ticketCategoryId);
        snapshot.setTicketCategoryPrice(request.getTicketCategoryPrice());
        snapshot.setTicketCount(request.getTicketCount());
        snapshot.setTicketUsers(matchedUsers.stream().map(TicketUserVo::getRelName).toList());
        snapshot.setTicketUserIds(matchedUsers.stream().map(TicketUserVo::getId).toList());
        snapshot.setGeneratedAt(generatedAt);
        snapshot.setExpiresAt(expiresAt);
        snapshot.setProgramOrderCreateDto(orderCreateDto);
        snapshot.setPreviewSummary(String.format("节目《%s》, 票价%s, 数量%s, 购票人%s。本预览不锁定库存，仅供确认；审批通过后将先预留库存再正式创建订单。",
                programDetailVo.getTitle(),
                request.getTicketCategoryPrice(),
                request.getTicketCount(),
                String.join("、", snapshot.getTicketUsers())));
        snapshot.setSnapshotHash(snapshotHash(snapshot));
        return snapshot;
    }

    private String snapshotHash(PurchaseActionSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = snapshot.getProgramId() + "|" + snapshot.getUserId() + "|" + snapshot.getTicketCategoryId()
                    + "|" + snapshot.getTicketCategoryPrice() + "|" + snapshot.getTicketCount() + "|" + snapshot.getTicketUserIds();
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法生成购票快照签名", ex);
        }
    }

    private String buildIdempotencyKey(String runId, String snapshotHash) {
        String suffix = snapshotHash == null ? "na" : snapshotHash.substring(0, Math.min(16, snapshotHash.length()));
        return "assistant:" + runId + ":" + suffix;
    }
}
