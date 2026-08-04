package com.sakuradata.media.controller;

import com.sakuradata.media.model.*;
import com.sakuradata.media.repository.*;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private FirewallRuleRepository firewallRuleRepository;

    @Autowired
    private CronJobRepository cronJobRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Helper to check admin permission
    private boolean isAdmin(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        return user != null && "admin".equals(user.getRole());
    }

    @GetMapping("/users")
    public ResponseEntity<?> getUsers(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        List<User> users = userRepository.findAll();
        List<Map<String, Object>> responseList = users.stream().map(u -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("role", u.getRole());
            map.put("downloadBandwidthLimit", u.getDownloadBandwidthLimit());
            map.put("uploadBandwidthLimit", u.getUploadBandwidthLimit());
            map.put("plainPassword", u.getPlainPassword());
            map.put("permissions", permissionRepository.findByUserId(u.getId()));
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(responseList);
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String username = body.get("username") != null ? body.get("username").toString() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;
        String role = body.get("role") != null ? body.get("role").toString() : "user";
        
        Double downloadBandwidthLimit = null;
        if (body.get("downloadBandwidthLimit") != null && !body.get("downloadBandwidthLimit").toString().trim().isEmpty()) {
            try {
                downloadBandwidthLimit = Double.valueOf(body.get("downloadBandwidthLimit").toString().trim());
            } catch (NumberFormatException ignored) {}
        }

        Double uploadBandwidthLimit = null;
        if (body.get("uploadBandwidthLimit") != null && !body.get("uploadBandwidthLimit").toString().trim().isEmpty()) {
            try {
                uploadBandwidthLimit = Double.valueOf(body.get("uploadBandwidthLimit").toString().trim());
            } catch (NumberFormatException ignored) {}
        }

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required"));
        }

        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User already exists"));
        }

        String salt = BCrypt.gensalt(10);
        String hashed = BCrypt.hashpw(password, salt);
        User user = new User(username, hashed, role, downloadBandwidthLimit, uploadBandwidthLimit);
        user.setPlainPassword(password);
        userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "downloadBandwidthLimit", user.getDownloadBandwidthLimit() != null ? user.getDownloadBandwidthLimit() : 0,
                "uploadBandwidthLimit", user.getUploadBandwidthLimit() != null ? user.getUploadBandwidthLimit() : 0
        ));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(HttpServletRequest request, @PathVariable Long id) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        if (id == 1L) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete the owner/admin account"));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        if (user.getProfilePicture() != null) {
            try {
                Files.deleteIfExists(Paths.get(AVATARS_DIR).resolve(user.getProfilePicture()));
            } catch (Exception ignored) {}
        }

        // Remove user permissions first (explicit transaction block in JPA repository is automatic on delete)
        permissionRepository.deleteByUserId(id);
        userRepository.deleteById(id);

        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        String role = body.get("role") != null ? body.get("role").toString() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;
        String username = body.get("username") != null ? body.get("username").toString().trim() : null;

        if (username != null && !username.isEmpty() && !username.equals(user.getUsername())) {
            if (userRepository.findByUsername(username).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
            }
            user.setUsername(username);
        }

        if (role != null) {
            if (id == 1L && !"admin".equals(role)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot change owner role to non-admin"));
            }
            user.setRole(role);
        }

        if (password != null && !password.trim().isEmpty()) {
            String salt = BCrypt.gensalt(10);
            String hashed = BCrypt.hashpw(password, salt);
            user.setPasswordHash(hashed);
            user.setPlainPassword(password);
        }

        if (body.containsKey("downloadBandwidthLimit")) {
            Object limitVal = body.get("downloadBandwidthLimit");
            if (limitVal == null || limitVal.toString().trim().isEmpty()) {
                user.setDownloadBandwidthLimit(null);
            } else {
                try {
                    user.setDownloadBandwidthLimit(Double.valueOf(limitVal.toString().trim()));
                } catch (NumberFormatException ignored) {}
            }
        }

        if (body.containsKey("uploadBandwidthLimit")) {
            Object limitVal = body.get("uploadBandwidthLimit");
            if (limitVal == null || limitVal.toString().trim().isEmpty()) {
                user.setUploadBandwidthLimit(null);
            } else {
                try {
                    user.setUploadBandwidthLimit(Double.valueOf(limitVal.toString().trim()));
                } catch (NumberFormatException ignored) {}
            }
        }

        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "downloadBandwidthLimit", user.getDownloadBandwidthLimit() != null ? user.getDownloadBandwidthLimit() : 0,
                "uploadBandwidthLimit", user.getUploadBandwidthLimit() != null ? user.getUploadBandwidthLimit() : 0
        ));
    }

    private static final String AVATARS_DIR = "./data/avatars";

    @PostMapping("/users/{id}/avatar")
    public ResponseEntity<?> uploadUserAvatar(HttpServletRequest request, @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        if (file.getSize() > 5 * 1024 * 1024) { // 5MB limit
            return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 5MB limit"));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
        }

        try {
            File dir = new File(AVATARS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String origName = file.getOriginalFilename();
            String ext = ".png";
            if (origName != null && origName.lastIndexOf('.') > 0) {
                ext = origName.substring(origName.lastIndexOf('.'));
            }

            String fileName = "avatar_" + user.getId() + "_" + UUID.randomUUID().toString() + ext;
            Path targetPath = Paths.get(AVATARS_DIR).resolve(fileName);

            if (user.getProfilePicture() != null) {
                try {
                    Files.deleteIfExists(Paths.get(AVATARS_DIR).resolve(user.getProfilePicture()));
                } catch (Exception ignored) {}
            }

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            user.setProfilePicture(fileName);
            userRepository.save(user);

            logAudit(request, "Updated profile picture for user " + user.getUsername());

            return ResponseEntity.ok(Map.of("profilePicture", "/api/users/avatar/" + user.getUsername()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to save avatar file"));
        }
    }

    @DeleteMapping("/users/{id}/avatar")
    public ResponseEntity<?> deleteUserAvatar(HttpServletRequest request, @PathVariable Long id) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        User user = userOpt.get();
        if (user.getProfilePicture() != null) {
            try {
                Files.deleteIfExists(Paths.get(AVATARS_DIR).resolve(user.getProfilePicture()));
            } catch (Exception ignored) {}
            user.setProfilePicture(null);
            userRepository.save(user);
            logAudit(request, "Removed profile picture for user " + user.getUsername());
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/users/{id}/permissions")
    @Transactional
    public ResponseEntity<?> savePermissions(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, List<Map<String, Object>>> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        List<Map<String, Object>> rules = body.get("permissions");
        if (rules == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Permissions must be an array"));
        }

        // Guard directory path boundaries
        for (Map<String, Object> rule : rules) {
            String path = (String) rule.get("path");
            if (path == null) continue;
            String resolved = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
            if (!resolved.startsWith("/home/sakura") && !resolved.startsWith("/media/storage") && !resolved.startsWith("/media/hdd") && !resolved.startsWith("/media/gdrive")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Path must be under /home/sakura, /media/storage, /media/hdd or /media/gdrive: " + path));
            }
        }

        // Delete existing rules and overwrite
        permissionRepository.deleteByUserId(id);

        List<Permission> newPermissions = rules.stream().map(r -> {
            String path = (String) r.get("path");
            
            boolean read = true;
            Object readVal = r.get("allowRead");
            if (readVal instanceof Boolean) {
                read = (Boolean) readVal;
            } else if (readVal != null) {
                read = Boolean.parseBoolean(readVal.toString());
            }

            boolean write = false;
            Object writeVal = r.get("allowWrite");
            if (writeVal instanceof Boolean) {
                write = (Boolean) writeVal;
            } else if (writeVal != null) {
                write = Boolean.parseBoolean(writeVal.toString());
            }

            return new Permission(id, path, read, write);
        }).collect(Collectors.toList());

        permissionRepository.saveAll(newPermissions);
        return ResponseEntity.ok(Map.of("success", true, "permissions", newPermissions));
    }

    @GetMapping("/admin/storage-analysis")
    public ResponseEntity<?> getStorageAnalysis(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        try {
            File homeFile = new File("/home/sakura");
            File storageFile = new File("/media/storage");
            File hddFile = new File("/media/hdd");
            File gdriveFile = new File("/media/gdrive");

            Map<String, Object> homeStats = null;
            Map<String, Object> storageStats = null;
            Map<String, Object> hddStats = null;
            Map<String, Object> gdriveStats = null;

            if (homeFile.exists()) {
                long total = homeFile.getTotalSpace();
                long usable = homeFile.getUsableSpace();
                long used = total - usable;
                double percent = total > 0 ? (double) used / total * 100 : 0.0;
                homeStats = new HashMap<>();
                homeStats.put("filesystem", "local");
                homeStats.put("total", total);
                homeStats.put("used", used);
                homeStats.put("available", usable);
                homeStats.put("usePercent", String.format(Locale.US, "%.0f%%", percent));
                homeStats.put("mountedOn", "/home/sakura");
            }

            if (storageFile.exists()) {
                long total = storageFile.getTotalSpace();
                long usable = storageFile.getUsableSpace();
                long used = total - usable;
                double percent = total > 0 ? (double) used / total * 100 : 0.0;
                storageStats = new HashMap<>();
                storageStats.put("filesystem", "storage");
                storageStats.put("total", total);
                storageStats.put("used", used);
                storageStats.put("available", usable);
                storageStats.put("usePercent", String.format(Locale.US, "%.0f%%", percent));
                storageStats.put("mountedOn", "/media/storage");
            }

            if (hddFile.exists()) {
                long total = hddFile.getTotalSpace();
                long usable = hddFile.getUsableSpace();
                long used = total - usable;
                double percent = total > 0 ? (double) used / total * 100 : 0.0;
                hddStats = new HashMap<>();
                hddStats.put("filesystem", "hdd");
                hddStats.put("total", total);
                hddStats.put("used", used);
                hddStats.put("available", usable);
                hddStats.put("usePercent", String.format(Locale.US, "%.0f%%", percent));
                hddStats.put("mountedOn", "/media/hdd");
            }

            if (gdriveFile.exists()) {
                long total = gdriveFile.getTotalSpace();
                long usable = gdriveFile.getUsableSpace();
                long used = total - usable;
                double percent = total > 0 ? (double) used / total * 100 : 0.0;
                gdriveStats = new HashMap<>();
                gdriveStats.put("filesystem", "gdrive");
                gdriveStats.put("total", total);
                gdriveStats.put("used", used);
                gdriveStats.put("available", usable);
                gdriveStats.put("usePercent", String.format(Locale.US, "%.0f%%", percent));
                gdriveStats.put("mountedOn", "/media/gdrive");
            }

            Map<String, Object> response = new HashMap<>();
            if (homeStats != null) {
                homeStats.put("name", "Home (sakura)");
                homeStats.put("path", "/home/sakura");
                response.put("home", homeStats);
            }
            if (storageStats != null) {
                storageStats.put("name", "Storage");
                storageStats.put("path", "/media/storage");
                response.put("storage", storageStats);
            }
            if (hddStats != null) {
                hddStats.put("name", "HDD");
                hddStats.put("path", "/media/hdd");
                response.put("hdd", hddStats);
            }
            if (gdriveStats != null) {
                gdriveStats.put("name", "Google Drive");
                gdriveStats.put("path", "/media/gdrive");
                response.put("gdrive", gdriveStats);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to retrieve storage stats: " + e.getMessage()));
        }
    }

    @GetMapping("/admin/server-metrics")
    public ResponseEntity<?> getServerMetrics(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Process process = null;
        try {
            // Run systemctl to check statuses
            process = Runtime.getRuntime().exec(new String[]{"systemctl", "--user", "is-active", "media-server", "cloudflared"});
            try {
                process.getOutputStream().close();
            } catch (Exception e) {}
            
            List<String> statuses;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                statuses = reader.lines().collect(Collectors.toList());
            }
            process.waitFor();

            String mediaServerStatus = statuses.size() > 0 ? statuses.get(0) : "unknown";
            String cloudflaredStatus = statuses.size() > 1 ? statuses.get(1) : "unknown";

            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();

            double cpuLoad = osBean.getCpuLoad();
            int cpuPercent = (int) Math.round(cpuLoad * 100);
            if (cpuPercent < 0) cpuPercent = 0;

            long totalMem = osBean.getTotalMemorySize();
            long freeMem = osBean.getFreeMemorySize();
            long usedMem = totalMem - freeMem;
            int memPercent = (int) Math.round(((double) usedMem / totalMem) * 100);

            Map<String, Object> memoryMap = new HashMap<>();
            memoryMap.put("total", totalMem);
            memoryMap.put("used", usedMem);
            memoryMap.put("free", freeMem);
            memoryMap.put("percent", memPercent);

            Map<String, Object> uptimeMap = new HashMap<>();
            uptimeMap.put("host", getHostUptime());
            uptimeMap.put("process", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);

            Map<String, Object> servicesMap = new HashMap<>();
            servicesMap.put("mediaServer", mediaServerStatus);
            servicesMap.put("cloudflared", cloudflaredStatus);

            Map<String, Object> osMap = new HashMap<>();
            osMap.put("platform", osBean.getName());
            osMap.put("release", osBean.getVersion());
            osMap.put("arch", osBean.getArch());
            osMap.put("nodeVersion", "Java " + System.getProperty("java.version")); // maps to frontend name nodeVersion

            Map<String, Object> response = new HashMap<>();
            response.put("cpuUsage", cpuPercent);
            response.put("loadAvg", getLinuxLoadAvg());
            response.put("memory", memoryMap);
            response.put("uptime", uptimeMap);
            response.put("services", servicesMap);
            response.put("os", osMap);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to retrieve server metrics: " + e.getMessage()));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private double[] getLinuxLoadAvg() {
        try {
            String content = new String(Files.readAllBytes(Paths.get("/proc/loadavg")));
            String[] parts = content.trim().split("\\s+");
            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])
            };
        } catch (Exception e) {
            return new double[]{0.0, 0.0, 0.0};
        }
    }

    private long getHostUptime() {
        try {
            String content = new String(Files.readAllBytes(Paths.get("/proc/uptime")));
            String[] parts = content.trim().split("\\s+");
            return (long) Double.parseDouble(parts[0]);
        } catch (Exception e) {
            return 0L;
        }
    }

    @GetMapping("/admin/server-processes")
    public ResponseEntity<?> getServerProcesses(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Process process = null;
        try {
            // Standard ps shell stream pipeline command
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", "ps -eo pid,pcpu,pmem,etime,args | grep -E 'ffmpeg|ffprobe' | grep -v grep");
            process = builder.start();
            try {
                process.getOutputStream().close();
            } catch (Exception e) {}
            
            List<String> lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                lines = reader.lines().collect(Collectors.toList());
            }
            process.waitFor();

            List<Map<String, String>> processes = lines.stream().map(line -> {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5) return null;
                Map<String, String> map = new HashMap<>();
                map.put("pid", parts[0]);
                map.put("cpu", parts[1]);
                map.put("mem", parts[2]);
                map.put("etime", parts[3]);
                map.put("command", String.join(" ", Arrays.copyOfRange(parts, 4, parts.length)));
                return map;
            }).filter(Objects::nonNull).collect(Collectors.toList());

            return ResponseEntity.ok(processes);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to retrieve processes: " + e.getMessage()));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @GetMapping("/admin/server-logs")
    public ResponseEntity<?> getServerLogs(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"journalctl", "--user", "-u", "media-server", "--no-pager", "-n", "100"});
            try {
                process.getOutputStream().close();
            } catch (Exception e) {}
            
            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = reader.lines().collect(Collectors.joining("\n"));
            }
            process.waitFor();

            return ResponseEntity.ok(Map.of("logs", stdout));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to retrieve logs: " + e.getMessage()));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @PostMapping("/admin/server-action")
    public ResponseEntity<?> runServerAction(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String action = (String) body.get("action");
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Action is required"));
        }

        if ("kill-process".equals(action)) {
            Object pidObj = body.get("pid");
            if (pidObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "PID is required to kill process"));
            }
            int pid = Integer.parseInt(pidObj.toString());
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
                process.waitFor();
                return ResponseEntity.ok(Map.of("success", true, "message", "Process " + pid + " killed successfully"));
            } catch (Exception e) {
                return ResponseEntity.status(500).body(Map.of("error", "Failed to kill process: " + e.getMessage()));
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        } else if ("restart-service".equals(action)) {
            scheduler.schedule(() -> {
                runSystemCommand(new String[]{"systemctl", "--user", "restart", "media-server"});
            }, 500, TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(Map.of("success", true, "message", "Application service restart scheduled"));
        } else if ("reboot-host".equals(action)) {
            scheduler.schedule(() -> {
                runSystemCommand(new String[]{"systemctl", "reboot"});
            }, 500, TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(Map.of("success", true, "message", "Host machine reboot scheduled"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid action: " + action));
        }
    }

    // -------------------------------------------------------------
    // INTEGRATED SERVER MANAGER REST API ENDPOINTS
    // -------------------------------------------------------------

    private Map<String, Object> runSystemCommand(String[] cmd) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
            try {
                process.getOutputStream().close();
            } catch (Exception e) {}

            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = reader.lines().collect(Collectors.joining("\n"));
            }
            
            String stderr;
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }
            
            int exitCode = process.waitFor();

            Map<String, Object> result = new HashMap<>();
            result.put("exitCode", exitCode);
            result.put("stdout", stdout);
            result.put("stderr", stderr);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("exitCode", -1);
            result.put("error", e.getMessage());
            return result;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void logAudit(HttpServletRequest request, String actionDescription) {
        User user = (User) request.getAttribute("user");
        String username = (user != null) ? user.getUsername() : "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null) ip = request.getRemoteAddr();
        auditLogRepository.save(new AuditLog(username, actionDescription, ip));
    }

    @GetMapping("/admin/docker/containers")
    public ResponseEntity<?> getDockerContainers(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String format = "{\"id\":\"{{.ID}}\",\"name\":\"{{.Names}}\",\"image\":\"{{.Image}}\",\"status\":\"{{.Status}}\",\"state\":\"{{.State}}\",\"ports\":\"{{.Ports}}\"}";
        Map<String, Object> cmdResult = runSystemCommand(new String[]{"docker", "ps", "-a", "--format", format});
        
        if ((int)cmdResult.get("exitCode") != 0) {
            return ResponseEntity.ok(Map.of("dockerActive", false, "containers", List.of()));
        }

        String stdout = (String) cmdResult.get("stdout");
        List<Map<String, Object>> containers = Arrays.stream(stdout.trim().split("\n"))
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        Map<String, Object> map = mapper.readValue(line, Map.class);
                        map.put("cpu", "0.2%"); // fallback mock resource statistics
                        map.put("memory", "48 MB");
                        return map;
                    } catch (Exception e) {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("dockerActive", true, "containers", containers));
    }

    @PostMapping("/admin/docker/containers/control")
    public ResponseEntity<?> controlDockerContainer(HttpServletRequest request, @RequestBody Map<String, String> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String id = body.get("id");
        String action = body.get("action");
        if (id == null || action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "ID and action are required"));
        }

        logAudit(request, "Docker container control: " + action + " on ID " + id);
        
        Map<String, Object> res = runSystemCommand(new String[]{"docker", action, id});
        if ((int)res.get("exitCode") != 0) {
            return ResponseEntity.status(500).body(Map.of("error", res.get("stderr")));
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "Container " + action + " command executed"));
    }

    @GetMapping("/admin/docker/containers/{id}/logs")
    public ResponseEntity<?> getDockerContainerLogs(HttpServletRequest request, @PathVariable String id) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Map<String, Object> res = runSystemCommand(new String[]{"docker", "logs", "--tail", "200", id});
        String logs = (String) res.get("stdout");
        if (logs == null || logs.trim().isEmpty()) {
            logs = (String) res.get("stderr");
        }
        if (logs == null) logs = "No container output logs retrieved.";

        return ResponseEntity.ok(Map.of("logs", logs));
    }

    @GetMapping("/admin/config/firewall")
    public ResponseEntity<?> getFirewallRules(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Map<String, Object> res = runSystemCommand(new String[]{"sh", "-c", "echo sakura | sudo -S ufw status verbose"});
        String raw = (String) res.get("stdout");
        boolean active = raw != null && raw.contains("Status: active");

        List<FirewallRule> dbRules = firewallRuleRepository.findAll();
        return ResponseEntity.ok(Map.of(
                "active", active,
                "rawStatus", raw != null ? raw.trim() : "inactive",
                "rules", dbRules
        ));
    }

    @PostMapping("/admin/config/firewall")
    public ResponseEntity<?> addFirewallRule(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Integer portObj = (Integer) body.get("port");
        String protocol = (String) body.get("protocol");
        String action = (String) body.get("action");
        String sourceIp = (String) body.get("sourceIp");

        if (portObj == null || protocol == null || action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Port, protocol, and action are required"));
        }

        int port = portObj;
        String protoSuffix = "both".equalsIgnoreCase(protocol) ? "" : "/" + protocol;
        String ufwCmd = "echo sakura | sudo -S ufw " + action + " " + port + protoSuffix;
        if (sourceIp != null && !sourceIp.trim().isEmpty()) {
            String protoPart = "both".equalsIgnoreCase(protocol) ? "" : " proto " + protocol;
            ufwCmd = "echo sakura | sudo -S ufw " + action + " from " + sourceIp + " to any port " + port + protoPart;
        }

        logAudit(request, "Added firewall rule: " + action + " port " + port + "/" + protocol);
        Map<String, Object> res = runSystemCommand(new String[]{"sh", "-c", ufwCmd});

        if ((int)res.get("exitCode") == 0) {
            FirewallRule rule = new FirewallRule(port, protocol, action, sourceIp);
            firewallRuleRepository.save(rule);
            return ResponseEntity.ok(Map.of("success", true, "message", "Firewall rule applied successfully"));
        } else {
            return ResponseEntity.status(500).body(Map.of("error", res.get("stderr")));
        }
    }

    @DeleteMapping("/admin/config/firewall/{id}")
    public ResponseEntity<?> deleteFirewallRule(HttpServletRequest request, @PathVariable Long id) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Optional<FirewallRule> ruleOpt = firewallRuleRepository.findById(id);
        if (ruleOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Firewall rule not found"));
        }

        FirewallRule rule = ruleOpt.get();
        String protoSuffix = "both".equalsIgnoreCase(rule.getProtocol()) ? "" : "/" + rule.getProtocol();
        String ufwCmd = "echo sakura | sudo -S ufw delete " + rule.getAction() + " " + rule.getPort() + protoSuffix;
        if (rule.getSourceIp() != null && !rule.getSourceIp().isEmpty()) {
            String protoPart = "both".equalsIgnoreCase(rule.getProtocol()) ? "" : " proto " + rule.getProtocol();
            ufwCmd = "echo sakura | sudo -S ufw delete " + rule.getAction() + " from " + rule.getSourceIp() + " to any port " + rule.getPort() + protoPart;
        }

        logAudit(request, "Deleted firewall rule: " + rule.getPort() + "/" + rule.getProtocol());
        runSystemCommand(new String[]{"sh", "-c", ufwCmd});
        firewallRuleRepository.deleteById(id);

        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/admin/config/vpn")
    public ResponseEntity<?> getVpnStatus(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Map<String, Object> wg = runSystemCommand(new String[]{"sh", "-c", "echo sakura | sudo -S wg show"});
        Map<String, Object> ts = runSystemCommand(new String[]{"tailscale", "status"});

        String wgOut = (String) wg.get("stdout");
        String tsOut = (String) ts.get("stdout");

        return ResponseEntity.ok(Map.of(
                "wireguard", Map.of("active", wgOut != null && !wgOut.trim().isEmpty(), "raw", wgOut != null ? wgOut.trim() : "WireGuard inactive"),
                "tailscale", Map.of("active", tsOut != null && !tsOut.trim().isEmpty() && !tsOut.contains("logged out"), "raw", tsOut != null ? tsOut.trim() : "Tailscale inactive")
        ));
    }

    @GetMapping("/admin/config/cloudflare")
    public ResponseEntity<?> getCloudflareTunnel(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        Map<String, Object> res = runSystemCommand(new String[]{"systemctl", "--user", "status", "cloudflared"});
        String raw = (String) res.get("stdout");
        if (raw == null || raw.trim().isEmpty()) {
            raw = (String) runSystemCommand(new String[]{"systemctl", "status", "cloudflared"}).get("stdout");
        }

        boolean active = raw != null && raw.contains("active (running)");
        return ResponseEntity.ok(Map.of(
                "active", active,
                "statusRaw", raw != null ? raw.trim() : "cloudflared status offline"
        ));
    }

    @GetMapping("/admin/config/cron")
    public ResponseEntity<?> getCronJobs(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(cronJobRepository.findAll());
    }

    @PostMapping("/admin/config/cron")
    public ResponseEntity<?> addCronJob(HttpServletRequest request, @RequestBody Map<String, String> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String name = body.get("name");
        String cronExpression = body.get("cronExpression");
        String command = body.get("command");
        String type = body.get("type");

        if (name == null || cronExpression == null || command == null || type == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "All fields are required"));
        }

        logAudit(request, "Added scheduled cron task: " + name);
        CronJob job = new CronJob(name, cronExpression, command, type);
        cronJobRepository.save(job);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true));
    }

    @DeleteMapping("/admin/config/cron/{id}")
    public ResponseEntity<?> deleteCronJob(HttpServletRequest request, @PathVariable Long id) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        logAudit(request, "Deleted scheduled cron task ID: " + id);
        cronJobRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/admin/system/packages")
    public ResponseEntity<?> getAptPackages(HttpServletRequest request, @RequestParam(required = false) String search) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String cmd = "apt list --upgradable 2>/dev/null | head -n 100";
        if (search != null && !search.trim().isEmpty()) {
            cmd = "apt-cache search \"" + search + "\" | head -n 100";
        }

        Map<String, Object> res = runSystemCommand(new String[]{"sh", "-c", cmd});
        String stdout = (String) res.get("stdout");
        if (stdout == null) stdout = "";

        List<Map<String, String>> packages = Arrays.stream(stdout.trim().split("\n"))
                .filter(line -> !line.isEmpty() && !line.startsWith("Listing..."))
                .map(line -> {
                    Map<String, String> map = new HashMap<>();
                    if (search != null && !search.trim().isEmpty()) {
                        int idx = line.indexOf(" - ");
                        if (idx == -1) {
                            map.put("name", line.trim());
                            map.put("description", "Apt package registry entry");
                        } else {
                            map.put("name", line.substring(0, idx).trim());
                            map.put("description", line.substring(idx + 3).trim());
                        }
                        map.put("status", "available");
                    } else {
                        String[] parts = line.split("/");
                        map.put("name", parts[0].trim());
                        map.put("description", "Upgradable release version exists");
                        map.put("status", "upgradable");
                    }
                    return map;
                }).collect(Collectors.toList());

        return ResponseEntity.ok(packages);
    }

    @PostMapping("/admin/system/packages/action")
    public ResponseEntity<?> runPackageAction(HttpServletRequest request, @RequestBody Map<String, String> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }

        String pkgName = body.get("pkgName");
        String action = body.get("action");
        if (pkgName == null || action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Package and action are required"));
        }

        String cmd = "echo sakura | sudo -S apt-get " + action + " -y " + pkgName;
        if ("upgrade".equals(action)) {
            cmd = "echo sakura | sudo -S apt-get update && echo sakura | sudo -S apt-get upgrade -y";
        }

        logAudit(request, "Triggered APT package action: " + action + " on " + pkgName);
        
        final String execCmd = cmd;
        scheduler.schedule(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", execCmd});
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 500, TimeUnit.MILLISECONDS);

        return ResponseEntity.ok(Map.of("success", true, "message", "Package manager operation spawned in background"));
    }

    @GetMapping("/admin/audit-logs")
    public ResponseEntity<?> getAuditLogs(HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(auditLogRepository.findTop200ByOrderByTimestampDesc());
    }

    private static final String THEME_FILE = "theme.json";

    @GetMapping("/theme")
    public ResponseEntity<?> getTheme() {
        String theme = "deep-ocean"; // Default
        try {
            java.io.File file = new java.io.File(THEME_FILE);
            if (file.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                int idx = content.indexOf("\"theme\"");
                if (idx != -1) {
                    int valStart = content.indexOf("\"", idx + 7);
                    if (valStart != -1) {
                        int valEnd = content.indexOf("\"", valStart + 1);
                        if (valEnd != -1) {
                            theme = content.substring(valStart + 1, valEnd);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("theme", theme));
    }

    @PutMapping("/theme")
    public ResponseEntity<?> updateTheme(HttpServletRequest request, @RequestBody Map<String, String> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }
        String theme = body.get("theme");
        if (theme == null || theme.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Theme name is required"));
        }

        // Validate theme name to prevent injections
        if (!theme.equals("cyber-sakura") && !theme.equals("deep-ocean") && !theme.equals("midnight-azure") && 
            !theme.equals("carbon-gray") && !theme.equals("aura-green") && !theme.equals("neon-violet") && 
            !theme.equals("sunset-orange") && !theme.equals("crimson-red") && !theme.equals("forest-lagoon") && 
            !theme.equals("golden-amber")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid theme name"));
        }

        try {
            java.io.File file = new java.io.File(THEME_FILE);
            String json = "{\"theme\":\"" + theme + "\"}";
            java.nio.file.Files.write(file.toPath(), json.getBytes());
            logAudit(request, "Changed system theme to " + theme);
            // Do not broadcast theme change to connected users to avoid overriding personal user theme selections
            return ResponseEntity.ok(Map.of("success", true, "theme", theme));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to save theme setting"));
        }
    }

    private static final String UI_STYLE_FILE = "ui_style.json";

    @GetMapping("/ui-style")
    public ResponseEntity<?> getUiStyle() {
        String style = "glassmorphism"; // Default
        try {
            java.io.File file = new java.io.File(UI_STYLE_FILE);
            if (file.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                int idx = content.indexOf("\"style\"");
                if (idx != -1) {
                    int valStart = content.indexOf("\"", idx + 7);
                    if (valStart != -1) {
                        int valEnd = content.indexOf("\"", valStart + 1);
                        if (valEnd != -1) {
                            style = content.substring(valStart + 1, valEnd);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("style", style));
    }

    @PutMapping("/ui-style")
    public ResponseEntity<?> updateUiStyle(HttpServletRequest request, @RequestBody Map<String, String> body) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admin access required"));
        }
        String style = body.get("style");
        if (style == null || style.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "UI Style name is required"));
        }

        // Validate style names to prevent injection
        if (!style.equals("glassmorphism") && !style.equals("minimalist") && !style.equals("retro-terminal") && 
            !style.equals("vaporwave-dream") && !style.equals("cyberpunk") && !style.equals("material-design") && 
            !style.equals("nebula-space") && !style.equals("steel-chrome") && !style.equals("nordic-aurora") && 
            !style.equals("aero-classic")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid UI Style name"));
        }

        try {
            java.io.File file = new java.io.File(UI_STYLE_FILE);
            String json = "{\"style\":\"" + style + "\"}";
            java.nio.file.Files.write(file.toPath(), json.getBytes());
            logAudit(request, "Changed system UI Style to " + style);
            // Do not broadcast ui style change to connected users to avoid overriding personal user ui style selections
            return ResponseEntity.ok(Map.of("success", true, "style", style));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to save UI Style setting"));
        }
    }

    @GetMapping("/users/avatar/{username}")
    public ResponseEntity<?> serveAvatar(@PathVariable String username) {
        java.util.Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        if (user.getProfilePicture() != null) {
            java.io.File avatarFile = new java.io.File("./data/avatars/" + user.getProfilePicture());
            if (avatarFile.exists()) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(avatarFile.toPath());
                    String contentType = java.nio.file.Files.probeContentType(avatarFile.toPath());
                    if (contentType == null) {
                        contentType = "image/png";
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(bytes);
                } catch (IOException ignored) {}
            }
        }

        // Fallback: Generate premium SVG avatar with user's initial and color hash
        String initial = username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase();
        int hash = username.hashCode();
        String[] colors = {
            "#e06666", "#f6b26b", "#ffd966", "#93c47d", "#76a5af", 
            "#6fa8dc", "#8e7cc3", "#c27ba0", "#a64d79", "#674ea7", 
            "#3d85c6", "#45818e", "#3f51b5", "#009688", "#4caf50"
        };
        String bgColor = colors[(hash & Integer.MAX_VALUE) % colors.length];
        
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" viewBox=\"0 0 100 100\">" +
                "<rect width=\"100\" height=\"100\" rx=\"50\" fill=\"" + bgColor + "\"/>" +
                "<text x=\"50%\" y=\"55%\" dominant-baseline=\"middle\" text-anchor=\"middle\" " +
                "font-family=\"system-ui, -apple-system, sans-serif\" font-size=\"45\" font-weight=\"bold\" fill=\"#ffffff\">" +
                initial + "</text></svg>";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .body(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
