package com.sakuradata.media.controller;

import com.sakuradata.media.model.Permission;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.PermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String SAKURA_ROOT = "/home/sakura";
    private static final String STORAGE_ROOT = "/media/storage";
    private static final String HDD_ROOT = "/media/hdd";
    private static final String GDRIVE_ROOT = "/media/gdrive";
    private static final String TEMP_CHUNKS_DIR = "./temp-chunks";

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private com.sakuradata.media.repository.RecycleItemRepository recycleItemRepository;

    // Helper to check subpath relation
    private boolean isSubPath(String parentStr, String childStr) {
        try {
            Path parent = Paths.get(parentStr).toRealPath();
            Path child = Paths.get(childStr).toRealPath();
            return child.startsWith(parent);
        } catch (Exception e) {
            String parent = Paths.get(parentStr).toAbsolutePath().normalize().toString().replace("\\", "/");
            String child = Paths.get(childStr).toAbsolutePath().normalize().toString().replace("\\", "/");
            return child.equals(parent) || child.startsWith(parent + "/");
        }
    }

    // Get all authorized roots for the current user
    private List<Map<String, Object>> getAuthorizedRoots(User user) {
        List<Map<String, Object>> roots = new ArrayList<>();
        if ("admin".equals(user.getRole())) {
            roots.add(Map.of("name", "Home root", "path", SAKURA_ROOT, "allowWrite", true));
            roots.add(Map.of("name", "Storage root", "path", STORAGE_ROOT, "allowWrite", true));
            roots.add(Map.of("name", "HDD root", "path", HDD_ROOT, "allowWrite", true));
            roots.add(Map.of("name", "Google Drive", "path", GDRIVE_ROOT, "allowWrite", true));
        } else {
            List<Permission> perms = permissionRepository.findByUserId(user.getId());
            for (Permission p : perms) {
                if (p.isAllowRead()) {
                    File file = new File(p.getPath());
                    Map<String, Object> r = new HashMap<>();
                    r.put("name", file.getName().isEmpty() ? p.getPath() : file.getName());
                    r.put("path", p.getPath());
                    r.put("allowWrite", p.isAllowWrite());
                    roots.add(r);
                }
            }
        }
        return roots;
    }

    // Validate if the user has access permission (read or write) to the path
    private boolean hasPermission(User user, String pathStr, String type) {
        if ("admin".equals(user.getRole())) {
            return true;
        }

        String resolved = Paths.get(pathStr).toAbsolutePath().normalize().toString().replace("\\", "/");
        List<Permission> perms = permissionRepository.findByUserId(user.getId());

        for (Permission p : perms) {
            if (isSubPath(p.getPath(), resolved)) {
                if ("read".equals(type) && p.isAllowRead()) return true;
                if ("write".equals(type) && (p.isAllowWrite() || p.isAllowRead())) return true;
            }
        }
        return false;
    }

    @GetMapping("/roots")
    public ResponseEntity<?> getRoots(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        return ResponseEntity.ok(getAuthorizedRoots(user));
    }

    private String resolvePath(String inputPath) {
        if (inputPath == null || inputPath.trim().isEmpty()) return null;
        String raw = inputPath;
        try {
            raw = java.net.URLDecoder.decode(inputPath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return Paths.get(raw).toAbsolutePath().normalize().toString().replace("\\", "/");
    }

    @GetMapping("/browse")
    public ResponseEntity<?> browse(HttpServletRequest request, @RequestParam String path) {
        User user = (User) request.getAttribute("user");
        String targetPath = resolvePath(path);
        if (targetPath == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        if (!hasPermission(user, targetPath, "read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        File folder = new File(targetPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Directory not found"));
        }

        File[] files = folder.listFiles();
        List<Map<String, Object>> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                Map<String, Object> fileMap = new HashMap<>();
                fileMap.put("name", file.getName());
                fileMap.put("isFile", file.isFile());
                fileMap.put("size", file.isFile() ? file.length() : 0L);
                fileMap.put("mtime", file.lastModified());
                result.add(fileMap);
            }
        }

        // Sort folders first, then files alphabetically
        result.sort((a, b) -> {
            boolean aIsFile = (boolean) a.get("isFile");
            boolean bIsFile = (boolean) b.get("isFile");
            if (aIsFile != bIsFile) {
                return aIsFile ? 1 : -1;
            }
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        long folderSize = 0L;
        try (java.util.stream.Stream<Path> stream = Files.walk(Paths.get(targetPath))) {
            folderSize = stream.filter(p -> Files.isRegularFile(p))
                               .mapToLong(p -> {
                                   try {
                                       return Files.size(p);
                                   } catch (Exception e) {
                                       return 0L;
                                   }
                               })
                               .sum();
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
                "currentPath", targetPath,
                "folderSize", folderSize,
                "files", result
        ));
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(HttpServletRequest request, @RequestParam String path) {
        User user = (User) request.getAttribute("user");
        String targetPath = resolvePath(path);
        if (targetPath == null) {
            return ResponseEntity.badRequest().build();
        }

        if (!hasPermission(user, targetPath, "read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        File file = new File(targetPath);
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource;
        if (user != null && user.getDownloadBandwidthLimit() != null && user.getDownloadBandwidthLimit() > 0) {
            resource = new com.sakuradata.media.util.ThrottledFileSystemResource(file, (long) (user.getDownloadBandwidthLimit() * 1024 * 1024));
        } else {
            resource = new FileSystemResource(file);
        }
        String contentType;
        String filenameLower = file.getName().toLowerCase();
        if (filenameLower.endsWith(".mkv") || filenameLower.contains(".mkv.")) {
            contentType = "video/x-matroska";
        } else {
            contentType = getCustomMimeType(targetPath, request);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .contentLength(file.length())
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String getCustomMimeType(String filePath, HttpServletRequest request) {
        String filename = Paths.get(filePath).getFileName().toString().toLowerCase();
        if (filename.endsWith(".mkv") || filename.contains(".mkv.")) {
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null) {
                String uaLower = userAgent.toLowerCase();
                if ((uaLower.contains("chrome") || uaLower.contains("chromium")) && !uaLower.contains("vlc") && !uaLower.contains("libvlc")) {
                    return "video/webm";
                }
            }
            return "video/x-matroska";
        }
        if (filename.endsWith(".mp4") || filename.endsWith(".m4v")) return "video/mp4";
        if (filename.endsWith(".webm")) return "video/webm";
        if (filename.endsWith(".mov")) return "video/quicktime";
        if (filename.endsWith(".avi")) return "video/x-msvideo";
        if (filename.endsWith(".flv")) return "video/x-flv";
        if (filename.endsWith(".ts")) return "video/mp2t";
        if (filename.endsWith(".3gp")) return "video/3gpp";
        if (filename.endsWith(".ogv")) return "video/ogg";
        if (filename.endsWith(".m3u8")) return "application/x-mpegurl";
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        if (filename.endsWith(".flac")) return "audio/flac";
        if (filename.endsWith(".aac")) return "audio/aac";
        if (filename.endsWith(".ogg") || filename.endsWith(".oga")) return "audio/ogg";
        if (filename.endsWith(".m4a")) return "audio/mp4";
        if (filename.endsWith(".wav")) return "audio/wav";

        String contentType = request.getServletContext().getMimeType(filePath);
        return (contentType != null) ? contentType : "application/octet-stream";
    }

    private ResponseEntity<ResourceRegion> handleStreamRequest(HttpServletRequest request, 
                                                               String targetPath, 
                                                               String rangeHeader, 
                                                               User user) {
        if (targetPath == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!hasPermission(user, targetPath, "read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        File file = new File(targetPath);
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = getCustomMimeType(targetPath, request);
        FileSystemResource resource;
        if (user != null && user.getDownloadBandwidthLimit() != null && user.getDownloadBandwidthLimit() > 0) {
            resource = new com.sakuradata.media.util.ThrottledFileSystemResource(file, (long) (user.getDownloadBandwidthLimit() * 1024 * 1024));
        } else {
            resource = new FileSystemResource(file);
        }
        long fileLength = file.length();

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (!ranges.isEmpty()) {
                    HttpRange range = ranges.get(0);
                    long start = range.getRangeStart(fileLength);
                    long end = range.getRangeEnd(fileLength);
                    long rangeLength = end - start + 1;

                    ResourceRegion region = new ResourceRegion(resource, start, rangeLength);

                    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*")
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(region);
                }
            } catch (Exception e) {
                // Ignore range parsing error and fall through to full response
            }
        }

        ResourceRegion entireRegion = new ResourceRegion(resource, 0, fileLength);
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*")
                .contentType(MediaType.parseMediaType(contentType))
                .body(entireRegion);
    }

    @GetMapping("/stream")
    public ResponseEntity<ResourceRegion> stream(HttpServletRequest request, 
                                                 @RequestParam String path, 
                                                 @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        User user = (User) request.getAttribute("user");
        String targetPath = resolvePath(path);
        return handleStreamRequest(request, targetPath, rangeHeader, user);
    }

    private String decodeBase64Path(String base64Path) {
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(base64Path);
            String raw = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(base64Path);
                String raw = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return null;
    }

    @GetMapping("/stream-media/{base64Path}")
    public ResponseEntity<ResourceRegion> streamMedia(HttpServletRequest request,
                                                     @PathVariable String base64Path,
                                                     @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        User user = (User) request.getAttribute("user");
        String targetPath = decodeBase64Path(base64Path);
        return handleStreamRequest(request, targetPath, rangeHeader, user);
    }

    @GetMapping("/download-folder")
    public void downloadFolder(HttpServletRequest request, HttpServletResponse response, @RequestParam String path) throws IOException {
        User user = (User) request.getAttribute("user");
        if (path == null || path.trim().isEmpty()) {
            response.sendError(400, "Path is required");
            return;
        }

        String targetPath = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (targetPath.equals(SAKURA_ROOT) || targetPath.equals(STORAGE_ROOT) || targetPath.equals(HDD_ROOT) || targetPath.equals(GDRIVE_ROOT)) {
            response.sendError(403, "Cannot download root directories directly");
            return;
        }

        if (!hasPermission(user, targetPath, "read")) {
            response.sendError(403, "Permission denied");
            return;
        }

        File folder = new File(targetPath);
        if (!folder.exists() || !folder.isDirectory()) {
            response.sendError(404, "Folder not found");
            return;
        }

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + folder.getName() + ".zip\"");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            zipFolder(folder, folder.getName(), zos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void zipFolder(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFolder(childFile, fileName + "/" + childFile.getName(), zipOut);
                }
            }
            return;
        }
        try (FileInputStream fis = new FileInputStream(fileToZip)) {
            ZipEntry zipEntry = new ZipEntry(fileName);
            zipOut.putNextEntry(zipEntry);
            byte[] bytes = new byte[4096];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
        }
    }

    @PostMapping("/mkdir")
    public ResponseEntity<?> mkdir(HttpServletRequest request, @RequestBody Map<String, String> body) {
        User user = (User) request.getAttribute("user");
        String pathParam = body.get("path");
        String name = body.get("name");

        if (pathParam == null || name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path and name are required"));
        }

        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid characters in new folder name"));
        }

        String targetDir = Paths.get(pathParam).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!hasPermission(user, targetDir, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        Path newFolderPath = Paths.get(targetDir, name);
        try {
            Files.createDirectories(newFolderPath);
            SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", targetDir));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to create folder: " + e.getMessage()));
        }
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("uploadId") String uploadId,
            @RequestParam(value = "relativePath", required = false) String relativePath,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam("path") String targetDirectory) {

        User user = (User) request.getAttribute("user");

        String finalRelPath = relativePath;
        if (finalRelPath == null || finalRelPath.trim().isEmpty()) {
            finalRelPath = filename;
        }
        if (finalRelPath == null || finalRelPath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "relativePath or filename parameter is required"));
        }

        // Clean relativePath boundaries
        String cleanRelativePath = finalRelPath.replace("\\", "/");
        if (cleanRelativePath.startsWith("/")) {
            cleanRelativePath = cleanRelativePath.substring(1);
        }

        String finalFileDestination = Paths.get(targetDirectory, cleanRelativePath).toAbsolutePath().normalize().toString().replace("\\", "/");
        String finalDirDestination = Paths.get(finalFileDestination).getParent().toString().replace("\\", "/");

        if (!hasPermission(user, finalDirDestination, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        Path chunkFolder = Paths.get(TEMP_CHUNKS_DIR, uploadId);
        try {
            Files.createDirectories(chunkFolder);
            Path chunkFile = chunkFolder.resolve(String.valueOf(chunkIndex));
            java.io.InputStream inputStream = file.getInputStream();
            if (user != null && user.getUploadBandwidthLimit() != null && user.getUploadBandwidthLimit() > 0) {
                inputStream = new com.sakuradata.media.util.ThrottledInputStream(inputStream, (long) (user.getUploadBandwidthLimit() * 1024 * 1024));
            }
            Files.copy(inputStream, chunkFile, StandardCopyOption.REPLACE_EXISTING);

            // Check if all chunks have been uploaded
            File[] uploadedChunks = new File(chunkFolder.toString()).listFiles();
            int uploadedCount = (uploadedChunks != null) ? uploadedChunks.length : 0;

            if (uploadedCount == totalChunks) {
                // Merge chunks synchronously to complete action before request termination
                Path finalPath = Paths.get(finalFileDestination);
                Files.createDirectories(finalPath.getParent());

                try (BufferedOutputStream destStream = new BufferedOutputStream(new FileOutputStream(finalPath.toFile()))) {
                    for (int i = 0; i < totalChunks; i++) {
                        Path partFile = chunkFolder.resolve(String.valueOf(i));
                        Files.copy(partFile, destStream);
                    }
                }

                // Delete chunks folder recursively
                try (java.util.stream.Stream<Path> stream = Files.walk(chunkFolder)) {
                    stream.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }

                String parentPath = finalPath.getParent().toAbsolutePath().normalize().toString().replace("\\", "/");
                SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", parentPath));

                return ResponseEntity.ok(Map.of("success", true, "merged", true));
            }

            return ResponseEntity.ok(Map.of("success", true, "merged", false));
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to upload chunk: " + e.getMessage()));
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteFile(HttpServletRequest request, @RequestParam String path) {
        User user = (User) request.getAttribute("user");
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        String targetPath = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!hasPermission(user, targetPath, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        File file = new File(targetPath);
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File not found"));
        }

        try {
            File recycleBinFolder = new File("./recycle-bin");
            if (!recycleBinFolder.exists()) {
                recycleBinFolder.mkdirs();
            }
            String tempName = UUID.randomUUID().toString() + "_" + file.getName();
            File dest = new File(recycleBinFolder, tempName);
            
            boolean moved = file.renameTo(dest);
            if (!moved) {
                try {
                    Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    moved = true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            
            if (moved) {
                com.sakuradata.media.model.RecycleItem item = new com.sakuradata.media.model.RecycleItem(
                    user.getId(),
                    targetPath,
                    file.getName(),
                    dest.getAbsolutePath(),
                    java.time.LocalDateTime.now(),
                    dest.isDirectory() ? null : dest.length(),
                    dest.isDirectory()
                );
                recycleItemRepository.save(item);

                // Broadcast change event for parent path
                String parentPath = file.getParentFile() != null ? file.getParentFile().getAbsolutePath().replace("\\", "/") : "";
                SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", parentPath));

                return ResponseEntity.ok(Map.of("success", true, "recycled", true));
            } else {
                return ResponseEntity.status(500).body(Map.of("error", "Failed to move file to recycle bin."));
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete: " + e.getMessage()));
        }
    }

    @PostMapping("/rename")
    public ResponseEntity<?> renameFile(HttpServletRequest request, 
                                        @RequestParam String path, 
                                        @RequestParam String newName) {
        User user = (User) request.getAttribute("user");
        if (path == null || path.trim().isEmpty() || newName == null || newName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path and newName are required"));
        }

        // Clean newName to prevent path traversal
        if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid characters in new name"));
        }

        String targetPath = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!hasPermission(user, targetPath, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        File file = new File(targetPath);
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File or folder not found"));
        }

        File parentDir = file.getParentFile();
        File destFile = new File(parentDir, newName);

        if (destFile.exists()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A file or folder with the new name already exists"));
        }

        try {
            boolean success = file.renameTo(destFile);
            if (!success) {
                Files.move(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                success = true;
            }
            if (success) {
                // Broadcast change event for parent path
                String parentPath = parentDir.getAbsolutePath().replace("\\", "/");
                SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", parentPath));

                return ResponseEntity.ok(Map.of("success", true, "newPath", destFile.getAbsolutePath().replace("\\", "/")));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to rename"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + file.getAbsolutePath());
        }
    }
}
