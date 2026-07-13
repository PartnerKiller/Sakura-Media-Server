package com.sakuradata.media.controller;

import com.sakuradata.media.model.Permission;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.PermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
    private List<Map<String, String>> getAuthorizedRoots(User user) {
        List<Map<String, String>> roots = new ArrayList<>();
        if ("admin".equals(user.getRole())) {
            roots.add(Map.of("name", "Home root", "path", SAKURA_ROOT));
            roots.add(Map.of("name", "Storage root", "path", STORAGE_ROOT));
        } else {
            List<Permission> perms = permissionRepository.findByUserId(user.getId());
            for (Permission p : perms) {
                if (p.isAllowRead()) {
                    File file = new File(p.getPath());
                    roots.add(Map.of("name", file.getName().isEmpty() ? p.getPath() : file.getName(), "path", p.getPath()));
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

    @GetMapping("/browse")
    public ResponseEntity<?> browse(HttpServletRequest request, @RequestParam String path) {
        User user = (User) request.getAttribute("user");
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        String targetPath = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
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
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String targetPath = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!hasPermission(user, targetPath, "read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        File file = new File(targetPath);
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(resource);
    }

    @GetMapping("/stream")
    public ResponseEntity<Resource> stream(HttpServletRequest request, @RequestParam String path) {
        User user = (User) request.getAttribute("user");
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String targetPath = Paths.get(path).toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!hasPermission(user, targetPath, "read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        File file = new File(targetPath);
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = request.getServletContext().getMimeType(file.getAbsolutePath());
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
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
