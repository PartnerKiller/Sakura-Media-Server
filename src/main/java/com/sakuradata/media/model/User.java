package com.sakuradata.media.model;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "plain_password")
    private String plainPassword;

    @Column(name = "theme")
    private String theme;

    @Column(name = "ui_style")
    private String uiStyle;

    @Column(name = "profile_picture")
    private String profilePicture;

    @Column(nullable = false)
    private String role; // "admin" or "user"

    @Column(name = "download_bandwidth_limit")
    private Double downloadBandwidthLimit; // in MB/s. null or <= 0 means unlimited.

    @Column(name = "upload_bandwidth_limit")
    private Double uploadBandwidthLimit; // in MB/s. null or <= 0 means unlimited.

    public User() {}

    public User(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public User(String username, String passwordHash, String role, Double downloadBandwidthLimit, Double uploadBandwidthLimit) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.downloadBandwidthLimit = downloadBandwidthLimit;
        this.uploadBandwidthLimit = uploadBandwidthLimit;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Double getDownloadBandwidthLimit() {
        return downloadBandwidthLimit;
    }

    public void setDownloadBandwidthLimit(Double downloadBandwidthLimit) {
        this.downloadBandwidthLimit = downloadBandwidthLimit;
    }

    public Double getUploadBandwidthLimit() {
        return uploadBandwidthLimit;
    }

    public void setUploadBandwidthLimit(Double uploadBandwidthLimit) {
        this.uploadBandwidthLimit = uploadBandwidthLimit;
    }

    public String getPlainPassword() {
        return plainPassword;
    }

    public void setPlainPassword(String plainPassword) {
        this.plainPassword = plainPassword;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public String getUiStyle() {
        return uiStyle;
    }

    public void setUiStyle(String uiStyle) {
        this.uiStyle = uiStyle;
    }

    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }
}
