package com.vsm.okno.web;

import com.vsm.okno.data.E3AssessmentService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Profile("database")
@RequestMapping("/api/v1/source-snapshots")
public final class E3AssessmentController {
    private final E3AssessmentService service;

    public E3AssessmentController(E3AssessmentService service) { this.service = service; }

    @GetMapping("/{id}/e3-assessment")
    public E3AssessmentService.Assessment assess(@PathVariable UUID id) { return service.assess(id); }
}
