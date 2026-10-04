package com.sakuradata.media.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "storage_roots")
public class StorageRoot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String path;

    @Column(name = "allow_write", nullable = false)
    private boolean allowWrite = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "order_index")
    private int orderIndex = 0;

    @Column(name = "device_node")
    private String deviceNode;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public StorageRoot() {}

    public StorageRoot(String name, String path, boolean allowWrite, boolean enabled, int orderIndex) {
        this.name = name;
        this.path = path;
        this.allowWrite = allowWrite;
        this.enabled = enabled;
        this.orderIndex = orderIndex;
        this.createdAt = LocalDateTime.now();
    }

    public StorageRoot(String name, String path, boolean allowWrite, boolean enabled, int orderIndex, String deviceNode) {
        this(name, path, allowWrite, enabled, orderIndex);
        this.deviceNode = deviceNode;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(boolean allowWrite) {
        this.allowWrite = allowWrite;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getDeviceNode() {
        return deviceNode;
    }

    public void setDeviceNode(String deviceNode) {
        this.deviceNode = deviceNode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
