package com.vsm.okno.web;

import com.vsm.okno.requests.RequestDto;
import com.vsm.okno.requests.SourceVersionService;
import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.util.UUID;

@RestController
@Profile("database")
@RequestMapping("/api/v1/scenarios/{id}")
public class E3SourceFactsController {
    private final SourceVersionService versions;
    public E3SourceFactsController(SourceVersionService versions) { this.versions=versions; }
    @PostMapping("/source-fact-versions")
    public ResponseEntity<RequestDto.Receipt> update(@PathVariable UUID id,@RequestBody RequestDto.Command command,Principal actor) {
        if (!id.equals(command.scenarioId())) throw new PlanningService.InvalidRequestException("scenarioId","must match the exact version in the path");
        return ResponseEntity.status(HttpStatus.CREATED).body(versions.submitE3Facts(command,actor.getName()));
    }
    @GetMapping("/urgent-work-rules")
    public JsonNode rules(@PathVariable UUID id) {
        JsonNode rules=versions.source(id).path("urgentWorkRules");
        return rules.isMissingNode()?new ObjectMapper().createArrayNode():rules;
    }
}
