package com.vsm.okno.web;

import com.vsm.okno.requests.RequestReviewService;
import com.vsm.okno.requests.ScheduleImportService;
import com.vsm.okno.requests.UiRequestService;
import com.vsm.okno.store.DatabasePlanningRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

// Planner proposes a change, dispatcher approves or rejects it (see RequestReviewService);
// planner uploads a schedule CSV as a new source version (see ScheduleImportService).
@RestController
@Profile("database")
@RequestMapping("/api/v1")
public class RequestReviewController {
    private final RequestReviewService reviews;
    private final ScheduleImportService schedules;
    private final DatabasePlanningRepository plans;

    public RequestReviewController(RequestReviewService reviews, ScheduleImportService schedules, DatabasePlanningRepository plans) {
        this.reviews = reviews; this.schedules = schedules; this.plans = plans;
    }

    public record LastPlan(UUID planId) {}

    @GetMapping("/scenarios/{id}/last-plan")
    public LastPlan lastPlan(@PathVariable UUID id) { return new LastPlan(plans.lastPlan(id)); }

    @PostMapping(value = "/scenarios/{id}/schedule", consumes = {"text/csv", "text/plain"}) @ResponseStatus(HttpStatus.CREATED)
    public ScheduleImportService.Result schedule(@PathVariable UUID id, @RequestBody String csv, Principal actor) {
        return schedules.importCsv(id, csv, actor.getName());
    }

    @PostMapping("/scenarios/{id}/proposals") @ResponseStatus(HttpStatus.CREATED)
    public RequestReviewService.Proposal propose(@PathVariable UUID id, @RequestBody UiRequestService.NewRequest request, Principal actor) {
        return reviews.propose(id, request, actor.getName());
    }

    @GetMapping("/proposals")
    public List<RequestReviewService.Proposal> list(@RequestParam(required = false) UUID scenarioId) {
        return reviews.list(scenarioId);
    }

    @PostMapping("/proposals/{id}/approve")
    public RequestReviewService.Proposal approve(@PathVariable UUID id, @RequestBody(required = false) RequestReviewService.Decision decision, Principal actor) {
        return reviews.approve(id, decision, actor.getName());
    }

    @PostMapping("/proposals/{id}/reject")
    public RequestReviewService.Proposal reject(@PathVariable UUID id, @RequestBody RequestReviewService.Decision decision, Principal actor) {
        return reviews.reject(id, decision, actor.getName());
    }
}
