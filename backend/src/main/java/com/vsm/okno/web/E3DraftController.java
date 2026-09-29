package com.vsm.okno.web;

import com.vsm.okno.data.E3FullDraftService;
import com.vsm.okno.planning.E3JointFullPlanner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Profile("database")
@RequestMapping("/api/v1/source-snapshots")
public final class E3DraftController {
    private final E3FullDraftService service;

    public E3DraftController(E3FullDraftService service) { this.service = service; }

    @PostMapping("/{id}/e3-full-draft")
    public E3FullDraftService.DraftResult calculate(@PathVariable UUID id,
            @RequestBody E3JointFullPlanner.Input input, Authentication authentication) {
        return service.calculate(id, input, authentication.getName());
    }
}
