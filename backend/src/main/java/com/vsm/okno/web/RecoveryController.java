package com.vsm.okno.web;

import com.vsm.okno.operations.RecoveryModel.*;
import com.vsm.okno.operations.RecoveryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.UUID;

@RestController
@Profile("database")
@RequestMapping("/api/v1")
public class RecoveryController {
    private final RecoveryService service;
    public RecoveryController(RecoveryService service) { this.service=service; }
    @PostMapping("/scenarios/{id}/operations") @ResponseStatus(HttpStatus.CREATED)
    public Board create(@PathVariable UUID id,Principal actor) { return service.create(id,actor.getName()); }
    @GetMapping("/operations/{id}")
    public Board get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/operations/{id}/failures")
    public Board failure(@PathVariable UUID id,@RequestBody FailureRequest request,Principal actor) { return service.failure(id,request,actor.getName()); }
    @PostMapping("/operations/{id}/replacements")
    public Board replace(@PathVariable UUID id,@RequestBody ReplacementRequest request,Principal actor) { return service.replace(id,request,actor.getName()); }
    @PostMapping("/operations/{id}/releases")
    public Board release(@PathVariable UUID id,@RequestBody ReleaseRequest request,Principal actor) { return service.release(id,request,actor.getName()); }
    @PostMapping("/operations/{id}/clock")
    public Board clock(@PathVariable UUID id,@RequestBody ClockRequest request,Principal actor) { return service.clock(id,request,actor.getName()); }
}
