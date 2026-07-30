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

    @Column(nullable = false)
    private String role; // "admin" or "user"

    @Column(name = "bandwidth_limit")
    private Double bandwidthLimit; // in MB/s. null or <= 0 means unlimited.

    public User() {}

    public User(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public User(String username, String passwordHash, String role, Double bandwidthLimit) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.bandwidthLimit = bandwidthLimit;
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

    public Double getBandwidthLimit() {
        return bandwidthLimit;
    }

    public void setBandwidthLimit(Double bandwidthLimit) {
        this.bandwidthLimit = bandwidthLimit;
    }
}
