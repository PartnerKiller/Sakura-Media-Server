package com.sakuradata.media.model;

import jakarta.persistence.*;

@Entity
@Table(name = "firewall_rules")
public class FirewallRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int port;

    @Column(nullable = false)
    private String protocol; // "tcp", "udp", "both"

    @Column(nullable = false)
    private String action; // "allow", "deny"

    @Column(name = "source_ip")
    private String sourceIp;

    public FirewallRule() {}

    public FirewallRule(int port, String protocol, String action, String sourceIp) {
        this.port = port;
        this.protocol = protocol;
        this.action = action;
        this.sourceIp = sourceIp;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }
}
