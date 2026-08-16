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
import java.nio.file.attribute.BasicFileAttributes;
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

    @Autowired
    private com.sakuradata.media.service.ImagePreviewService imagePreviewService;

    @jakarta.annotation.PostConstruct
    public void initTempDirectories() {
        try {
            Files.createDirectories(Paths.get("./temp-uploads"));
            Files.createDirectories(Paths.get("./temp-chunks"));
        } catch (Exception ignored) {}
    }

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
                if ("write".equals(type) && p.isAllowWrite()) return true;
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
        String raw = inputPath.trim();
        File directFile = new File(raw);
        if (directFile.exists()) {
            return Paths.get(raw).toAbsolutePath().normalize().toString().replace("\\", "/");
        }
        if (raw.contains("%")) {
            try {
                String decoded = java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
                if (new File(decoded).exists()) {
                    return Paths.get(decoded).toAbsolutePath().normalize().toString().replace("\\", "/");
                }
            } catch (Exception ignored) {}
        }
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

        List<Map<String, Object>> result = new ArrayList<>();
        long folderSize = 0L;

        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = java.nio.file.Files.newDirectoryStream(folder.toPath())) {
            for (java.nio.file.Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith(".") || fileName.equals(".recycle-bin")) {
                    continue; // Hide hidden and recycle bin folders
                }
                Map<String, Object> fileMap = new HashMap<>();
                fileMap.put("name", fileName);
                try {
                    java.nio.file.attribute.BasicFileAttributes attrs = java.nio.file.Files.readAttributes(entry, java.nio.file.attribute.BasicFileAttributes.class);
                    boolean isFile = attrs.isRegularFile();
                    fileMap.put("isFile", isFile);
                    long size = isFile ? attrs.size() : 0L;
                    fileMap.put("size", size);
                    fileMap.put("mtime", attrs.lastModifiedTime().toMillis());
                    
                    if (isFile) {
                        folderSize += size;
                    }
                } catch (Exception e) {
                    // Fallback
                    fileMap.put("isFile", false);
                    fileMap.put("size", 0L);
                    fileMap.put("mtime", 0L);
                }
                result.add(fileMap);
            }
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to read directory: " + e.getMessage()));
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
        org.springframework.http.ContentDisposition contentDisposition = org.springframework.http.ContentDisposition.attachment()
                .filename(file.getName(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .headers(headers -> headers.setContentDisposition(contentDisposition))
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
                    long rangeLength = Math.min(1024 * 1024 * 5, end - start + 1); // 5MB chunk for smooth streaming & seeking

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

    @GetMapping("/preview")
    public ResponseEntity<Resource> preview(HttpServletRequest request,
                                            @RequestParam String path,
                                            @RequestParam(defaultValue = "1600") int maxDim,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
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

        File previewFile = imagePreviewService.getOrCreatePreview(file, maxDim);
        if (previewFile == null || !previewFile.exists()) {
            previewFile = file;
        }

        String etag = "\"" + previewFile.getName() + "-" + previewFile.lastModified() + "-" + previewFile.length() + "\"";
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String contentType = previewFile.getName().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        FileSystemResource resource = new FileSystemResource(previewFile);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800, immutable")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .eTag(etag)
                .contentLength(previewFile.length())
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String decodeBase64Path(String base64Path) {
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(base64Path);
            String raw = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return resolvePath(raw);
        } catch (Exception e) {
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(base64Path);
                String raw = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                return resolvePath(raw);
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

        String targetPath = resolvePath(path);
        if (targetPath == null || targetPath.equals(SAKURA_ROOT) || targetPath.equals(STORAGE_ROOT) || targetPath.equals(HDD_ROOT) || targetPath.equals(GDRIVE_ROOT)) {
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
        org.springframework.http.ContentDisposition contentDisposition = org.springframework.http.ContentDisposition.attachment()
                .filename(folder.getName() + ".zip", java.nio.charset.StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());

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
            byte[] bytes = new byte[65536];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zipOut.write(bytes, 0, length);
            }
            zipOut.closeEntry();
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

        String targetDir = resolvePath(pathParam);
        if (targetDir == null || !hasPermission(user, targetDir, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        Path newFolderPath = Paths.get(targetDir, name.trim());
        if (Files.exists(newFolderPath)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A folder with this name already exists"));
        }

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

        String resolvedTargetDir = resolvePath(targetDirectory);
        if (resolvedTargetDir == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target directory"));
        }

        String finalFileDestination = Paths.get(resolvedTargetDir, cleanRelativePath).toAbsolutePath().normalize().toString().replace("\\", "/");
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

                // Delete temp chunks folder after merging
                deleteRecursively(chunkFolder);

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

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "relativePath", required = false) String relativePath,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam("path") String targetDirectory) {

        User user = (User) request.getAttribute("user");

        String finalRelPath = relativePath;
        if (finalRelPath == null || finalRelPath.trim().isEmpty()) {
            finalRelPath = filename;
        }
        if (finalRelPath == null || finalRelPath.trim().isEmpty()) {
            finalRelPath = file.getOriginalFilename();
        }
        if (finalRelPath == null || finalRelPath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "relativePath or filename parameter is required"));
        }

        // Clean relativePath boundaries
        String cleanRelativePath = finalRelPath.replace("\\", "/");
        if (cleanRelativePath.startsWith("/")) {
            cleanRelativePath = cleanRelativePath.substring(1);
        }

        String resolvedTargetDir = resolvePath(targetDirectory);
        if (resolvedTargetDir == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target directory"));
        }

        String finalFileDestination = Paths.get(resolvedTargetDir, cleanRelativePath).toAbsolutePath().normalize().toString().replace("\\", "/");
        String finalDirDestination = Paths.get(finalFileDestination).getParent().toString().replace("\\", "/");

        if (!hasPermission(user, finalDirDestination, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        try {
            Path finalPath = Paths.get(finalFileDestination);
            Files.createDirectories(finalPath.getParent());

            java.io.InputStream inputStream = file.getInputStream();
            if (user != null && user.getUploadBandwidthLimit() != null && user.getUploadBandwidthLimit() > 0) {
                inputStream = new com.sakuradata.media.util.ThrottledInputStream(inputStream, (long) (user.getUploadBandwidthLimit() * 1024 * 1024));
            }

            Files.copy(inputStream, finalPath, StandardCopyOption.REPLACE_EXISTING);

            String parentPath = finalPath.getParent().toAbsolutePath().normalize().toString().replace("\\", "/");
            SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", parentPath));

            return ResponseEntity.ok(Map.of("success", true));
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteFile(HttpServletRequest request, @RequestParam String path) {
        User user = (User) request.getAttribute("user");
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        String targetPath = resolvePath(path);
        if (targetPath == null || !hasPermission(user, targetPath, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        File file = new File(targetPath);
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File not found"));
        }

        try {
            File recycleBinFolder = getRecycleBinFolderForPath(targetPath);
            String tempName = UUID.randomUUID().toString() + "_" + file.getName();
            File dest = new File(recycleBinFolder, tempName);
            
            boolean moved = file.renameTo(dest);
            if (!moved) {
                try {
                    Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    moved = true;
                } catch (Exception e) {
                    try {
                        copyRecursively(file.toPath(), dest.toPath(), true);
                        deleteRecursively(file.toPath());
                        moved = true;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
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

        String targetPath = resolvePath(path);
        if (targetPath == null || !hasPermission(user, targetPath, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        File file = new File(targetPath);
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File or folder not found"));
        }

        File parentDir = file.getParentFile();
        File destFile = new File(parentDir, newName.trim());

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

    @PostMapping("/copy")
    public ResponseEntity<?> copyFiles(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        User user = (User) request.getAttribute("user");
        Object sourcesObj = body.get("sources");
        String destination = (String) body.get("destination");
        String taskId = (String) body.get("taskId");
        boolean overwrite = Boolean.TRUE.equals(body.get("overwrite"));

        if (sourcesObj == null || destination == null || destination.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Sources and destination are required"));
        }

        List<String> sources = new ArrayList<>();
        if (sourcesObj instanceof List<?>) {
            for (Object item : (List<?>) sourcesObj) {
                if (item != null) sources.add(item.toString());
            }
        } else if (sourcesObj instanceof String) {
            sources.add((String) sourcesObj);
        }

        if (sources.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No source files/folders provided"));
        }

        String targetDirStr = resolvePath(destination);
        if (targetDirStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid destination path"));
        }

        if (!hasPermission(user, targetDirStr, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Write permission denied for destination"));
        }

        File targetDir = new File(targetDirStr);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Destination must be an existing directory"));
        }

        List<File> validSourceFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String srcStr : sources) {
            String resolvedSrc = resolvePath(srcStr);
            if (resolvedSrc != null) {
                File f = new File(resolvedSrc);
                if (!f.exists()) {
                    errors.add("Source file or folder not found: " + srcStr);
                } else if (!hasPermission(user, resolvedSrc, "read")) {
                    errors.add("Permission denied for source: " + srcStr);
                } else {
                    validSourceFiles.add(f);
                }
            } else {
                errors.add("Invalid source path: " + srcStr);
            }
        }

        if (validSourceFiles.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "errors", errors.isEmpty() ? List.of("No valid source files found") : errors, "copiedCount", 0));
        }

        long totalBytes = calculateTotalBytes(validSourceFiles);
        int totalFiles = countTotalFiles(validSourceFiles);
        ProgressTracker tracker = new ProgressTracker(taskId, "copy", totalBytes, totalFiles);
        tracker.broadcastProgress("Starting copy...", false);

        int copiedCount = 0;

        for (File srcFile : validSourceFiles) {
            String resolvedSrc = srcFile.getAbsolutePath().replace("\\", "/");

            // Prevent circular copy
            if (srcFile.isDirectory() && isSubPath(resolvedSrc, targetDirStr)) {
                errors.add("Cannot copy a directory into itself or its subdirectories: " + srcFile.getName());
                continue;
            }

            Path destPath = targetDir.toPath().resolve(srcFile.getName());
            try {
                copyRecursively(srcFile.toPath(), destPath, overwrite, tracker);
                copiedCount++;
            } catch (Exception e) {
                errors.add("Failed to copy " + srcFile.getName() + ": " + e.getMessage());
            }
        }

        tracker.broadcastProgress("Completed", true);
        SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", targetDirStr));

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", errors.isEmpty());
        resp.put("copiedCount", copiedCount);
        if (!errors.isEmpty()) {
            resp.put("errors", errors);
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/move")
    public ResponseEntity<?> moveFiles(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        User user = (User) request.getAttribute("user");
        Object sourcesObj = body.get("sources");
        String destination = (String) body.get("destination");
        String taskId = (String) body.get("taskId");
        boolean overwrite = Boolean.TRUE.equals(body.get("overwrite"));

        if (sourcesObj == null || destination == null || destination.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Sources and destination are required"));
        }

        List<String> sources = new ArrayList<>();
        if (sourcesObj instanceof List<?>) {
            for (Object item : (List<?>) sourcesObj) {
                if (item != null) sources.add(item.toString());
            }
        } else if (sourcesObj instanceof String) {
            sources.add((String) sourcesObj);
        }

        if (sources.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No source files/folders provided"));
        }

        String targetDirStr = resolvePath(destination);
        if (targetDirStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid destination path"));
        }

        if (!hasPermission(user, targetDirStr, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Write permission denied for destination"));
        }

        File targetDir = new File(targetDirStr);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Destination must be an existing directory"));
        }

        List<File> validSourceFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String srcStr : sources) {
            String resolvedSrc = resolvePath(srcStr);
            if (resolvedSrc != null) {
                File f = new File(resolvedSrc);
                if (!f.exists()) {
                    errors.add("Source file or folder not found: " + srcStr);
                } else if (!hasPermission(user, resolvedSrc, "write")) {
                    errors.add("Permission denied for source: " + srcStr);
                } else {
                    validSourceFiles.add(f);
                }
            } else {
                errors.add("Invalid source path: " + srcStr);
            }
        }

        if (validSourceFiles.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "errors", errors.isEmpty() ? List.of("No valid source files found") : errors, "movedCount", 0));
        }

        long totalBytes = calculateTotalBytes(validSourceFiles);
        int totalFiles = countTotalFiles(validSourceFiles);
        ProgressTracker tracker = new ProgressTracker(taskId, "move", totalBytes, totalFiles);
        tracker.broadcastProgress("Starting move...", false);

        int movedCount = 0;
        Set<String> affectedParents = new HashSet<>();
        affectedParents.add(targetDirStr);

        for (File srcFile : validSourceFiles) {
            String resolvedSrc = srcFile.getAbsolutePath().replace("\\", "/");

            // Prevent circular move
            if (srcFile.isDirectory() && isSubPath(resolvedSrc, targetDirStr)) {
                errors.add("Cannot move a directory into itself or its subdirectories: " + srcFile.getName());
                continue;
            }

            if (srcFile.getParentFile() != null) {
                affectedParents.add(srcFile.getParentFile().getAbsolutePath().replace("\\", "/"));
            }

            Path destPath = targetDir.toPath().resolve(srcFile.getName());
            try {
                moveRecursively(srcFile.toPath(), destPath, overwrite, tracker);
                movedCount++;
            } catch (Exception e) {
                errors.add("Failed to move " + srcFile.getName() + ": " + e.getMessage());
            }
        }

        tracker.broadcastProgress("Completed", true);
        for (String p : affectedParents) {
            SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", p));
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", errors.isEmpty());
        resp.put("movedCount", movedCount);
        if (!errors.isEmpty()) {
            resp.put("errors", errors);
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<?> batchDelete(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        User user = (User) request.getAttribute("user");
        Object pathsObj = body.get("paths");
        if (pathsObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Paths list is required"));
        }

        List<String> paths = new ArrayList<>();
        if (pathsObj instanceof List<?>) {
            for (Object item : (List<?>) pathsObj) {
                if (item != null) paths.add(item.toString());
            }
        }

        if (paths.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No paths provided"));
        }

        int deletedCount = 0;
        List<String> errors = new ArrayList<>();
        Set<String> affectedParents = new HashSet<>();

        for (String path : paths) {
            String targetPath = resolvePath(path);
            if (targetPath == null) continue;

            if (!hasPermission(user, targetPath, "write")) {
                errors.add("Permission denied: " + path);
                continue;
            }

            File file = new File(targetPath);
            if (!file.exists()) {
                continue;
            }

            try {
                File recycleBinFolder = getRecycleBinFolderForPath(targetPath);
                String tempName = UUID.randomUUID().toString() + "_" + file.getName();
                File dest = new File(recycleBinFolder, tempName);
                
                boolean moved = file.renameTo(dest);
                if (!moved) {
                    try {
                        Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        moved = true;
                    } catch (Exception e) {
                        try {
                            copyRecursively(file.toPath(), dest.toPath(), true);
                            deleteRecursively(file.toPath());
                            moved = true;
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
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
                    deletedCount++;

                    if (file.getParentFile() != null) {
                        affectedParents.add(file.getParentFile().getAbsolutePath().replace("\\", "/"));
                    }
                }
            } catch (Exception e) {
                errors.add("Failed to delete " + file.getName() + ": " + e.getMessage());
            }
        }

        for (String p : affectedParents) {
            SseController.broadcast("fs-change", Map.of("userId", user.getId(), "parentPath", p));
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", errors.isEmpty());
        resp.put("deletedCount", deletedCount);
        if (!errors.isEmpty()) {
            resp.put("errors", errors);
        }
        return ResponseEntity.ok(resp);
    }

    @RequestMapping(value = "/download-batch", method = {RequestMethod.GET, RequestMethod.POST})
    public void downloadBatch(HttpServletRequest request, 
                              HttpServletResponse response) throws IOException {
        User user = (User) request.getAttribute("user");
        List<String> paths = new ArrayList<>();
        
        // 1. Check form / query parameters
        String[] reqPaths = request.getParameterValues("paths");
        if (reqPaths != null) {
            for (String p : reqPaths) {
                if (p != null && !p.trim().isEmpty()) paths.add(p);
            }
        }

        // 2. If empty and JSON content-type, parse from request input stream
        if (paths.isEmpty()) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, Object> body = mapper.readValue(request.getInputStream(), Map.class);
                    if (body != null && body.get("paths") != null) {
                        Object pathsObj = body.get("paths");
                        if (pathsObj instanceof List<?>) {
                            for (Object item : (List<?>) pathsObj) {
                                if (item != null) paths.add(item.toString());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        if (paths.isEmpty()) {
            response.sendError(400, "No paths provided");
            return;
        }

        response.setContentType("application/zip");
        String folderName = "media";
        try {
            if (!paths.isEmpty()) {
                String firstPath = paths.get(0);
                File f = new File(firstPath);
                File parent = f.getParentFile();
                if (parent != null && !parent.getName().isEmpty()) {
                    folderName = parent.getName();
                }
            }
        } catch (Exception ignored) {}

        String zipName = folderName + "_batch_" + System.currentTimeMillis() + ".zip";
        org.springframework.http.ContentDisposition contentDisposition = org.springframework.http.ContentDisposition.attachment()
                .filename(zipName, java.nio.charset.StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (String pStr : paths) {
                String targetPath = resolvePath(pStr);
                if (targetPath == null || !hasPermission(user, targetPath, "read")) continue;

                File file = new File(targetPath);
                if (!file.exists()) continue;

                if (file.isDirectory()) {
                    zipFolder(file, file.getName(), zos);
                } else {
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
        } catch (Exception e) {
            // Client disconnect or stream finished
        }
    }

    private void copyRecursively(Path source, Path target, boolean overwrite) throws IOException {
        copyRecursively(source, target, overwrite, null);
    }

    private void copyRecursively(Path source, Path target, boolean overwrite, ProgressTracker tracker) throws IOException {
        if (Files.isDirectory(source)) {
            if (!Files.exists(target)) {
                Files.createDirectories(target);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path entry : stream) {
                    Path destEntry = target.resolve(entry.getFileName());
                    copyRecursively(entry, destEntry, overwrite, tracker);
                }
            }
        } else {
            Path finalTarget = target;
            if (!overwrite && Files.exists(finalTarget)) {
                finalTarget = getUniqueDestinationPath(finalTarget);
            }
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(finalTarget)) {
                copyStreamWithProgress(in, out, source.getFileName().toString(), tracker);
            }
            if (tracker != null) {
                tracker.fileCompleted(source.getFileName().toString());
            }
        }
    }

    private void copyStreamWithProgress(InputStream in, OutputStream out, String filename, ProgressTracker tracker) throws IOException {
        byte[] buffer = new byte[1024 * 1024]; // 1MB buffer for fast I/O
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            if (tracker != null) {
                tracker.addBytes(bytesRead, filename);
            }
        }
    }

    private File getRecycleBinFolderForPath(String path) {
        String normalized = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
        List<String> knownRoots = List.of("/media/storage", "/media/hdd", "/media/gdrive", "/home/sakura");
        String matchedRoot = "/home/sakura";
        for (String r : knownRoots) {
            if (normalized.equals(r) || normalized.startsWith(r + "/")) {
                matchedRoot = r;
                break;
            }
        }
        File bin = new File(matchedRoot, ".recycle-bin");
        if (!bin.exists()) {
            bin.mkdirs();
        }
        return bin;
    }

    private void moveRecursively(Path source, Path target, boolean overwrite) throws IOException {
        moveRecursively(source, target, overwrite, null);
    }

    private void moveRecursively(Path source, Path target, boolean overwrite, ProgressTracker tracker) throws IOException {
        if (source.equals(target)) return;

        Path finalTarget = target;
        if (!overwrite && Files.exists(finalTarget)) {
            finalTarget = getUniqueDestinationPath(finalTarget);
        }

        try {
            Files.move(source, finalTarget, overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.ATOMIC_MOVE);
            if (tracker != null) {
                long size = Files.isDirectory(finalTarget) ? calculateDirectorySize(finalTarget.toFile()) : Files.size(finalTarget);
                tracker.addBytes(size, source.getFileName().toString());
                tracker.fileCompleted(source.getFileName().toString());
            }
            return;
        } catch (Exception ignored) {
            // Cross-filesystem fallback
        }

        copyRecursively(source, finalTarget, overwrite, tracker);
        deleteRecursively(source);
    }

    private long calculateTotalBytes(List<File> files) {
        long total = 0;
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            } else if (f.isDirectory()) {
                total += calculateDirectorySize(f);
            }
        }
        return total;
    }

    private long calculateDirectorySize(File dir) {
        long length = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    length += file.length();
                } else if (file.isDirectory()) {
                    length += calculateDirectorySize(file);
                }
            }
        }
        return length;
    }

    private int countTotalFiles(List<File> files) {
        int count = 0;
        for (File f : files) {
            if (f.isFile()) {
                count++;
            } else if (f.isDirectory()) {
                count += countFilesInDirectory(f);
            }
        }
        return Math.max(1, count);
    }

    private int countFilesInDirectory(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    count++;
                } else if (file.isDirectory()) {
                    count += countFilesInDirectory(file);
                }
            }
        }
        return count;
    }

    private Path getUniqueDestinationPath(Path target) {
        String filename = target.getFileName().toString();
        Path parent = target.getParent();
        String name = filename;
        String ext = "";
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0 && !Files.isDirectory(target)) {
            name = filename.substring(0, dotIdx);
            ext = filename.substring(dotIdx);
        }
        int count = 1;
        Path newTarget = target;
        while (Files.exists(newTarget)) {
            newTarget = parent.resolve(name + " (Copy " + count + ")" + ext);
            count++;
        }
        return newTarget;
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    Files.delete(file);
                } catch (Exception e) {
                    file.toFile().setWritable(true);
                    file.toFile().delete();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                try {
                    Files.delete(dir);
                } catch (Exception e) {
                    dir.toFile().setWritable(true);
                    dir.toFile().delete();
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static class ProgressTracker {
        final String taskId;
        final String action;
        final long totalBytes;
        final int totalFiles;
        long copiedBytes = 0;
        int copiedFiles = 0;
        long lastBroadcastTime = 0;
        long startTime = System.currentTimeMillis();

        ProgressTracker(String taskId, String action, long totalBytes, int totalFiles) {
            this.taskId = taskId;
            this.action = action;
            this.totalBytes = Math.max(1, totalBytes);
            this.totalFiles = Math.max(1, totalFiles);
        }

        synchronized void addBytes(long bytes, String currentFile) {
            this.copiedBytes += bytes;
            long now = System.currentTimeMillis();
            if (now - lastBroadcastTime > 150) { // Broadcast max ~6 times per second
                lastBroadcastTime = now;
                broadcastProgress(currentFile, false);
            }
        }

        synchronized void fileCompleted(String currentFile) {
            this.copiedFiles++;
            broadcastProgress(currentFile, false);
        }

        void broadcastProgress(String currentFile, boolean completed) {
            try {
                int percent = (int) Math.min(100, (copiedBytes * 100) / totalBytes);
                if (completed) percent = 100;
                
                long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                double speedMbps = elapsedSec > 0 ? (copiedBytes / (1024.0 * 1024.0)) / elapsedSec : 0;
                String speedStr = speedMbps > 0 ? String.format(Locale.US, "%.1f MB/s", speedMbps) : "";

                SseController.broadcast("file-op-progress", Map.of(
                    "taskId", taskId != null ? taskId : "",
                    "action", action,
                    "currentFile", currentFile != null ? currentFile : "",
                    "copiedBytes", copiedBytes,
                    "totalBytes", totalBytes,
                    "copiedFiles", copiedFiles,
                    "totalFiles", totalFiles,
                    "percent", percent,
                    "speed", speedStr,
                    "completed", completed
                ));
            } catch (Throwable ignored) {}
        }
    }
}
