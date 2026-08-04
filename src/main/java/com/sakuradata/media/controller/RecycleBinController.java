package com.sakuradata.media.controller;

import com.sakuradata.media.model.RecycleItem;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.RecycleItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/recycle-bin")
public class RecycleBinController {

    @Autowired
    private RecycleItemRepository recycleItemRepository;

    @GetMapping
    public ResponseEntity<?> getRecycleBinItems(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<RecycleItem> items = recycleItemRepository.findByUserId(user.getId());
        return ResponseEntity.ok(items);
    }

    @PostMapping("/restore/{id}")
    public ResponseEntity<?> restoreItem(HttpServletRequest request, @PathVariable Long id) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<RecycleItem> itemOpt = recycleItemRepository.findById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Item not found"));
        }

        RecycleItem item = itemOpt.get();
        if (!item.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
        }

        File src = new File(item.getTempPath());
        File dest = new File(item.getOriginalPath());

        // Ensure parent directories exist
        File destParent = dest.getParentFile();
        if (destParent != null && !destParent.exists()) {
            destParent.mkdirs();
        }

        if (src.renameTo(dest)) {
            recycleItemRepository.delete(item);

            // Broadcast change event for destination parent path
            String parentPath = dest.getParentFile() != null ? dest.getParentFile().getAbsolutePath().replace("\\", "/") : "";
            SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", parentPath));

            return ResponseEntity.ok(Map.of("success", true));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to restore item. Target path may be occupied or unwritable."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePermanently(HttpServletRequest request, @PathVariable Long id) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<RecycleItem> itemOpt = recycleItemRepository.findById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Item not found"));
        }

        RecycleItem item = itemOpt.get();
        if (!item.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
        }

        File file = new File(item.getTempPath());
        if (file.exists()) {
            deleteRecursively(file);
        }

        recycleItemRepository.delete(item);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/clean")
    public ResponseEntity<?> cleanRecycleBin(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<RecycleItem> items = recycleItemRepository.findByUserId(user.getId());
        for (RecycleItem item : items) {
            File file = new File(item.getTempPath());
            if (file.exists()) {
                deleteRecursively(file);
            }
            recycleItemRepository.delete(item);
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        file.delete();
    }
}
