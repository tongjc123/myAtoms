package com.deepwistom.atoms.run;

import com.deepwistom.atoms.session.SessionService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/projects/{id}/runs")
public class RunController {
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final RunService runs;
    private final SessionService sessions;
    private final ObjectMapper json;
    private final ExecutorService streamers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "atoms-sse");
        thread.setDaemon(true);
        return thread;
    });

    public RunController(RunService runs, SessionService sessions, ObjectMapper json) {
        this.runs = runs;
        this.sessions = sessions;
        this.json = json;
    }

    @PreDestroy
    void shutdown() { streamers.shutdownNow(); }

    @PostMapping
    public ResponseEntity<RunModels.StartResponse> start(HttpServletRequest request, @PathVariable String id,
            @RequestBody(required = false) RunModels.StartRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var started = runs.start(sessions.current(request).id(), id, body, key);
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore()).body(started);
    }

    @GetMapping("/latest")
    public RunModels.Snapshot latest(HttpServletRequest request, @PathVariable String id) {
        return runs.latest(sessions.current(request).id(), id);
    }

    @GetMapping("/{runId}")
    public RunModels.Snapshot get(HttpServletRequest request, @PathVariable String id, @PathVariable String runId) {
        return runs.snapshot(sessions.current(request).id(), id, runId);
    }

    @PostMapping("/{runId}/cancel")
    public RunModels.Run cancel(HttpServletRequest request, @PathVariable String id, @PathVariable String runId) {
        return runs.cancel(sessions.current(request).id(), id, runId);
    }

    /** Replays the stored event log first, then follows new rows until the run reaches an end state. */
    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request, @PathVariable String id, @PathVariable String runId,
                             @RequestParam(defaultValue = "0") int after) {
        String owner = sessions.current(request).id();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamers.submit(() -> {
            int cursor = Math.max(0, after);
            try {
                emitter.send(SseEmitter.event().name("open").data("{}"));
                for (int tick = 0; tick < 1200; tick++) {
                    // Read state first: a successful version and its terminal events commit
                    // atomically. Reading an empty batch before a newer state could drop them.
                    String status = runs.run(owner, id, runId).status();
                    List<RunModels.Event> batch = runs.eventsAfter(owner, id, runId, cursor);
                    for (RunModels.Event event : batch) {
                        cursor = event.seq();
                        emitter.send(SseEmitter.event().id(String.valueOf(event.seq())).name(event.type())
                                .data(json.writeValueAsString(Map.of(
                                        "seq", event.seq(), "type", event.type(), "data", event.data())),
                                        MediaType.APPLICATION_JSON));
                    }
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status) && batch.isEmpty()) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(250);
                }
                emitter.complete();
            } catch (IOException closed) {
                emitter.completeWithError(closed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }
}
