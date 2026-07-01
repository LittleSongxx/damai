package org.javaup.ai.controller;

import org.javaup.ai.ai.function.call.ProgramCall;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantRuntimeService;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.dto.ProgramDetailDto;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.javaup.ai.vo.ProgramSearchVo;
import org.javaup.ai.vo.result.ProgramDetailResultVo;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/program")
public class ProgramController {

    private final ProgramCall programCall;
    private final AssistantRuntimeService assistantRuntimeService;
    private final AiPermissionService aiPermissionService;

    public ProgramController(ProgramCall programCall,
                             AssistantRuntimeService assistantRuntimeService,
                             AiPermissionService aiPermissionService) {
        this.programCall = programCall;
        this.assistantRuntimeService = assistantRuntimeService;
        this.aiPermissionService = aiPermissionService;
    }

    @RequestMapping(value = "/chat/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatMcp(@RequestParam("prompt") String prompt,
                                                 @RequestParam("chatId") String chatId) {
        aiPermissionService.requireOpsAccess();
        return streamRun(prompt, chatId, AssistantRouteType.OPS);
    }

    @PostMapping(value = "/search")
    public List<ProgramSearchVo> search(@RequestBody ProgramSearchFunctionDto programSearchFunctionDto) {
        return programCall.search(programSearchFunctionDto);
    }

    @PostMapping(value = "/detail")
    public ProgramDetailResultVo search(@RequestBody ProgramDetailDto programDetailDto) {
        return programCall.detail(programDetailDto);
    }

    private Flux<ServerSentEvent<String>> streamRun(String prompt, String chatId, AssistantRouteType routeType) {
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setChatId(chatId);
        request.setMessage(prompt);
        request.setClientContext(Map.of("routeHint", routeType.getCode()));
        AssistantRunCreatedVo createdVo = assistantRuntimeService.createRun(request);
        return assistantRuntimeService.streamRun(createdVo.getRunId());
    }
}
