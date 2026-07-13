package com.sakuradata.media.model;

import jakarta.persistence.*;
import java.util.Date;

@Entity
@Table(name = "cron_jobs")
public class CronJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(nullable = false)
    private String command;

    @Column(nullable = false)
    private String type; // "shell", "python", "docker"

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run")
    private Date lastRun;

    @Column(nullable = true)
    private String status;

    public CronJob() {}

    public CronJob(String name, String cronExpression, String command, String type) {
        this.name = name;
        this.cronExpression = cronExpression;
        this.command = command;
        this.type = type;
        this.enabled = true;
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

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Date getLastRun() {
        return lastRun;
    }

    public void setLastRun(Date lastRun) {
        this.lastRun = lastRun;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
