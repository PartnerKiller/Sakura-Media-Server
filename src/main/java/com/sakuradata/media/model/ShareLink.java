package com.sakuradata.media.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "share_links", indexes = {
    @Index(name = "idx_share_code", columnList = "code", unique = true),
    @Index(name = "idx_share_user_id", columnList = "user_id")
})
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "is_directory")
    private Boolean isDirectory = false;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "creator_username")
    private String creatorUsername;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "download_count", nullable = false)
    private Long downloadCount = 0L;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    public ShareLink() {}

    public ShareLink(String code, String filePath, String fileName, Long fileSize, Boolean isDirectory, Long userId, String creatorUsername, LocalDateTime expiresAt) {
        this.code = code;
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.isDirectory = isDirectory != null ? isDirectory : false;
        this.userId = userId;
        this.creatorUsername = creatorUsername;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
        this.downloadCount = 0L;
        this.isActive = true;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCreatorUsername() {
        return creatorUsername;
    }

    public void setCreatorUsername(String creatorUsername) {
        this.creatorUsername = creatorUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getDownloadCount() {
        return downloadCount;
    }

    public void setDownloadCount(Long downloadCount) {
        this.downloadCount = downloadCount;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
