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
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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

        if (!src.exists()) {
            recycleItemRepository.delete(item);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Source file in recycle bin no longer exists"));
        }

        // Ensure parent directories exist
        File destParent = dest.getParentFile();
        if (destParent != null && !destParent.exists()) {
            destParent.mkdirs();
        }

        boolean success = src.renameTo(dest);
        if (!success) {
            try {
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                success = true;
            } catch (Exception e) {
                try {
                    copyRecursively(src.toPath(), dest.toPath());
                    deleteRecursively(src.toPath());
                    success = true;
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        if (success) {
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
            deleteRecursively(file.toPath());
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
                deleteRecursively(file.toPath());
            }
            recycleItemRepository.delete(item);
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            if (!Files.exists(target)) {
                Files.createDirectories(target);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path entry : stream) {
                    copyRecursively(entry, target.resolve(entry.getFileName()));
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        Files.delete(file);
                    } catch (Exception e) {
                        file.toFile().setWritable(true);
                        file.toFile().delete();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    try {
                        Files.delete(dir);
                    } catch (Exception e) {
                        dir.toFile().setWritable(true);
                        dir.toFile().delete();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
    }
}
