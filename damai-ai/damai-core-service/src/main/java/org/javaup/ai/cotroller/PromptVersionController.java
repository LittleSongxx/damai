package org.javaup.ai.cotroller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiPromptVersion;
import org.javaup.ai.service.PromptVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prompt-versions")
@RequiredArgsConstructor
public class PromptVersionController {

    private final PromptVersionService promptVersionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String promptKey) {
        List<AiPromptVersion> versions = promptVersionService.list(promptKey);
        return ResponseEntity.ok(Map.of("code", 0, "data", versions));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String promptKey = (String) body.get("promptKey");
        String template = (String) body.get("template");
        String description = (String) body.get("description");
        Long userId = body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : null;
        AiPromptVersion version = promptVersionService.create(promptKey, template, description, userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", version));
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, Object> body) {
        String promptKey = (String) body.get("promptKey");
        Integer version = (Integer) body.get("version");
        promptVersionService.activate(promptKey, version);
        return ResponseEntity.ok(Map.of("code", 0, "message", "activated"));
    }

    @PostMapping("/invalidate-cache")
    public ResponseEntity<Map<String, Object>> invalidateCache() {
        promptVersionService.invalidateAll();
        return ResponseEntity.ok(Map.of("code", 0, "message", "cache invalidated"));
    }
}
