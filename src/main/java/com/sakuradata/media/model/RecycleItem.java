package com.sakuradata.media.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "recycle_items")
public class RecycleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "original_path", nullable = false, length = 1024)
    private String originalPath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "temp_path", nullable = false, length = 1024)
    private String tempPath;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "is_directory")
    private Boolean isDirectory;

    public RecycleItem() {}

    public RecycleItem(Long userId, String originalPath, String fileName, String tempPath, LocalDateTime deletedAt, Long fileSize, Boolean isDirectory) {
        this.userId = userId;
        this.originalPath = originalPath;
        this.fileName = fileName;
        this.tempPath = tempPath;
        this.deletedAt = deletedAt;
        this.fileSize = fileSize;
        this.isDirectory = isDirectory;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getTempPath() {
        return tempPath;
    }

    public void setTempPath(String tempPath) {
        this.tempPath = tempPath;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Boolean getIsDirectory() {
        return isDirectory;
    }

    public void setIsDirectory(Boolean isDirectory) {
        this.isDirectory = isDirectory;
    }
}
