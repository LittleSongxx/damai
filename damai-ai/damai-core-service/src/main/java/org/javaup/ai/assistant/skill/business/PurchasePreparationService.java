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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchasePreparationService {

    private final ProgramQueryService programQueryService;
    private final UserCall userCall;
    private final AssistantRunService assistantRunService;

    public AssistantActionPreviewVo prepare(CreateOrderFunctionDto request) {
        if (request.getMobile() != null && !request.getMobile().equals(AiRequestContextHolder.getRequiredUser().getMobile())) {
            throw new RuntimeException("当前登录用户手机号与提交的手机号不一致");
        }

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

        Map<String, Object> previewData = new HashMap<>(8);
        previewData.put("programTitle", programDetailVo.getTitle());
        previewData.put("actor", programDetailVo.getActor());
        previewData.put("cityName", programDetailVo.getAreaName());
        previewData.put("ticketCategoryPrice", request.getTicketCategoryPrice());
        previewData.put("ticketCount", request.getTicketCount());
        previewData.put("ticketUsers", matchedUsers.stream().map(TicketUserVo::getRelName).toList());
        previewData.put("programOrderCreateDto", orderCreateDto);

        String runId = AiRequestContextHolder.getOptional().map(context -> context.getRunId()).orElseThrow(() -> new RuntimeException("缺少 run 上下文"));
        AiAction action = assistantRunService.createAction(runId, AssistantActionType.PURCHASE_APPROVAL, previewData);

        AssistantActionPreviewVo previewVo = new AssistantActionPreviewVo();
        previewVo.setActionRequired(true);
        previewVo.setActionId(action.getActionId());
        previewVo.setActionType(action.getActionType());
        previewVo.setPreviewSummary(String.format("节目《%s》, 票价%s, 数量%s, 购票人%s。请确认后再正式创建订单。",
                programDetailVo.getTitle(),
                request.getTicketCategoryPrice(),
                request.getTicketCount(),
                String.join("、", matchedUsers.stream().map(TicketUserVo::getRelName).toList())));
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
}
