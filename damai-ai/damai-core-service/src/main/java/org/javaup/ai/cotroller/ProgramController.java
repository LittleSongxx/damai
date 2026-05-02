package org.javaup.ai.cotroller;

import org.javaup.ai.ai.function.call.ProgramCall;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.compat.LegacyAssistantCompatibilityService;
import org.javaup.ai.dto.ProgramDetailDto;
import org.javaup.ai.security.AiPermissionService;
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

/**
 * 兼容旧版三助手入口，内部统一转接到新的 Assistant Runtime。
 */
@RestController
@RequestMapping("/program")
public class ProgramController {

    private final ProgramCall programCall;
    private final LegacyAssistantCompatibilityService legacyCompatibilityService;
    private final AiPermissionService aiPermissionService;

    public ProgramController(ProgramCall programCall,
                             LegacyAssistantCompatibilityService legacyCompatibilityService,
                             AiPermissionService aiPermissionService) {
        this.programCall = programCall;
        this.legacyCompatibilityService = legacyCompatibilityService;
        this.aiPermissionService = aiPermissionService;
    }

    @RequestMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestParam("prompt") String prompt,
                                              @RequestParam("chatId") String chatId) {
        return legacyCompatibilityService.streamLegacyRun(prompt, chatId, AssistantRouteType.BUSINESS);
    }

    @RequestMapping(value = "/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> rag(@RequestParam("prompt") String prompt,
                                             @RequestParam("chatId") String chatId) {
        return legacyCompatibilityService.streamLegacyRun(prompt, chatId, AssistantRouteType.KNOWLEDGE);
    }

    @RequestMapping(value = "/chat/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatMcp(@RequestParam("prompt") String prompt,
                                                 @RequestParam("chatId") String chatId) {
        aiPermissionService.requireOpsAccess();
        return legacyCompatibilityService.streamLegacyRun(prompt, chatId, AssistantRouteType.OPS);
    }

    @PostMapping(value = "/search")
    public List<ProgramSearchVo> search(@RequestBody ProgramSearchFunctionDto programSearchFunctionDto) {
        return programCall.search(programSearchFunctionDto);
    }

    @PostMapping(value = "/detail")
    public ProgramDetailResultVo search(@RequestBody ProgramDetailDto programDetailDto) {
        return programCall.detail(programDetailDto);
    }
}
