package com.vsm.okno.web;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.service.PlanningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final PlanningService service;

    public ApiController(PlanningService service) {
        this.service = service;
    }

    @PostMapping("/scenarios/import")
    public ResponseEntity<Dto.ImportResponse> importScenario(@RequestBody(required = false) Dto.ImportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.importScenario(request));
    }

    @GetMapping("/scenarios/{id}")
    public Dto.Scenario getScenario(@PathVariable UUID id) {
        return service.getScenario(id);
    }

    @GetMapping("/scenarios/{id}/trains")
    public List<Dto.Train> getTrains(@PathVariable UUID id) {
        return service.getTrains(id);
    }

    @PostMapping("/scenarios/{id}/events")
    public ResponseEntity<Map<String, UUID>> addEvent(@PathVariable UUID id, @RequestBody Dto.ScenarioEvent event) {
        UUID newScenarioId = service.newScenarioVersion(id, event);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("newScenarioId", newScenarioId));
    }

    @PostMapping("/planning-jobs")
    public ResponseEntity<Dto.JobStatus> createJob(@RequestBody Dto.JobRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.createJob(request));
    }

    @GetMapping("/planning-jobs/{id}")
    public Dto.JobStatus getJob(@PathVariable UUID id) {
        return service.getJob(id);
    }

    @GetMapping("/plans/{id}")
    public Dto.Plan getPlan(@PathVariable UUID id) {
        return service.getPlan(id);
    }

    @PostMapping("/plans/{id}/approve")
    public Dto.Plan approve(@PathVariable UUID id, @RequestBody Dto.ApproveRequest request) {
        return service.approve(id, request);
    }

    @GetMapping(value = "/plans/{id}/export", produces = "text/csv")
    public ResponseEntity<String> export(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body(service.exportCsv(id));
    }
}
