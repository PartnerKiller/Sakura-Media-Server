package com.sakuradata.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakuradata.media.model.StorageRoot;
import com.sakuradata.media.repository.StorageRootRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class StorageService {

    private static final Pattern SAFE_DEVICE_PATTERN = Pattern.compile("^/dev/[a-zA-Z0-9_\\-]+$");
    private static final List<String> SYSTEM_PATHS = List.of(
            "/", "/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc",
            "/root", "/run", "/sbin", "/sys", "/tmp", "/usr", "/var"
    );

    @Autowired
    private StorageRootRepository storageRootRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initDefaultRoots() {
        try {
            if (storageRootRepository.count() == 0) {
                int index = 0;
                storageRootRepository.save(new StorageRoot("Home", "/home/sakura", true, true, index++));
                storageRootRepository.save(new StorageRoot("Storage", "/media/storage", true, true, index++));

                File ssdDir = new File("/media/ssd");
                if (ssdDir.exists()) {
                    storageRootRepository.save(new StorageRoot("SSD", "/media/ssd", true, true, index++, "/dev/sdb1"));
                }

                storageRootRepository.save(new StorageRoot("HDD", "/media/hdd", true, true, index++));
                storageRootRepository.save(new StorageRoot("Google Drive", "/media/gdrive", true, true, index++));
                System.out.println("Initialized default storage roots in database.");
            } else {
                // If SSD exists on system but not in DB roots, add it if user hasn't configured it yet
                File ssdDir = new File("/media/ssd");
                if (ssdDir.exists() && !storageRootRepository.existsByPath("/media/ssd")) {
                    int nextIdx = storageRootRepository.findAllByOrderByOrderIndexAsc().size();
                    storageRootRepository.save(new StorageRoot("SSD", "/media/ssd", true, true, nextIdx, "/dev/sdb1"));
                    System.out.println("Auto-registered existing SSD mount at /media/ssd");
                }
            }
        } catch (Exception e) {
            System.err.println("Error initializing storage roots: " + e.getMessage());
        }
    }

    public Map<String, Object> getConnectedDevices() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> disks = new ArrayList<>();

        long totalCapacity = 0;
        int mountedCount = 0;
        int unmountedCount = 0;

        List<StorageRoot> activeRoots = storageRootRepository.findAllByOrderByOrderIndexAsc();
        Map<String, StorageRoot> rootPathMap = new HashMap<>();
        for (StorageRoot r : activeRoots) {
            rootPathMap.put(normalizePath(r.getPath()), r);
        }

        try {
            String[] cmd = new String[]{"lsblk", "-J", "-b", "-o", "NAME,KNAME,PATH,SIZE,TYPE,FSTYPE,LABEL,UUID,MOUNTPOINT,MODEL,SERIAL,ROTA,PARTTYPENAME"};
            Process process = Runtime.getRuntime().exec(cmd);
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            process.waitFor();

            if (output != null && !output.trim().isEmpty() && output.startsWith("{")) {
                JsonNode rootNode = objectMapper.readTree(output);
                JsonNode blockDevices = rootNode.get("blockdevices");

                if (blockDevices != null && blockDevices.isArray()) {
                    for (JsonNode devNode : blockDevices) {
                        String type = getNodeString(devNode, "type");
                        if ("loop".equalsIgnoreCase(type) || "rom".equalsIgnoreCase(type)) {
                            continue; // skip loopback squashfs/snap devices and optical roms
                        }

                        Map<String, Object> diskData = parseDeviceNode(devNode, rootPathMap);
                        long diskSize = (long) diskData.getOrDefault("sizeBytes", 0L);
                        totalCapacity += diskSize;

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> partitions = (List<Map<String, Object>>) diskData.get("partitions");
                        if (partitions != null && !partitions.isEmpty()) {
                            for (Map<String, Object> part : partitions) {
                                boolean isMounted = Boolean.TRUE.equals(part.get("isMounted"));
                                if (isMounted) mountedCount++;
                                else unmountedCount++;
                            }
                        } else {
                            if (isUsableDisk(diskData)) {
                                boolean isMounted = Boolean.TRUE.equals(diskData.get("isMounted"));
                                if (isMounted) mountedCount++;
                                else unmountedCount++;
                            }
                        }

                        disks.add(diskData);
                    }
                }
            }
        } catch (Exception e) {
            // Fallback for non-Linux or lsblk failure
            System.err.println("lsblk error or non-linux environment: " + e.getMessage());
            Map<String, Object> fallbackDisk = getFallbackDiskInfo(rootPathMap);
            disks.add(fallbackDisk);
            mountedCount = activeRoots.size();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDisks", disks.size());
        summary.put("mountedCount", mountedCount);
        summary.put("unmountedCount", unmountedCount);
        summary.put("totalCapacityBytes", totalCapacity);
        summary.put("formattedTotalCapacity", formatBytes(totalCapacity));
        summary.put("allocatedRootsCount", activeRoots.size());

        result.put("summary", summary);
        result.put("disks", disks);
        return result;
    }

    private Map<String, Object> parseDeviceNode(JsonNode devNode, Map<String, StorageRoot> rootPathMap) {
        Map<String, Object> dev = new LinkedHashMap<>();
        String name = getNodeString(devNode, "name");
        String path = getNodeString(devNode, "path");
        if (path == null) path = "/dev/" + name;

        long sizeBytes = devNode.has("size") && !devNode.get("size").isNull() ? devNode.get("size").asLong(0) : 0;
        String type = getNodeString(devNode, "type");
        String fstype = getNodeString(devNode, "fstype");
        String label = getNodeString(devNode, "label");
        String uuid = getNodeString(devNode, "uuid");
        String model = getNodeString(devNode, "model");
        String serial = getNodeString(devNode, "serial");
        String parttypename = getNodeString(devNode, "parttypename");
        boolean isSsd = devNode.has("rota") && !devNode.get("rota").isNull() && !devNode.get("rota").asBoolean(true);

        String mountpoint = getMountpoint(devNode);

        dev.put("name", name);
        dev.put("path", path);
        dev.put("sizeBytes", sizeBytes);
        dev.put("formattedSize", formatBytes(sizeBytes));
        dev.put("type", type);
        dev.put("fstype", fstype);
        dev.put("label", label);
        dev.put("uuid", uuid);
        dev.put("model", model != null ? model.trim() : (label != null ? label : name));
        dev.put("serial", serial);
        dev.put("parttypename", parttypename);
        dev.put("isSsd", isSsd);
        dev.put("mountpoint", mountpoint);
        dev.put("isMounted", mountpoint != null && !mountpoint.trim().isEmpty());

        if (mountpoint != null) {
            enrichMountInfo(dev, mountpoint, rootPathMap);
        }

        List<Map<String, Object>> partitions = new ArrayList<>();
        JsonNode children = devNode.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode childNode : children) {
                Map<String, Object> part = parseDeviceNode(childNode, rootPathMap);
                if (isUsablePartition(part)) {
                    partitions.add(part);
                }
            }
        }
        dev.put("partitions", partitions);

        return dev;
    }

    /**
     * Checks if a partition is usable as server media/file storage.
     * Filters out non-storage, tiny metadata, and system reserved partitions
     * (e.g. 1MB BIOS boot, 127MB Microsoft MSR, LDM metadata, swap).
     */
    private boolean isUsablePartition(Map<String, Object> part) {
        if (part == null) return false;

        boolean isMounted = Boolean.TRUE.equals(part.get("isMounted"));
        if (isMounted) {
            return true; // Keep all actively mounted partitions visible
        }

        // Exclude system reserved and metadata partition types
        String partTypeName = (String) part.get("parttypename");
        if (partTypeName != null) {
            String lowerType = partTypeName.toLowerCase();
            if (lowerType.contains("bios boot") ||
                lowerType.contains("reserved") ||
                lowerType.contains("metadata") ||
                lowerType.contains("efi") ||
                lowerType.contains("apple_") ||
                lowerType.contains("solaris")) {
                return false;
            }
        }

        // Exclude swap and squashfs
        String fstype = (String) part.get("fstype");
        if (fstype != null) {
            String lowerFs = fstype.toLowerCase();
            if ("swap".equals(lowerFs) || "squashfs".equals(lowerFs)) {
                return false;
            }
        }

        // Exclude unmounted partitions smaller than 1 GB (1,073,741,824 bytes)
        // Storage drives/partitions for media server are always >= 1 GB
        long sizeBytes = (long) part.getOrDefault("sizeBytes", 0L);
        if (sizeBytes < 1024L * 1024L * 1024L) {
            return false;
        }

        return true;
    }

    private boolean isUsableDisk(Map<String, Object> disk) {
        if (disk == null) return false;
        boolean isMounted = Boolean.TRUE.equals(disk.get("isMounted"));
        if (isMounted) return true;
        long sizeBytes = (long) disk.getOrDefault("sizeBytes", 0L);
        return sizeBytes >= 1024L * 1024L * 1024L;
    }

    private String getMountpoint(JsonNode node) {
        if (node.has("mountpoint") && !node.get("mountpoint").isNull()) {
            String mp = node.get("mountpoint").asText();
            if (mp != null && !mp.trim().isEmpty()) return mp.trim();
        }
        if (node.has("mountpoints") && node.get("mountpoints").isArray() && node.get("mountpoints").size() > 0) {
            JsonNode first = node.get("mountpoints").get(0);
            if (first != null && !first.isNull() && !first.asText().trim().isEmpty()) {
                return first.asText().trim();
            }
        }
        return null;
    }

    private void enrichMountInfo(Map<String, Object> data, String mountpoint, Map<String, StorageRoot> rootPathMap) {
        String normalized = normalizePath(mountpoint);
        boolean isSystem = "/".equals(normalized) || "/boot".equals(normalized) || normalized.startsWith("/boot/");
        data.put("isSystem", isSystem);

        StorageRoot matchedRoot = rootPathMap.get(normalized);
        if (matchedRoot != null) {
            data.put("isAllocated", true);
            data.put("allocatedRootId", matchedRoot.getId());
            data.put("allocatedRootName", matchedRoot.getName());
            data.put("allocatedRootEnabled", matchedRoot.isEnabled());
        } else {
            data.put("isAllocated", false);
        }

        try {
            File f = new File(mountpoint);
            if (f.exists()) {
                long total = f.getTotalSpace();
                long usable = f.getUsableSpace();
                long used = total - usable;
                double percent = total > 0 ? (double) used / total * 100 : 0.0;
                data.put("totalSpace", total);
                data.put("usedSpace", used);
                data.put("usableSpace", usable);
                data.put("usePercent", String.format(Locale.US, "%.1f%%", percent));
                data.put("usePercentVal", Math.round(percent));
                data.put("formattedTotal", formatBytes(total));
                data.put("formattedUsed", formatBytes(used));
                data.put("formattedFree", formatBytes(usable));
            }
        } catch (Exception ignored) {}
    }

    private String getNodeString(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            String val = node.get(field).asText();
            return (val == null || val.trim().isEmpty()) ? null : val.trim();
        }
        return null;
    }

    private Map<String, Object> getFallbackDiskInfo(Map<String, StorageRoot> rootPathMap) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("name", "local-system");
        fallback.put("path", "/");
        fallback.put("type", "disk");
        fallback.put("model", "Host Storage");
        fallback.put("isSsd", true);
        fallback.put("mountpoint", "/");
        fallback.put("isMounted", true);
        enrichMountInfo(fallback, "/", rootPathMap);
        fallback.put("partitions", Collections.emptyList());
        return fallback;
    }

    public Map<String, Object> mountDevice(String device, String mountPath, boolean allocateAsRoot, String rootName) throws Exception {
        if (device == null || !SAFE_DEVICE_PATTERN.matcher(device.trim()).matches()) {
            throw new IllegalArgumentException("Invalid device path: " + device);
        }
        device = device.trim();

        if (mountPath == null || mountPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Mount destination path is required");
        }
        mountPath = normalizePath(mountPath.trim());

        if (!mountPath.startsWith("/")) {
            throw new IllegalArgumentException("Mount destination must be an absolute path starting with /");
        }

        if (SYSTEM_PATHS.contains(mountPath) || "/home".equals(mountPath) || "/home/sakura".equals(mountPath)) {
            throw new IllegalArgumentException("Cannot mount to protected system directory: " + mountPath);
        }

        File targetDir = new File(mountPath);
        if (!targetDir.exists()) {
            // create directory via sudo
            executeCommand(new String[]{"sudo", "mkdir", "-p", mountPath});
        }

        // Try mounting
        Map<String, Object> mountResult = null;
        try {
            // Check fstype from blkid or lsblk
            String fstype = detectFilesystem(device);
            if ("ntfs".equalsIgnoreCase(fstype) || "vfat".equalsIgnoreCase(fstype) || "exfat".equalsIgnoreCase(fstype)) {
                mountResult = executeCommand(new String[]{"sudo", "mount", "-o", "uid=1000,gid=1000,umask=0022", device, mountPath});
            } else {
                mountResult = executeCommand(new String[]{"sudo", "mount", device, mountPath});
                if ((int) mountResult.get("exitCode") == 0) {
                    executeCommand(new String[]{"sudo", "chown", "1000:1000", mountPath});
                }
            }

            if ((int) mountResult.get("exitCode") != 0) {
                // Fallback attempt with standard mount
                mountResult = executeCommand(new String[]{"sudo", "mount", device, mountPath});
            }
        } catch (Exception e) {
            throw new Exception("Mount execution failed: " + e.getMessage());
        }

        if ((int) mountResult.get("exitCode") != 0) {
            String err = (String) mountResult.get("stderr");
            throw new Exception("Failed to mount " + device + " at " + mountPath + ": " + (err != null ? err : "exit code " + mountResult.get("exitCode")));
        }

        // Auto allocate as media root if requested
        if (allocateAsRoot) {
            String finalName = (rootName != null && !rootName.trim().isEmpty())
                    ? rootName.trim()
                    : targetDir.getName();
            if (finalName.isEmpty()) finalName = "Drive-" + Paths.get(device).getFileName().toString();

            if (!storageRootRepository.existsByPath(mountPath)) {
                int nextIdx = storageRootRepository.findAllByOrderByOrderIndexAsc().size();
                StorageRoot newRoot = new StorageRoot(finalName, mountPath, true, true, nextIdx, device);
                storageRootRepository.save(newRoot);
            }
        }

        return Map.of("success", true, "message", "Device " + device + " mounted successfully at " + mountPath, "mountPath", mountPath);
    }

    public Map<String, Object> unmountDevice(String target) throws Exception {
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("Mount point or device is required to unmount");
        }
        target = target.trim();
        String normalized = normalizePath(target);

        if (SYSTEM_PATHS.contains(normalized) || "/home".equals(normalized) || "/home/sakura".equals(normalized)) {
            throw new IllegalArgumentException("Cannot unmount protected system path: " + target);
        }

        Map<String, Object> res = executeCommand(new String[]{"sudo", "umount", target});
        if ((int) res.get("exitCode") != 0) {
            String err = (String) res.get("stderr");
            throw new Exception("Failed to unmount " + target + ": " + (err != null ? err : "exit code " + res.get("exitCode")));
        }

        return Map.of("success", true, "message", "Successfully unmounted " + target);
    }

    public List<Map<String, Object>> getAllRootsWithStats() {
        List<StorageRoot> roots = storageRootRepository.findAllByOrderByOrderIndexAsc();
        List<Map<String, Object>> list = new ArrayList<>();

        for (StorageRoot root : roots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", root.getId());
            item.put("name", root.getName());
            item.put("path", root.getPath());
            item.put("allowWrite", root.isAllowWrite());
            item.put("enabled", root.isEnabled());
            item.put("orderIndex", root.getOrderIndex());
            item.put("deviceNode", root.getDeviceNode());

            File f = new File(root.getPath());
            boolean exists = f.exists() && f.isDirectory();
            item.put("exists", exists);

            if (exists) {
                long total = f.getTotalSpace();
                long usable = f.getUsableSpace();
                long used = total - usable;
                double percent = total > 0 ? (double) used / total * 100 : 0.0;
                item.put("totalSpace", total);
                item.put("usedSpace", used);
                item.put("usableSpace", usable);
                item.put("formattedTotal", formatBytes(total));
                item.put("formattedUsed", formatBytes(used));
                item.put("formattedFree", formatBytes(usable));
                item.put("usePercent", String.format(Locale.US, "%.1f%%", percent));
                item.put("usePercentVal", Math.round(percent));
            } else {
                item.put("totalSpace", 0L);
                item.put("usedSpace", 0L);
                item.put("usableSpace", 0L);
                item.put("formattedTotal", "0 B");
                item.put("formattedUsed", "0 B");
                item.put("formattedFree", "0 B");
                item.put("usePercent", "0%");
                item.put("usePercentVal", 0);
            }
            list.add(item);
        }
        return list;
    }

    public StorageRoot addRoot(String name, String path, boolean allowWrite) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Root name is required");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Root path is required");
        }
        name = name.trim();
        path = normalizePath(path.trim());

        File f = new File(path);
        if (!f.exists()) {
            throw new IllegalArgumentException("Directory path does not exist on server: " + path);
        }
        if (!f.isDirectory()) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }

        if (storageRootRepository.existsByName(name)) {
            throw new IllegalArgumentException("A root with the name '" + name + "' already exists");
        }
        if (storageRootRepository.existsByPath(path)) {
            throw new IllegalArgumentException("A root for path '" + path + "' already exists");
        }

        int nextIdx = storageRootRepository.findAllByOrderByOrderIndexAsc().size();
        StorageRoot root = new StorageRoot(name, path, allowWrite, true, nextIdx);
        return storageRootRepository.save(root);
    }

    public StorageRoot updateRoot(Long id, String name, String path, boolean allowWrite, boolean enabled) {
        StorageRoot root = storageRootRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Root not found with ID: " + id));

        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            if (!trimmed.equalsIgnoreCase(root.getName()) && storageRootRepository.existsByName(trimmed)) {
                throw new IllegalArgumentException("A root with the name '" + trimmed + "' already exists");
            }
            root.setName(trimmed);
        }

        if (path != null && !path.trim().isEmpty()) {
            String normalized = normalizePath(path.trim());
            File f = new File(normalized);
            if (!f.exists()) {
                throw new IllegalArgumentException("Directory path does not exist on server: " + normalized);
            }
            if (!normalized.equals(root.getPath()) && storageRootRepository.existsByPath(normalized)) {
                throw new IllegalArgumentException("A root for path '" + normalized + "' already exists");
            }
            root.setPath(normalized);
        }

        root.setAllowWrite(allowWrite);
        root.setEnabled(enabled);
        return storageRootRepository.save(root);
    }

    public void deleteRoot(Long id) {
        StorageRoot root = storageRootRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Root not found with ID: " + id));
        storageRootRepository.delete(root);
    }

    private String detectFilesystem(String device) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"blkid", "-s", "TYPE", "-o", "value", device});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
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

    private String normalizePath(String path) {
        if (path == null) return null;
        return Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB", "PB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
