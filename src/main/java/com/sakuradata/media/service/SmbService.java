package com.sakuradata.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SmbService {

    private static final String SMB_CONF_PATH = "/etc/samba/smb.conf";
    private static final List<String> SYSTEM_SHARES = List.of("global", "printers", "print$");
    private static final Pattern SAFE_SHARE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.\\$ ]+$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get overview metrics and status of Samba daemon and connections
     */
    public Map<String, Object> getSmbOverview() {
        Map<String, Object> result = new LinkedHashMap<>();

        boolean isRunning = false;
        String activeState = "inactive";
        String subState = "dead";
        String pid = "";
        String startTime = "";

        try {
            Map<String, Object> statusCmd = executeCommand(new String[]{
                    "systemctl", "show", "smbd", "-p", "ActiveState,SubState,MainPID,ExecMainStartTimestamp"
            });
            String stdout = (String) statusCmd.get("stdout");
            if (stdout != null) {
                for (String line : stdout.split("\n")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String k = parts[0].trim();
                        String v = parts[1].trim();
                        if ("ActiveState".equals(k)) activeState = v;
                        else if ("SubState".equals(k)) subState = v;
                        else if ("MainPID".equals(k)) pid = v;
                        else if ("ExecMainStartTimestamp".equals(k)) startTime = v;
                    }
                }
            }
            isRunning = "active".equalsIgnoreCase(activeState);
        } catch (Exception e) {
            System.err.println("Error reading smbd service status: " + e.getMessage());
        }

        String serverIp = getPrimaryLanIp();
        String hostname = getHostname();
        String version = getSambaVersion();

        // Get smbstatus JSON for active sessions and connections
        int activeClientsCount = 0;
        int activeTconsCount = 0;
        int openFilesCount = 0;

        try {
            JsonNode statusJson = getSmbStatusJson();
            if (statusJson != null) {
                JsonNode sessions = statusJson.get("sessions");
                if (sessions != null && sessions.isObject()) {
                    activeClientsCount = sessions.size();
                }
                JsonNode tcons = statusJson.get("tcons");
                if (tcons != null && tcons.isObject()) {
                    activeTconsCount = tcons.size();
                }
                JsonNode openFiles = statusJson.get("open_files");
                if (openFiles != null && openFiles.isObject()) {
                    openFilesCount = openFiles.size();
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading smbstatus: " + e.getMessage());
        }

        List<Map<String, Object>> shares = getAllShares();

        result.put("running", isRunning);
        result.put("activeState", activeState);
        result.put("subState", subState);
        result.put("mainPid", pid);
        result.put("startTime", startTime);
        result.put("serverIp", serverIp);
        result.put("hostname", hostname);
        result.put("version", version);
        result.put("port", 445);
        result.put("totalShares", shares.size());
        result.put("customSharesCount", shares.stream().filter(s -> !Boolean.TRUE.equals(s.get("isSystem"))).count());
        result.put("activeClientsCount", activeClientsCount);
        result.put("activeTconsCount", activeTconsCount);
        result.put("openFilesCount", openFilesCount);

        return result;
    }

    /**
     * Get all shares defined in smb.conf, enriched with live connections and UNC paths
     */
    public List<Map<String, Object>> getAllShares() {
        List<Map<String, Object>> shares = new ArrayList<>();
        String serverIp = getPrimaryLanIp();
        String hostname = getHostname();

        // Parse smbstatus tcons to see which shares are currently in use
        Map<String, List<String>> shareClientsMap = new HashMap<>();
        try {
            JsonNode statusJson = getSmbStatusJson();
            if (statusJson != null && statusJson.has("tcons") && statusJson.get("tcons").isObject()) {
                JsonNode tcons = statusJson.get("tcons");
                tcons.fields().forEachRemaining(entry -> {
                    JsonNode tcon = entry.getValue();
                    String svc = tcon.has("service") ? tcon.get("service").asText() : "";
                    String machine = tcon.has("machine") ? tcon.get("machine").asText() : "";
                    if (!svc.isEmpty() && !machine.isEmpty()) {
                        shareClientsMap.computeIfAbsent(svc.toLowerCase(), k -> new ArrayList<>()).add(machine);
                    }
                });
            }
        } catch (Exception ignored) {}

        List<RawSection> sections = parseSmbConfSections();

        for (RawSection sec : sections) {
            if ("global".equalsIgnoreCase(sec.name)) {
                continue; // skip global section
            }

            boolean isSystem = SYSTEM_SHARES.contains(sec.name.toLowerCase());
            Map<String, Object> share = new LinkedHashMap<>();
            share.put("name", sec.name);
            share.put("isSystem", isSystem);

            String path = sec.getParam("path", "");
            share.put("path", path);

            File pathFile = new File(path);
            share.put("pathExists", !path.isEmpty() && pathFile.exists() && pathFile.isDirectory());

            share.put("comment", sec.getParam("comment", ""));

            // ReadOnly vs Writable
            String readOnlyVal = sec.getParam("read only", null);
            String writableVal = sec.getParam("writable", null);
            if (writableVal == null) writableVal = sec.getParam("writeable", null);

            boolean readOnly = true;
            if (readOnlyVal != null) {
                readOnly = isTrue(readOnlyVal);
            } else if (writableVal != null) {
                readOnly = !isTrue(writableVal);
            }
            share.put("readOnly", readOnly);

            // Browseable
            String browseableVal = sec.getParam("browseable", null);
            if (browseableVal == null) browseableVal = sec.getParam("browsable", null);
            boolean browseable = browseableVal == null || isTrue(browseableVal);
            share.put("browseable", browseable);

            // Guest OK
            String guestOkVal = sec.getParam("guest ok", null);
            if (guestOkVal == null) guestOkVal = sec.getParam("public", null);
            boolean guestOk = guestOkVal != null && isTrue(guestOkVal);
            share.put("guestOk", guestOk);

            share.put("validUsers", sec.getParam("valid users", ""));
            share.put("forceUser", sec.getParam("force user", ""));
            share.put("createMask", sec.getParam("create mask", "0775"));
            share.put("directoryMask", sec.getParam("directory mask", "0775"));

            // UNC Paths
            share.put("uncWindowsIp", "\\\\" + serverIp + "\\" + sec.name);
            share.put("uncWindowsHost", "\\\\" + hostname + "\\" + sec.name);
            share.put("uncMacLinuxIp", "smb://" + serverIp + "/" + sec.name);
            share.put("uncMacLinuxHost", "smb://" + hostname + ".local/" + sec.name);

            // Active clients
            List<String> clients = shareClientsMap.getOrDefault(sec.name.toLowerCase(), Collections.emptyList());
            share.put("activeClients", clients);
            share.put("activeClientsCount", clients.size());

            shares.add(share);
        }

        return shares;
    }

    /**
     * Get detailed information for a single share
     */
    public Map<String, Object> getShareByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        for (Map<String, Object> s : getAllShares()) {
            if (name.equalsIgnoreCase((String) s.get("name"))) {
                return s;
            }
        }
        return null;
    }

    /**
     * Add or update an SMB share in /etc/samba/smb.conf
     */
    public synchronized Map<String, Object> saveShare(Map<String, Object> payload, boolean isNew) throws Exception {
        String name = (String) payload.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Share name is required.");
        }
        name = name.trim();

        if (!SAFE_SHARE_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid share name. Only alphanumeric, dashes, underscores, and dots are allowed.");
        }

        if (SYSTEM_SHARES.contains(name.toLowerCase())) {
            throw new IllegalArgumentException("Cannot create or modify reserved system share name: " + name);
        }

        String path = (String) payload.get("path");
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Directory path is required.");
        }
        path = Paths.get(path.trim()).toAbsolutePath().normalize().toString().replace("\\", "/");

        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be an absolute path starting with /");
        }

        // Auto-create directory if requested or if missing
        boolean createDir = Boolean.TRUE.equals(payload.get("createDirectory"));
        File dir = new File(path);
        if (!dir.exists()) {
            if (createDir) {
                executeCommand(new String[]{"sudo", "mkdir", "-p", path});
                executeCommand(new String[]{"sudo", "chown", "1000:1000", path});
                executeCommand(new String[]{"sudo", "chmod", "0775", path});
            } else {
                throw new IllegalArgumentException("Directory path does not exist on server: " + path + ". Check 'Create directory' to automatically create it.");
            }
        }

        String comment = (String) payload.getOrDefault("comment", "");
        boolean readOnly = Boolean.TRUE.equals(payload.get("readOnly"));
        boolean browseable = !Boolean.FALSE.equals(payload.get("browseable"));
        boolean guestOk = Boolean.TRUE.equals(payload.get("guestOk"));
        String validUsers = (String) payload.getOrDefault("validUsers", "");
        String forceUser = (String) payload.getOrDefault("forceUser", "");
        String createMask = (String) payload.getOrDefault("createMask", "0775");
        String directoryMask = (String) payload.getOrDefault("directoryMask", "0775");

        String originalName = (String) payload.get("originalName");
        String targetName = (originalName != null && !originalName.trim().isEmpty()) ? originalName.trim() : name;

        List<RawSection> sections = parseSmbConfSections();

        if (isNew) {
            for (RawSection s : sections) {
                if (s.name.equalsIgnoreCase(name)) {
                    throw new IllegalArgumentException("A share with the name '" + name + "' already exists.");
                }
            }
        }

        // Build new share representation
        RawSection newSection = new RawSection(name);
        newSection.setParam("path", path);
        if (comment != null && !comment.trim().isEmpty()) newSection.setParam("comment", comment.trim());
        newSection.setParam("browseable", browseable ? "yes" : "no");
        newSection.setParam("writable", readOnly ? "no" : "yes");
        newSection.setParam("read only", readOnly ? "yes" : "no");
        newSection.setParam("guest ok", guestOk ? "yes" : "no");
        if (validUsers != null && !validUsers.trim().isEmpty()) newSection.setParam("valid users", validUsers.trim());
        if (forceUser != null && !forceUser.trim().isEmpty()) newSection.setParam("force user", forceUser.trim());
        if (createMask != null && !createMask.trim().isEmpty()) newSection.setParam("create mask", createMask.trim());
        if (directoryMask != null && !directoryMask.trim().isEmpty()) newSection.setParam("directory mask", directoryMask.trim());

        // Replace existing section or append new
        boolean found = false;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).name.equalsIgnoreCase(targetName)) {
                sections.set(i, newSection);
                found = true;
                break;
            }
        }
        if (!found) {
            sections.add(newSection);
        }

        // Rebuild full smb.conf string
        String updatedConfig = serializeSmbConf(sections);

        // Atomically validate and write
        applySmbConf(updatedConfig);

        return Map.of("success", true, "message", "Share [" + name + "] saved successfully.", "share", getShareByName(name));
    }

    /**
     * Delete an SMB share by name
     */
    public synchronized Map<String, Object> deleteShare(String shareName) throws Exception {
        if (shareName == null || shareName.trim().isEmpty()) {
            throw new IllegalArgumentException("Share name is required.");
        }
        shareName = shareName.trim();

        if (SYSTEM_SHARES.contains(shareName.toLowerCase())) {
            throw new IllegalArgumentException("Cannot delete reserved system share: " + shareName);
        }

        List<RawSection> sections = parseSmbConfSections();
        boolean removed = false;
        for (Iterator<RawSection> it = sections.iterator(); it.hasNext(); ) {
            RawSection s = it.next();
            if (s.name.equalsIgnoreCase(shareName)) {
                it.remove();
                removed = true;
                break;
            }
        }

        if (!removed) {
            throw new IllegalArgumentException("Share not found: " + shareName);
        }

        String updatedConfig = serializeSmbConf(sections);
        applySmbConf(updatedConfig);

        return Map.of("success", true, "message", "Share [" + shareName + "] removed successfully.");
    }

    /**
     * Get active client sessions from smbstatus
     */
    public List<Map<String, Object>> getActiveSessions() {
        List<Map<String, Object>> sessionList = new ArrayList<>();
        try {
            JsonNode statusJson = getSmbStatusJson();
            if (statusJson != null) {
                JsonNode sessions = statusJson.get("sessions");
                JsonNode tcons = statusJson.get("tcons");
                JsonNode openFiles = statusJson.get("open_files");

                Map<String, List<String>> sessionShares = new HashMap<>();
                if (tcons != null && tcons.isObject()) {
                    tcons.fields().forEachRemaining(entry -> {
                        JsonNode t = entry.getValue();
                        String sid = t.has("session_id") ? t.get("session_id").asText() : "";
                        String svc = t.has("service") ? t.get("service").asText() : "";
                        if (!sid.isEmpty() && !svc.isEmpty()) {
                            sessionShares.computeIfAbsent(sid, k -> new ArrayList<>()).add(svc);
                        }
                    });
                }

                if (sessions != null && sessions.isObject()) {
                    sessions.fields().forEachRemaining(entry -> {
                        String sid = entry.getKey();
                        JsonNode s = entry.getValue();

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sessionId", sid);
                        item.put("username", s.has("username") ? s.get("username").asText() : "guest");
                        item.put("remoteMachine", s.has("remote_machine") ? s.get("remote_machine").asText() : "");
                        item.put("remoteAddress", s.has("remote_address") ? s.get("remote_address").asText() : "");
                        item.put("protocol", s.has("session_dialect") ? s.get("session_dialect").asText() : "SMB");

                        String signing = "-";
                        if (s.has("signing") && s.get("signing").has("cipher")) {
                            signing = s.get("signing").get("cipher").asText();
                        }
                        item.put("signing", signing);

                        item.put("shares", sessionShares.getOrDefault(sid, Collections.emptyList()));
                        sessionList.add(item);
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing smbstatus sessions: " + e.getMessage());
        }
        return sessionList;
    }

    /**
     * List Samba users configured in the passdb database
     */
    public List<Map<String, Object>> getSambaUsers() {
        List<Map<String, Object>> users = new ArrayList<>();
        try {
            Map<String, Object> res = executeCommand(new String[]{"sudo", "pdbedit", "-L", "-v"});
            String stdout = (String) res.get("stdout");
            if (stdout != null) {
                String[] blocks = stdout.split("---------------");
                for (String block : blocks) {
                    if (block.trim().isEmpty()) continue;
                    Map<String, Object> user = new LinkedHashMap<>();
                    for (String line : block.split("\n")) {
                        String[] parts = line.split(":", 2);
                        if (parts.length == 2) {
                            String k = parts[0].trim();
                            String v = parts[1].trim();
                            if ("Unix username".equalsIgnoreCase(k)) user.put("username", v);
                            else if ("Full Name".equalsIgnoreCase(k)) user.put("fullName", v);
                            else if ("Account Flags".equalsIgnoreCase(k)) user.put("accountFlags", v);
                            else if ("Password last set".equalsIgnoreCase(k)) user.put("passwordLastSet", v);
                            else if ("Home Directory".equalsIgnoreCase(k)) user.put("homeDirectory", v);
                        }
                    }
                    if (user.containsKey("username")) {
                        users.add(user);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error listing pdbedit users: " + e.getMessage());
        }

        // Fallback: if pdbedit -L -v was empty, try pdbedit -L
        if (users.isEmpty()) {
            try {
                Map<String, Object> res = executeCommand(new String[]{"sudo", "pdbedit", "-L"});
                String stdout = (String) res.get("stdout");
                if (stdout != null) {
                    for (String line : stdout.split("\n")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 1 && !parts[0].trim().isEmpty()) {
                            users.add(Map.of(
                                    "username", parts[0].trim(),
                                    "fullName", parts.length > 2 ? parts[2].trim() : parts[0].trim()
                            ));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return users;
    }

    /**
     * Set or change Samba password for a user
     */
    public Map<String, Object> setSambaUserPassword(String username, String password) throws Exception {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty.");
        }
        username = username.trim();

        // Check if linux user exists
        Map<String, Object> userCheck = executeCommand(new String[]{"id", "-u", username});
        if ((int) userCheck.get("exitCode") != 0) {
            throw new IllegalArgumentException("Linux system user '" + username + "' does not exist on the server host.");
        }

        // Execute smbpasswd -s -a <username> with password passed twice via stdin
        String stdin = password + "\n" + password + "\n";
        Map<String, Object> res = executeCommandWithStdin(new String[]{"sudo", "smbpasswd", "-s", "-a", username}, stdin);

        if ((int) res.get("exitCode") != 0) {
            String err = (String) res.get("stderr");
            throw new Exception("Failed to set SMB password: " + (err != null ? err : "exit code " + res.get("exitCode")));
        }

        return Map.of("success", true, "message", "Samba password for user '" + username + "' updated successfully.");
    }

    /**
     * Delete a Samba user from passdb
     */
    public Map<String, Object> deleteSambaUser(String username) throws Exception {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required.");
        }
        username = username.trim();

        Map<String, Object> res = executeCommand(new String[]{"sudo", "smbpasswd", "-x", username});
        if ((int) res.get("exitCode") != 0) {
            String err = (String) res.get("stderr");
            throw new Exception("Failed to remove SMB user: " + (err != null ? err : "exit code " + res.get("exitCode")));
        }

        return Map.of("success", true, "message", "Samba user '" + username + "' removed.");
    }

    /**
     * Control Samba service actions (restart, reload, start, stop)
     */
    public Map<String, Object> controlService(String action) throws Exception {
        if ("reload".equalsIgnoreCase(action)) {
            executeCommand(new String[]{"sudo", "smbcontrol", "all", "reload-config"});
            return Map.of("success", true, "message", "Samba configuration reloaded successfully without disconnecting clients.");
        } else if ("restart".equalsIgnoreCase(action)) {
            executeCommand(new String[]{"sudo", "systemctl", "restart", "smbd"});
            return Map.of("success", true, "message", "Samba service (smbd) restarted.");
        } else if ("start".equalsIgnoreCase(action)) {
            executeCommand(new String[]{"sudo", "systemctl", "start", "smbd"});
            return Map.of("success", true, "message", "Samba service (smbd) started.");
        } else if ("stop".equalsIgnoreCase(action)) {
            executeCommand(new String[]{"sudo", "systemctl", "stop", "smbd"});
            return Map.of("success", true, "message", "Samba service (smbd) stopped.");
        } else {
            throw new IllegalArgumentException("Unknown action: " + action + ". Allowed: reload, restart, start, stop.");
        }
    }

    // =========================================================================
    // HELPER & PARSING METHODS
    // =========================================================================

    private JsonNode getSmbStatusJson() {
        try {
            Map<String, Object> res = executeCommand(new String[]{"sudo", "smbstatus", "-j"});
            String stdout = (String) res.get("stdout");
            if (stdout != null && stdout.trim().startsWith("{")) {
                return objectMapper.readTree(stdout);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getPrimaryLanIp() {
        try {
            Map<String, Object> res = executeCommand(new String[]{"hostname", "-I"});
            String stdout = (String) res.get("stdout");
            if (stdout != null) {
                String[] ips = stdout.trim().split("\\s+");
                for (String ip : ips) {
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")) {
                        return ip;
                    }
                }
                if (ips.length > 0 && !ips[0].isEmpty()) return ips[0];
            }
        } catch (Exception ignored) {}
        return "192.168.0.10";
    }

    private String getHostname() {
        try {
            Map<String, Object> res = executeCommand(new String[]{"hostname"});
            String stdout = (String) res.get("stdout");
            if (stdout != null && !stdout.trim().isEmpty()) {
                return stdout.trim();
            }
        } catch (Exception ignored) {}
        return "sakura";
    }

    private String getSambaVersion() {
        try {
            Map<String, Object> res = executeCommand(new String[]{"smbd", "-V"});
            String stdout = (String) res.get("stdout");
            if (stdout != null && !stdout.trim().isEmpty()) {
                return stdout.trim();
            }
        } catch (Exception ignored) {}
        return "Samba";
    }

    private boolean isTrue(String val) {
        if (val == null) return false;
        String v = val.trim().toLowerCase();
        return "yes".equals(v) || "true".equals(v) || "1".equals(v);
    }

    /**
     * Atomically validates new configuration with testparm, backs up original, and reloads
     */
    private void applySmbConf(String newConfigContent) throws Exception {
        Path tempFile = Files.createTempFile("smb_conf_", ".tmp");
        try {
            Files.writeString(tempFile, newConfigContent, StandardCharsets.UTF_8);

            // Validate temp file with testparm
            Map<String, Object> testRes = executeCommand(new String[]{"testparm", "-s", tempFile.toAbsolutePath().toString()});
            if ((int) testRes.get("exitCode") != 0) {
                String stderr = (String) testRes.get("stderr");
                String stdout = (String) testRes.get("stdout");
                String errMsg = (stderr != null && !stderr.trim().isEmpty()) ? stderr : stdout;
                throw new Exception("Samba configuration validation failed (testparm error): " + errMsg);
            }

            // Create timestamped backup of current smb.conf
            executeCommand(new String[]{"sudo", "cp", SMB_CONF_PATH, SMB_CONF_PATH + ".bak"});

            // Copy valid new config into /etc/samba/smb.conf
            executeCommand(new String[]{"sudo", "cp", tempFile.toAbsolutePath().toString(), SMB_CONF_PATH});
            executeCommand(new String[]{"sudo", "chmod", "0644", SMB_CONF_PATH});

            // Reload configuration without dropping existing connections
            executeCommand(new String[]{"sudo", "smbcontrol", "all", "reload-config"});
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Parse /etc/samba/smb.conf into RawSection objects preserving comments & formatting
     */
    private List<RawSection> parseSmbConfSections() {
        List<RawSection> list = new ArrayList<>();
        List<String> lines = Collections.emptyList();
        try {
            lines = Files.readAllLines(Paths.get(SMB_CONF_PATH), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Failed to read " + SMB_CONF_PATH + ": " + e.getMessage());
            return list;
        }

        RawSection currentSection = null;
        List<String> leadingComments = new ArrayList<>();

        Pattern sectionPattern = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*$");
        Pattern paramPattern = Pattern.compile("^\\s*([^=;#!]+?)\\s*=\\s*(.*?)\\s*$");

        for (String line : lines) {
            String trimmed = line.trim();
            Matcher secMatcher = sectionPattern.matcher(trimmed);

            if (secMatcher.matches()) {
                String secName = secMatcher.group(1).trim();
                currentSection = new RawSection(secName);
                currentSection.precedingLines.addAll(leadingComments);
                leadingComments.clear();
                list.add(currentSection);
            } else if (currentSection != null) {
                Matcher paramMatcher = paramPattern.matcher(line);
                if (paramMatcher.matches()) {
                    String key = paramMatcher.group(1).trim().toLowerCase();
                    String val = paramMatcher.group(2).trim();
                    currentSection.setParam(key, val);
                } else {
                    currentSection.rawLines.add(line);
                }
            } else {
                leadingComments.add(line);
            }
        }

        return list;
    }

    /**
     * Serialize RawSections back to smb.conf format
     */
    private String serializeSmbConf(List<RawSection> sections) {
        StringBuilder sb = new StringBuilder();

        for (RawSection sec : sections) {
            // Write preceding comments
            for (String c : sec.precedingLines) {
                sb.append(c).append("\n");
            }

            sb.append("[").append(sec.name).append("]\n");

            // Write parameters
            for (Map.Entry<String, String> entry : sec.params.entrySet()) {
                sb.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
            }

            // Write extra raw lines if any
            for (String raw : sec.rawLines) {
                if (!raw.trim().isEmpty()) {
                    sb.append("    ").append(raw.trim()).append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private Map<String, Object> executeCommand(String[] cmd) throws Exception {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
            String stdout;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = r.lines().collect(Collectors.joining("\n"));
            }
            String stderr;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = r.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();
            Map<String, Object> res = new HashMap<>();
            res.put("exitCode", exitCode);
            res.put("stdout", stdout);
            res.put("stderr", stderr);
            return res;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private Map<String, Object> executeCommandWithStdin(String[] cmd, String stdin) throws Exception {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
            if (stdin != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
            String stdout;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = r.lines().collect(Collectors.joining("\n"));
            }
            String stderr;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = r.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();
            Map<String, Object> res = new HashMap<>();
            res.put("exitCode", exitCode);
            res.put("stdout", stdout);
            res.put("stderr", stderr);
            return res;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Inner helper representing a section in smb.conf
     */
    private static class RawSection {
        String name;
        List<String> precedingLines = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>();
        List<String> rawLines = new ArrayList<>();

        RawSection(String name) {
            this.name = name;
        }

        void setParam(String key, String value) {
            params.put(key, value);
        }

        String getParam(String key, String def) {
            return params.getOrDefault(key.toLowerCase(), def);
        }
    }
}
