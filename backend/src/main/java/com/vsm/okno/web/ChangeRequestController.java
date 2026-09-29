package com.vsm.okno.web;

import com.vsm.okno.requests.RequestDto;
import com.vsm.okno.requests.SourceVersionService;
import com.vsm.okno.requests.UiRequestService;
import com.vsm.okno.service.PlanningService;
import com.vsm.okno.store.DatabasePlanningRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;

import java.security.Principal;
import java.util.*;
import java.util.concurrent.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1")
public class ChangeRequestController {
    private final SourceVersionService versions;
    private final DatabasePlanningRepository planning;
    private final UiRequestService ui;
    private final Map<SseEmitter,Cursor> clients=new ConcurrentHashMap<>();
    private final ScheduledExecutorService notifications=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"request-notifications"); t.setDaemon(true); return t;
    });
    private static final class Cursor { long after; long heartbeat; Cursor(long after) { this.after=after; } }
    public ChangeRequestController(SourceVersionService versions,DatabasePlanningRepository planning,UiRequestService ui) {
        this.versions=versions; this.planning=planning; this.ui=ui;
        notifications.scheduleWithFixedDelay(this::notifyClients,1,1,TimeUnit.SECONDS);
    }
    @PostMapping("/scenarios/{id}/requests")
    public ResponseEntity<UiRequestService.ChangeRequest> uiSubmit(@PathVariable UUID id,@RequestBody UiRequestService.NewRequest request,Principal actor) {
        var submitted=ui.submit(id,request,actor.getName());
        return ResponseEntity.status(submitted.replay()?HttpStatus.OK:HttpStatus.CREATED).body(submitted.request());
    }
    @GetMapping("/requests")
    public List<UiRequestService.ChangeRequest> uiRequests(@RequestParam(required=false) UUID scenarioId,@RequestParam(defaultValue="100") int limit) {
        return ui.list(scenarioId,limit);
    }
    @GetMapping("/requests/{id}") public UiRequestService.ChangeRequest uiRequest(@PathVariable UUID id) { return ui.get(id); }
    @PostMapping("/change-requests")
    public ResponseEntity<RequestDto.Receipt> submit(@RequestBody RequestDto.Command request,Principal actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(versions.submit(request,actor.getName(),false));
    }
    @PostMapping("/scenarios/{id}/rule-versions")
    public ResponseEntity<RequestDto.Receipt> rules(@PathVariable UUID id,@RequestBody RequestDto.Command request,Principal actor) {
        if (!id.equals(request.scenarioId())) throw new PlanningService.InvalidRequestException("scenarioId","must match the path");
        return ResponseEntity.status(HttpStatus.CREATED).body(versions.submit(request,actor.getName(),true));
    }
    @GetMapping("/change-requests")
    public List<RequestDto.Receipt> requests(@RequestParam(required=false) UUID scenarioId,@RequestParam(defaultValue="100") int limit) {
        return versions.requests(scenarioId,limit);
    }
    @GetMapping("/change-requests/{id}") public RequestDto.Receipt request(@PathVariable UUID id) { return versions.receipt(id); }
    @GetMapping("/change-requests/{id}/history") public List<RequestDto.Update> history(@PathVariable UUID id) { return versions.history(id); }
    @GetMapping("/change-requests/updates") public RequestDto.Updates updates(@RequestParam(defaultValue="0") long after) { return versions.updates(after); }
    @GetMapping("/scenarios/{id}/version") public RequestDto.Version head(@PathVariable UUID id) { return versions.head(id); }
    @GetMapping("/scenarios/{id}/versions") public List<RequestDto.Version> versions(@PathVariable UUID id) { return versions.versions(id); }
    @GetMapping("/scenarios/{id}/source") public JsonNode source(@PathVariable UUID id) { return versions.source(id); }
    @GetMapping("/scenarios/{id}/plan-selection") public RequestDto.Selection selection(@PathVariable UUID id) { return planning.selection(id); }

    @GetMapping(value="/change-requests/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue="0") long after,@RequestHeader(value="Last-Event-ID",required=false) String lastId) {
        long cursor=after;
        if (lastId!=null) try { cursor=Long.parseLong(lastId); } catch (NumberFormatException e) { throw new PlanningService.InvalidRequestException("Last-Event-ID","must be an integer"); }
        if (cursor<0) throw new PlanningService.InvalidRequestException("after","must be nonnegative");
        var emitter=new SseEmitter(300_000L); clients.put(emitter,new Cursor(cursor));
        emitter.onCompletion(() -> clients.remove(emitter)); emitter.onTimeout(() -> clients.remove(emitter)); emitter.onError(e -> clients.remove(emitter));
        return emitter;
    }
    private void notifyClients() {
        for (var entry:clients.entrySet()) {
            var emitter=entry.getKey(); var cursor=entry.getValue();
            try {
                var updates=versions.updates(cursor.after);
                for (var event:updates.events()) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("request-status").data(event));
                    cursor.after=event.sequence();
                }
                if (System.currentTimeMillis()-cursor.heartbeat>15_000) {
                    emitter.send(SseEmitter.event().comment("keep-alive")); cursor.heartbeat=System.currentTimeMillis();
                }
            } catch (Exception e) { clients.remove(emitter); emitter.completeWithError(e); }
        }
    }
    @PreDestroy void close() { notifications.shutdownNow(); clients.keySet().forEach(SseEmitter::complete); clients.clear(); }
}
