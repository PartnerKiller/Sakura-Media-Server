package com.sakuradata.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakuradata.media.model.StorageRoot;
import com.sakuradata.media.repository.StorageRootRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CloudStorageService {

    private static final String RCLONE_CONF_PATH = System.getProperty("user.home") + "/.config/rclone/rclone.conf";
    private static final String SYSTEMD_USER_DIR = System.getProperty("user.home") + "/.config/systemd/user";
    private static final Pattern REMOTE_SECTION_PATTERN = Pattern.compile("^\\[([a-zA-Z0-9._-]+)\\]\\s*$");
    private static final Pattern URL_STATE_PATTERN = Pattern.compile("state=([a-zA-Z0-9_-]+)");
    private static final Pattern URL_CODE_PATTERN = Pattern.compile("code=([^&\\s]+)");

    @Autowired
    private StorageRootRepository storageRootRepository;

    @Autowired
    private StorageService storageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, OAuthSession> activeSessions = new ConcurrentHashMap<>();

    private static class OAuthSession {
        String sessionId;
        Process process;
        String state;
        String googleAuthUrl;
        long createdAt;
        StringBuilder outputBuffer = new StringBuilder();
        volatile boolean completed = false;
        volatile String tokenJson = null;
        volatile String error = null;
    }

    /**
     * Get all configured Google Drive & rclone remotes with their mount & systemd status
     */
    public List<Map<String, Object>> getCloudDrives() {
        List<Map<String, Object>> list = new ArrayList<>();
        File confFile = new File(RCLONE_CONF_PATH);
        if (!confFile.exists()) {
            return list;
        }

        Map<String, Map<String, String>> remotes = parseRcloneConf(confFile);

        for (Map.Entry<String, Map<String, String>> entry : remotes.entrySet()) {
            String remoteName = entry.getKey();
            Map<String, String> props = entry.getValue();

            String type = props.getOrDefault("type", "drive");
            if (!"drive".equalsIgnoreCase(type) && !props.containsKey("token")) {
                continue; // only show cloud drive remotes
            }

            Map<String, Object> drive = new LinkedHashMap<>();
            drive.put("remoteName", remoteName);
            drive.put("type", "Google Drive");
            drive.put("scope", props.getOrDefault("scope", "drive"));
            drive.put("hasToken", props.containsKey("token"));

            // Determine mount path and systemd service
            String serviceName = remoteName.equalsIgnoreCase("gdrive") ? "rclone-gdrive.service" : "rclone-" + remoteName + ".service";
            drive.put("serviceName", serviceName);

            String mountPath = findMountPathForRemote(remoteName, serviceName);
            drive.put("mountPath", mountPath);

            // Check systemd status
            Map<String, Object> svcStatus = checkServiceStatus(serviceName);
            drive.put("serviceRunning", svcStatus.get("running"));
            drive.put("serviceState", svcStatus.get("activeState"));
            drive.put("serviceSubState", svcStatus.get("subState"));
            drive.put("mainPid", svcStatus.get("mainPid"));
            drive.put("uptime", svcStatus.get("uptime"));

            // Check if mountpoint is actually mounted
            boolean isMounted = isPathMounted(mountPath);
            drive.put("isMounted", isMounted);

            // Enrich with disk capacity & usage if mounted
            if (isMounted && mountPath != null) {
                File dir = new File(mountPath);
                if (dir.exists()) {
                    long total = dir.getTotalSpace();
                    long free = dir.getUsableSpace();
                    long used = Math.max(0, total - free);
                    double pct = total > 0 ? (double) used / total * 100.0 : 0.0;

                    drive.put("totalSpace", total);
                    drive.put("usedSpace", used);
                    drive.put("freeSpace", free);
                    drive.put("formattedTotal", formatBytes(total));
                    drive.put("formattedUsed", formatBytes(used));
                    drive.put("formattedFree", formatBytes(free));
                    drive.put("usePercent", String.format(Locale.US, "%.1f%%", pct));
                    drive.put("usePercentVal", Math.round(pct));
                }
            }

            // Check if allocated as media root
            if (mountPath != null) {
                Optional<StorageRoot> rootOpt = storageRootRepository.findByPath(mountPath);
                drive.put("isAllocatedRoot", rootOpt.isPresent());
                if (rootOpt.isPresent()) {
                    drive.put("allocatedRootId", rootOpt.get().getId());
                    drive.put("allocatedRootName", rootOpt.get().getName());
                }
            } else {
                drive.put("isAllocatedRoot", false);
            }

            list.add(drive);
        }

        return list;
    }

    /**
     * Start Google OAuth login session using rclone authorize
     */
    public synchronized Map<String, Object> startGoogleLogin(String customClientId, String customClientSecret) throws Exception {
        // Clean up expired sessions (> 10 mins)
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> {
            boolean expired = (now - e.getValue().createdAt) > 600000;
            if (expired && e.getValue().process != null && e.getValue().process.isAlive()) {
                e.getValue().process.destroyForcibly();
            }
            return expired;
        });

        // Ensure port 53682 is free before launching
        ensurePortFree(53682);

        String sessionId = UUID.randomUUID().toString();
        OAuthSession session = new OAuthSession();
        session.sessionId = sessionId;
        session.createdAt = now;

        List<String> cmd = new ArrayList<>(Arrays.asList("rclone", "authorize", "drive"));
        if (customClientId != null && !customClientId.trim().isEmpty() &&
            customClientSecret != null && !customClientSecret.trim().isEmpty()) {
            cmd.add(customClientId.trim());
            cmd.add(customClientSecret.trim());
        }
        cmd.add("--auth-no-open-browser");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        session.process = process;

        // Asynchronously read stdout/stderr to find the auth link and final token
        CompletableFuture<String> authUrlFuture = new CompletableFuture<>();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    session.outputBuffer.append(line).append("\n");

                    // Check for notice containing localhost link
                    if (!authUrlFuture.isDone() && line.contains("http://127.0.0.1:53682/auth?state=")) {
                        Matcher matcher = URL_STATE_PATTERN.matcher(line);
                        if (matcher.find()) {
                            session.state = matcher.group(1);
                            // Query the rclone localhost server to get the actual 307 redirect URL to Google
                            new Thread(() -> {
                                try {
                                    Thread.sleep(500);
                                    String googleUrl = fetchGoogleAuthRedirect(session.state);
                                    session.googleAuthUrl = googleUrl;
                                    authUrlFuture.complete(googleUrl);
                                } catch (Exception ex) {
                                    authUrlFuture.completeExceptionally(ex);
                                }
                            }).start();
                        }
                    }

                    // Check for token JSON in stdout
                    if (line.trim().startsWith("{") && line.contains("access_token")) {
                        session.tokenJson = line.trim();
                        session.completed = true;
                    }
                }
            } catch (Exception e) {
                session.error = e.getMessage();
                if (!authUrlFuture.isDone()) authUrlFuture.completeExceptionally(e);
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        // Wait up to 6 seconds for Google Auth URL
        String googleAuthUrl;
        try {
            googleAuthUrl = authUrlFuture.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new Exception("Failed to start Google login authorization: " + (session.error != null ? session.error : e.getMessage()));
        }

        activeSessions.put(sessionId, session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("state", session.state);
        result.put("googleAuthUrl", googleAuthUrl);
        result.put("expiresInSeconds", 600);
        return result;
    }

    /**
     * Check status of ongoing OAuth session (polls whether rclone received callback)
     */
    public Map<String, Object> checkLoginSession(String sessionId) {
        OAuthSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found or expired");
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sessionId", sessionId);
        res.put("state", session.state);
        res.put("completed", session.completed);
        res.put("hasToken", session.tokenJson != null);

        if (session.error != null) {
            res.put("error", session.error);
        }
        return res;
    }

    /**
     * Complete authorization: if user got redirected to http://127.0.0.1:53682/?state=...&code=...
     * this method relays it to the local rclone listener, captures the token, saves config and mounts!
     */
    public Map<String, Object> completeGoogleLogin(String sessionId, String callbackUrlOrCode, String remoteName,
                                                   String mountPath, boolean allocateAsRoot, String rootName) throws Exception {
        OAuthSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("OAuth session not found or expired. Please restart Google Login.");
        }

        String tokenJson = session.tokenJson;

        if (tokenJson == null) {
            if (callbackUrlOrCode == null || callbackUrlOrCode.trim().isEmpty()) {
                throw new IllegalArgumentException("Please paste the callback URL or authorization code received from Google.");
            }

            callbackUrlOrCode = callbackUrlOrCode.trim();
            String code = null;
            String state = session.state;

            if (callbackUrlOrCode.contains("code=")) {
                Matcher codeMatcher = URL_CODE_PATTERN.matcher(callbackUrlOrCode);
                if (codeMatcher.find()) {
                    code = codeMatcher.group(1);
                }
                Matcher stateMatcher = URL_STATE_PATTERN.matcher(callbackUrlOrCode);
                if (stateMatcher.find()) {
                    state = stateMatcher.group(1);
                }
            } else {
                code = callbackUrlOrCode;
            }

            if (code == null || code.isEmpty()) {
                throw new IllegalArgumentException("Unable to extract authorization code from input.");
            }

            // Relay code to local rclone listener on port 53682
            String relayUrl = "http://127.0.0.1:53682/?state=" + state + "&code=" + code;
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(relayUrl).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.getResponseCode();
            } catch (Exception ignored) {}

            // Wait up to 10 seconds for rclone to process the token
            long waitStart = System.currentTimeMillis();
            while (session.tokenJson == null && (System.currentTimeMillis() - waitStart) < 10000) {
                if (!session.process.isAlive()) break;
                Thread.sleep(500);
            }

            tokenJson = session.tokenJson;
        }

        if (tokenJson == null) {
            // Check output buffer for any JSON
            String fullOutput = session.outputBuffer.toString();
            int jsonStart = fullOutput.indexOf("{\"access_token\":");
            if (jsonStart != -1) {
                int jsonEnd = fullOutput.indexOf("}", jsonStart);
                if (jsonEnd != -1) {
                    tokenJson = fullOutput.substring(jsonStart, jsonEnd + 1);
                }
            }
        }

        if (tokenJson == null) {
            throw new Exception("Google token exchange timed out or failed. Output: " + session.outputBuffer.toString());
        }

        // Clean up session
        if (session.process != null && session.process.isAlive()) {
            session.process.destroy();
        }
        activeSessions.remove(sessionId);

        // Save remote, create systemd service, and mount drive
        return saveAndMountDrive(remoteName, tokenJson, mountPath, allocateAsRoot, rootName);
    }

    /**
     * Attach drive directly by pasting token JSON
     */
    public Map<String, Object> attachDriveWithToken(String remoteName, String tokenJson, String mountPath,
                                                    boolean allocateAsRoot, String rootName) throws Exception {
        if (tokenJson == null || tokenJson.trim().isEmpty() || !tokenJson.contains("access_token")) {
            throw new IllegalArgumentException("Invalid token JSON: must contain 'access_token'");
        }
        return saveAndMountDrive(remoteName, tokenJson.trim(), mountPath, allocateAsRoot, rootName);
    }

    /**
     * Saves remote into rclone.conf, creates systemd service, and mounts the drive
     */
    private Map<String, Object> saveAndMountDrive(String remoteName, String tokenJson, String mountPath,
                                                  boolean allocateAsRoot, String rootName) throws Exception {
        if (remoteName == null || remoteName.trim().isEmpty()) {
            remoteName = "gdrive";
        }
        remoteName = sanitizeRemoteName(remoteName.trim());

        if (mountPath == null || mountPath.trim().isEmpty()) {
            mountPath = "/media/" + remoteName;
        }
        mountPath = mountPath.trim();
        if (!mountPath.startsWith("/")) {
            mountPath = "/" + mountPath;
        }

        // 1. Update or append remote to ~/.config/rclone/rclone.conf
        writeRcloneRemote(remoteName, tokenJson);

        // 2. Create mount directory
        File targetDir = new File(mountPath);
        if (!targetDir.exists()) {
            executeCommand(new String[]{"sudo", "mkdir", "-p", mountPath});
            executeCommand(new String[]{"sudo", "chown", "1000:1000", mountPath});
            executeCommand(new String[]{"sudo", "chmod", "775", mountPath});
        }

        // 3. Create or update systemd user service
        String serviceName = remoteName.equalsIgnoreCase("gdrive") ? "rclone-gdrive.service" : "rclone-" + remoteName + ".service";
        createSystemdService(serviceName, remoteName, mountPath);

        // 4. Reload systemd daemon & start service
        executeCommand(new String[]{"systemctl", "--user", "daemon-reload"});
        executeCommand(new String[]{"systemctl", "--user", "enable", "--now", serviceName});

        // 5. Wait up to 5 seconds to verify mount
        boolean mounted = false;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            if (isPathMounted(mountPath)) {
                mounted = true;
                break;
            }
        }

        // 6. Allocate as Storage Root if requested
        if (allocateAsRoot) {
            String finalRootName = (rootName != null && !rootName.trim().isEmpty())
                    ? rootName.trim()
                    : "Google Drive (" + remoteName + ")";

            if (!storageRootRepository.existsByPath(mountPath)) {
                int nextIdx = storageRootRepository.findAllByOrderByOrderIndexAsc().size();
                StorageRoot newRoot = new StorageRoot(finalRootName, mountPath, true, true, nextIdx, "rclone:" + remoteName);
                storageRootRepository.save(newRoot);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("remoteName", remoteName);
        result.put("mountPath", mountPath);
        result.put("serviceName", serviceName);
        result.put("isMounted", mounted);
        result.put("message", "Google Drive [" + remoteName + "] attached and mounted successfully at " + mountPath);
        return result;
    }

    /**
     * Start, stop, or restart rclone mount service
     */
    public Map<String, Object> controlDrive(String remoteName, String action) throws Exception {
        remoteName = sanitizeRemoteName(remoteName);
        String serviceName = remoteName.equalsIgnoreCase("gdrive") ? "rclone-gdrive.service" : "rclone-" + remoteName + ".service";

        String verb;
        if ("start".equalsIgnoreCase(action) || "mount".equalsIgnoreCase(action)) {
            verb = "start";
        } else if ("stop".equalsIgnoreCase(action) || "unmount".equalsIgnoreCase(action)) {
            verb = "stop";
        } else if ("restart".equalsIgnoreCase(action) || "remount".equalsIgnoreCase(action)) {
            verb = "restart";
        } else {
            throw new IllegalArgumentException("Unknown control action: " + action);
        }

        executeCommand(new String[]{"systemctl", "--user", verb, serviceName});
        Thread.sleep(1000);

        Map<String, Object> status = checkServiceStatus(serviceName);
        String mountPath = findMountPathForRemote(remoteName, serviceName);
        status.put("remoteName", remoteName);
        status.put("mountPath", mountPath);
        status.put("isMounted", isPathMounted(mountPath));
        return status;
    }

    /**
     * Detach drive: stop service, disable, remove service file, remove from rclone.conf
     */
    public Map<String, Object> detachDrive(String remoteName) throws Exception {
        remoteName = sanitizeRemoteName(remoteName);
        String serviceName = remoteName.equalsIgnoreCase("gdrive") ? "rclone-gdrive.service" : "rclone-" + remoteName + ".service";
        String mountPath = findMountPathForRemote(remoteName, serviceName);

        // 1. Stop and disable service
        try {
            executeCommand(new String[]{"systemctl", "--user", "stop", serviceName});
            executeCommand(new String[]{"systemctl", "--user", "disable", serviceName});
        } catch (Exception ignored) {}

        // 2. Unmount if still held by fuse
        if (mountPath != null && isPathMounted(mountPath)) {
            try {
                executeCommand(new String[]{"fusermount", "-uz", mountPath});
            } catch (Exception ignored) {}
        }

        // 3. Remove systemd service file if custom
        if (!remoteName.equalsIgnoreCase("gdrive")) {
            File svcFile = new File(SYSTEMD_USER_DIR, serviceName);
            if (svcFile.exists()) {
                svcFile.delete();
            }
            executeCommand(new String[]{"systemctl", "--user", "daemon-reload"});
        }

        // 4. Remove remote from rclone.conf
        removeRcloneRemote(remoteName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("remoteName", remoteName);
        result.put("message", "Google Drive remote [" + remoteName + "] detached successfully.");
        return result;
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private String fetchGoogleAuthRedirect(String state) throws Exception {
        String testUrl = "http://127.0.0.1:53682/auth?state=" + state;
        HttpURLConnection conn = (HttpURLConnection) URI.create(testUrl).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        int code = conn.getResponseCode();
        if (code == 307 || code == 302 || code == 301) {
            String loc = conn.getHeaderField("Location");
            if (loc != null && !loc.isEmpty()) {
                return loc;
            }
        }
        throw new Exception("rclone auth listener did not return Google OAuth redirect location (HTTP " + code + ")");
    }

    private void ensurePortFree(int port) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"bash", "-c", "fuser -k " + port + "/tcp 2>/dev/null || true"});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private Map<String, Map<String, String>> parseRcloneConf(File file) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        if (!file.exists()) return map;

        String currentSection = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

                Matcher m = REMOTE_SECTION_PATTERN.matcher(line);
                if (m.matches()) {
                    currentSection = m.group(1);
                    map.putIfAbsent(currentSection, new LinkedHashMap<>());
                    continue;
                }

                if (currentSection != null && line.contains("=")) {
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim().toLowerCase();
                    String val = line.substring(idx + 1).trim();
                    map.get(currentSection).put(key, val);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing rclone.conf: " + e.getMessage());
        }
        return map;
    }

    private synchronized void writeRcloneRemote(String remoteName, String tokenJson) throws Exception {
        File conf = new File(RCLONE_CONF_PATH);
        conf.getParentFile().mkdirs();

        List<String> lines = new ArrayList<>();
        if (conf.exists()) {
            lines = Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8);
        }

        // Remove old section if exists
        List<String> cleanLines = new ArrayList<>();
        boolean inSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher m = REMOTE_SECTION_PATTERN.matcher(trimmed);
            if (m.matches()) {
                inSection = m.group(1).equalsIgnoreCase(remoteName);
            }
            if (!inSection) {
                cleanLines.add(line);
            }
        }

        // Append new section
        cleanLines.add("[" + remoteName + "]");
        cleanLines.add("type = drive");
        cleanLines.add("scope = drive");
        cleanLines.add("token = " + tokenJson);
        cleanLines.add("team_drive = ");
        cleanLines.add("");

        Files.write(conf.toPath(), cleanLines, StandardCharsets.UTF_8);
    }

    private synchronized void removeRcloneRemote(String remoteName) throws Exception {
        File conf = new File(RCLONE_CONF_PATH);
        if (!conf.exists()) return;

        List<String> lines = Files.readAllLines(conf.toPath(), StandardCharsets.UTF_8);
        List<String> cleanLines = new ArrayList<>();
        boolean inSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher m = REMOTE_SECTION_PATTERN.matcher(trimmed);
            if (m.matches()) {
                inSection = m.group(1).equalsIgnoreCase(remoteName);
            }
            if (!inSection) {
                cleanLines.add(line);
            }
        }
        Files.write(conf.toPath(), cleanLines, StandardCharsets.UTF_8);
    }

    private void createSystemdService(String serviceName, String remoteName, String mountPath) throws Exception {
        File userDir = new File(SYSTEMD_USER_DIR);
        userDir.mkdirs();

        String serviceContent = String.join("\n",
                "[Unit]",
                "Description=Rclone Google Drive Mount - " + remoteName,
                "After=network-online.target",
                "Wants=network-online.target",
                "",
                "[Service]",
                "Type=simple",
                "ExecStart=/usr/bin/rclone mount " + remoteName + ": " + mountPath + " \\",
                "    --config " + RCLONE_CONF_PATH + " \\",
                "    --vfs-cache-mode full \\",
                "    --vfs-read-ahead 256M \\",
                "    --vfs-read-chunk-size 32M \\",
                "    --vfs-read-chunk-size-limit 512M \\",
                "    --buffer-size 128M \\",
                "    --drive-pacer-min-sleep 10ms \\",
                "    --drive-pacer-burst 200 \\",
                "    --drive-acknowledge-abuse \\",
                "    --allow-other \\",
                "    --dir-cache-time 72h \\",
                "    --vfs-cache-max-age 48h",
                "ExecStop=/bin/fusermount -uz " + mountPath,
                "Restart=on-failure",
                "RestartSec=5",
                "",
                "[Install]",
                "WantedBy=default.target",
                ""
        );

        File svcFile = new File(userDir, serviceName);
        Files.writeString(svcFile.toPath(), serviceContent, StandardCharsets.UTF_8);
    }

    private String findMountPathForRemote(String remoteName, String serviceName) {
        // First check systemd service file
        File svcFile = new File(SYSTEMD_USER_DIR, serviceName);
        if (svcFile.exists()) {
            try {
                String content = Files.readString(svcFile.toPath());
                Matcher m = Pattern.compile("rclone mount " + Pattern.quote(remoteName) + ":\\s+([^\\s\\\\]+)").matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
            } catch (Exception ignored) {}
        }

        // Standard convention default
        if ("gdrive".equalsIgnoreCase(remoteName)) {
            return "/media/gdrive";
        }
        return "/media/" + remoteName;
    }

    private boolean isPathMounted(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"mountpoint", "-q", path});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> checkServiceStatus(String serviceName) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("running", false);
        res.put("activeState", "inactive");
        res.put("subState", "dead");
        res.put("mainPid", null);
        res.put("uptime", null);

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"systemctl", "--user", "show", serviceName,
                    "--property=ActiveState,SubState,MainPID,ActiveEnterTimestamp"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ActiveState=")) {
                        String st = line.substring(12).trim();
                        res.put("activeState", st);
                        res.put("running", "active".equalsIgnoreCase(st));
                    } else if (line.startsWith("SubState=")) {
                        res.put("subState", line.substring(9).trim());
                    } else if (line.startsWith("MainPID=")) {
                        String pid = line.substring(8).trim();
                        if (!"0".equals(pid)) res.put("mainPid", pid);
                    } else if (line.startsWith("ActiveEnterTimestamp=")) {
                        String ts = line.substring(21).trim();
                        if (!ts.isEmpty()) res.put("uptime", ts);
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}

        return res;
    }

    private String sanitizeRemoteName(String name) {
        String clean = name.replaceAll("[^a-zA-Z0-9._-]", "").toLowerCase();
        return clean.isEmpty() ? "gdrive" : clean;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private Map<String, Object> executeCommand(String[] cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        String stdout;
        String stderr;
        try (BufferedReader r1 = new BufferedReader(new InputStreamReader(p.getInputStream()));
             BufferedReader r2 = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            stdout = r1.lines().collect(Collectors.joining("\n"));
            stderr = r2.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        Map<String, Object> map = new HashMap<>();
        map.put("exitCode", code);
        map.put("stdout", stdout);
        map.put("stderr", stderr);
        if (code != 0) {
            throw new Exception("Command failed (" + code + "): " + stderr);
        }
        return map;
    }
}
