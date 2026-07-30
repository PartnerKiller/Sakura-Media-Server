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
    private static final String TEMP_CHUNKS_DIR = "./temp-chunks";

    @Autowired
    private PermissionRepository permissionRepository;

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

        return ResponseEntity.ok(Map.of(
                "currentPath", targetPath,
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

        Resource resource = new FileSystemResource(file);
        String contentType = getCustomMimeType(targetPath, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .contentLength(file.length())
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String getCustomMimeType(String filePath, HttpServletRequest request) {
        String filename = Paths.get(filePath).getFileName().toString().toLowerCase();
        if (filename.endsWith(".mkv") || filename.contains(".mkv.")) return "video/webm";
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
        FileSystemResource resource = new FileSystemResource(file);
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
        if (targetPath.equals(SAKURA_ROOT) || targetPath.equals(STORAGE_ROOT)) {
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

        String targetDir = Paths.get(pathParam).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!hasPermission(user, targetDir, "write")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        Path newFolderPath = Paths.get(targetDir, name);
        try {
            Files.createDirectories(newFolderPath);
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
            Files.copy(file.getInputStream(), chunkFile, StandardCopyOption.REPLACE_EXISTING);

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
                Files.walk(chunkFolder)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);

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
            deleteRecursively(file);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to delete: " + e.getMessage()));
        }
    }

    @GetMapping("/transcode/{base64Path}")
    public void transcode(HttpServletRequest request,
                          HttpServletResponse response,
                          @PathVariable String base64Path) throws IOException {
        User user = (User) request.getAttribute("user");
        String targetPath = decodeBase64Path(base64Path);
        if (targetPath == null) {
            response.sendError(400, "Invalid path");
            return;
        }

        if (!hasPermission(user, targetPath, "read")) {
            response.sendError(403, "Permission denied");
            return;
        }

        File file = new File(targetPath);
        if (!file.exists() || !file.isFile()) {
            response.sendError(404, "File not found");
            return;
        }

        String ssParam = request.getParameter("ss");

        response.setContentType("video/mp4");
        response.setHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        if (ssParam != null && !ssParam.trim().isEmpty()) {
            command.add("-ss");
            command.add(ssParam.trim());
        }
        command.add("-i");
        command.add(file.getAbsolutePath());
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("superfast");
        command.add("-crf");
        command.add("23");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-f");
        command.add("mp4");
        command.add("-movflags");
        command.add("frag_keyframe+empty_moov+default_base_moof");
        command.add("pipe:1");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();

        try (InputStream is = process.getInputStream();
             OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
                os.flush();
            }
        } catch (IOException e) {
            // Connection reset/closed by peer
        } finally {
            process.destroyForcibly();
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
