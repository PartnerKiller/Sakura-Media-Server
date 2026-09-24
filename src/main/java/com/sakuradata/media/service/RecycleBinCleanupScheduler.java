package com.sakuradata.media.service;

import com.sakuradata.media.model.RecycleItem;
import com.sakuradata.media.repository.RecycleItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class RecycleBinCleanupScheduler {

    @Autowired
    private RecycleItemRepository recycleItemRepository;

    // Run every hour
    @Scheduled(cron = "0 0 * * * *")
    public void cleanupExpiredItems() {
        LocalDateTime limit = LocalDateTime.now().minusDays(3);
        List<RecycleItem> expired = recycleItemRepository.findByDeletedAtBefore(limit);
        for (RecycleItem item : expired) {
            try {
                File file = new File(item.getTempPath());
                boolean deleted = true;
                if (file.exists()) {
                    deleted = deleteRecursively(file);
                }
                if (deleted || !file.exists()) {
                    recycleItemRepository.delete(item);
                } else {
                    System.err.println("Could not delete physical file for expired recycle item: " + item.getTempPath());
                }
            } catch (Exception e) {
                System.err.println("Failed to permanently delete expired recycle item: " + item.getTempPath() + " - " + e.getMessage());
            }
        }
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        return file.delete();
    }
}
