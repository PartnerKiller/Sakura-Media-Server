package com.sakuradata.media.service;

import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class UserActivityService {

    // In-memory cache of userId -> last active epoch millis for zero-overhead tracking
    private final ConcurrentHashMap<Long, Long> lastActiveMap = new ConcurrentHashMap<>();

    // Threshold to consider a user online (active within last 2 minutes / 120,000 ms)
    public static final long ONLINE_THRESHOLD_MS = 120_000L;

    @Autowired
    private UserRepository userRepository;

    private final ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor();

    public UserActivityService() {
        // Periodically flush in-memory active timestamps to database every 60 seconds
        flushScheduler.scheduleWithFixedDelay(this::flushToDatabase, 30, 60, TimeUnit.SECONDS);
    }

    public void recordActivity(Long userId) {
        if (userId != null) {
            lastActiveMap.put(userId, System.currentTimeMillis());
        }
    }

    public boolean isOnline(Long userId) {
        if (userId == null) return false;
        Long lastActive = lastActiveMap.get(userId);
        if (lastActive == null) return false;
        return (System.currentTimeMillis() - lastActive) < ONLINE_THRESHOLD_MS;
    }

    public Long getLastActiveTimestamp(Long userId) {
        if (userId == null) return null;
        return lastActiveMap.get(userId);
    }

    private void flushToDatabase() {
        try {
            long now = System.currentTimeMillis();
            for (var entry : lastActiveMap.entrySet()) {
                Long userId = entry.getKey();
                Long lastMillis = entry.getValue();
                if (now - lastMillis < 300_000L) { // Only update recently active users
                    userRepository.findById(userId).ifPresent(user -> {
                        user.setLastActiveAt(LocalDateTime.now());
                        userRepository.save(user);
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }
}
