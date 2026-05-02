package org.javaup.ai.assistant.skill.business;

import cn.hutool.core.collection.CollectionUtil;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.function.call.ProgramCall;
import org.javaup.ai.ai.function.call.TicketCategoryCall;
import org.javaup.ai.ai.function.dto.ProgramRecommendFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.dto.ProgramDetailDto;
import org.javaup.ai.dto.TicketCategoryListByProgramDto;
import org.javaup.ai.vo.AssistantProgramDetailView;
import org.javaup.ai.vo.ProgramDetailVo;
import org.javaup.ai.vo.ProgramSearchVo;
import org.javaup.ai.vo.TicketCategoryDetailVo;
import org.javaup.ai.vo.TicketCategoryVo;
import org.javaup.ai.vo.result.ProgramDetailResultVo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProgramQueryService {

    private final ProgramCall programCall;
    private final TicketCategoryCall ticketCategoryCall;
    private final AssistantResponsePolicyService responsePolicyService;

    public List<ProgramSearchVo> recommendPrograms(ProgramRecommendFunctionDto request) {
        return programCall.recommendList(request);
    }

    public List<ProgramSearchVo> searchPrograms(ProgramSearchFunctionDto request) {
        return programCall.search(request);
    }

    public AssistantProgramDetailView getProgramDetail(ProgramSearchFunctionDto request) {
        ProgramDetailVo detailVo = getProgramDetailRaw(request);
        return detailVo == null ? null : responsePolicyService.sanitizeProgramDetail(detailVo);
    }

    public ProgramDetailVo getProgramDetailRaw(ProgramSearchFunctionDto request) {
        List<ProgramSearchVo> programs = searchPrograms(request);
        if (CollectionUtil.isEmpty(programs)) {
            return null;
        }
        ProgramDetailDto detailDto = new ProgramDetailDto();
        detailDto.setId(programs.get(0).getId());
        ProgramDetailResultVo detailResultVo = programCall.detail(detailDto);
        if (detailResultVo == null || detailResultVo.getData() == null) {
            return null;
        }
        ProgramDetailVo detailVo = detailResultVo.getData();
        TicketCategoryListByProgramDto ticketRequest = new TicketCategoryListByProgramDto();
        ticketRequest.setProgramId(detailVo.getId());
        List<TicketCategoryDetailVo> ticketDetails = ticketCategoryCall.selectListByProgram(ticketRequest);
        Map<Long, TicketCategoryDetailVo> ticketDetailMap = ticketDetails.stream()
                .collect(Collectors.toMap(TicketCategoryDetailVo::getId, item -> item, (left, right) -> right));
        for (TicketCategoryVo ticketCategoryVo : detailVo.getTicketCategoryVoList()) {
            TicketCategoryDetailVo detail = ticketDetailMap.get(ticketCategoryVo.getId());
            if (Objects.nonNull(detail)) {
                ticketCategoryVo.setRemainNumber(detail.getRemainNumber());
                ticketCategoryVo.setTotalNumber(detail.getTotalNumber());
            }
        }
        return detailVo;
    }
}
