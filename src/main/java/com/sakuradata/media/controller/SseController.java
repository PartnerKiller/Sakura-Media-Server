package com.sakuradata.media.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/events")
public class SseController {

    private static final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        // Send a keepalive comment every 15 seconds to prevent Cloudflare/proxy timeouts
        heartbeatScheduler.scheduleWithFixedDelay(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Throwable t) {
                    emitters.remove(emitter);
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(86_400_000L); // 24 hours
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((e) -> emitters.remove(emitter));

        // Send initial keep-alive comment
        try {
            emitter.send(SseEmitter.event().comment("con"));
        } catch (Throwable e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    public static void broadcast(String eventName, Object data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            json = "{}";
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (Throwable t) {
                emitters.remove(emitter);
            }
        }
    }
}
