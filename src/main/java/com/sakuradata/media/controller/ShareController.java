package com.sakuradata.media.controller;

import com.sakuradata.media.model.AuditLog;
import com.sakuradata.media.model.Permission;
import com.sakuradata.media.model.ShareLink;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.AuditLogRepository;
import com.sakuradata.media.repository.PermissionRepository;
import com.sakuradata.media.repository.ShareLinkRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public class ShareController {

    private static final String SAKURA_ROOT = "/home/sakura";
    private static final String STORAGE_ROOT = "/media/storage";
    private static final String HDD_ROOT = "/media/hdd";
    private static final String GDRIVE_ROOT = "/media/gdrive";

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

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

    private String resolvePath(String inputPath) {
        if (inputPath == null || inputPath.trim().isEmpty()) return null;
        String raw = inputPath.trim();
        File directFile = new File(raw);
        if (directFile.exists()) {
            return Paths.get(raw).toAbsolutePath().normalize().toString().replace("\\", "/");
        }
        if (raw.contains("%")) {
            try {
                String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
                if (new File(decoded).exists()) {
                    return Paths.get(decoded).toAbsolutePath().normalize().toString().replace("\\", "/");
                }
            } catch (Exception ignored) {}
        }
        return Paths.get(raw).toAbsolutePath().normalize().toString().replace("\\", "/");
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

    /**
     * Create or retrieve a direct download link for a file or folder.
     */
    @PostMapping("/api/shares/create")
    public ResponseEntity<?> createShare(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        String pathParam = (String) body.get("path");
        if (pathParam == null || pathParam.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File path is required"));
        }

        String targetPath = resolvePath(pathParam);
        if (targetPath == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path"));
        }

        if (!hasPermission(user, targetPath, "read")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied for this path"));
        }

        File targetFile = new File(targetPath);
        if (!targetFile.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Target file or folder not found"));
        }

        String expiresIn = (String) body.getOrDefault("expiresIn", "never");
        LocalDateTime expiresAt = null;
        if ("1h".equalsIgnoreCase(expiresIn)) {
            expiresAt = LocalDateTime.now().plusHours(1);
        } else if ("24h".equalsIgnoreCase(expiresIn) || "1d".equalsIgnoreCase(expiresIn)) {
            expiresAt = LocalDateTime.now().plusDays(1);
        } else if ("7d".equalsIgnoreCase(expiresIn)) {
            expiresAt = LocalDateTime.now().plusDays(7);
        } else if ("30d".equalsIgnoreCase(expiresIn)) {
            expiresAt = LocalDateTime.now().plusDays(30);
        }

        // Check if an active, non-expired share already exists for this user and path
        Optional<ShareLink> existingOpt = shareLinkRepository.findByFilePathAndUserIdAndIsActiveTrue(targetPath, user.getId());
        ShareLink share;
        if (existingOpt.isPresent()) {
            share = existingOpt.get();
            if (expiresAt != null || share.getExpiresAt() != null) {
                share.setExpiresAt(expiresAt);
            }
            share = shareLinkRepository.save(share);
        } else {
            // Generate clean, secure 16-character alphanumeric code
            String code = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            share = new ShareLink(
                    code,
                    targetPath,
                    targetFile.getName(),
                    targetFile.isFile() ? targetFile.length() : 0L,
                    targetFile.isDirectory(),
                    user.getId(),
                    user.getUsername(),
                    expiresAt
            );
            share = shareLinkRepository.save(share);

            try {
                auditLogRepository.save(new AuditLog(user.getUsername(), "CREATE_SHARE_LINK: " + targetFile.getName(), request.getRemoteAddr()));
            } catch (Exception ignored) {}
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("code", share.getCode());
        resp.put("downloadUrl", "/d/" + share.getCode());
        resp.put("fileName", share.getFileName());
        resp.put("fileSize", share.getFileSize());
        resp.put("isDirectory", share.getIsDirectory());
        resp.put("expiresAt", share.getExpiresAt());
        resp.put("downloadCount", share.getDownloadCount());
        resp.put("createdAt", share.getCreatedAt());

        return ResponseEntity.ok(resp);
    }

    /**
     * Get all active share links created by the current user (or all if admin).
     */
    @GetMapping("/api/shares/my")
    public ResponseEntity<?> getMyShares(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        List<ShareLink> shares;
        if ("admin".equals(user.getRole())) {
            shares = shareLinkRepository.findAllByOrderByCreatedAtDesc();
        } else {
            shares = shareLinkRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        }

        return ResponseEntity.ok(shares);
    }

    /**
     * Revoke or delete a share link.
     */
    @DeleteMapping("/api/shares/{code}")
    public ResponseEntity<?> revokeShare(HttpServletRequest request, @PathVariable String code) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        Optional<ShareLink> shareOpt = shareLinkRepository.findByCode(code);
        if (shareOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Share link not found"));
        }

        ShareLink share = shareOpt.get();
        if (!"admin".equals(user.getRole()) && !share.getUserId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Permission denied"));
        }

        share.setIsActive(false);
        shareLinkRepository.save(share);

        try {
            auditLogRepository.save(new AuditLog(user.getUsername(), "REVOKE_SHARE_LINK: " + share.getFileName(), request.getRemoteAddr()));
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("success", true, "message", "Share link revoked successfully"));
    }

    /**
     * Public direct download endpoint.
     * Accessible via both /d/{code} and /api/public/download/{code}.
     * 1-Click direct file download with HTTP Byte-Range resumable support.
     * No login or dedicated landing page required.
     */
    @GetMapping({"/d/{code}", "/api/public/download/{code}"})
    public void directDownload(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) throws IOException {

        Optional<ShareLink> shareOpt = shareLinkRepository.findByCode(code);
        if (shareOpt.isEmpty()) {
            serveErrorPage(response, HttpServletResponse.SC_NOT_FOUND, "Share Link Not Found", "The requested download link does not exist or has been removed.");
            return;
        }

        ShareLink share = shareOpt.get();
        if (Boolean.FALSE.equals(share.getIsActive())) {
            serveErrorPage(response, HttpServletResponse.SC_GONE, "Share Link Inactive", "This direct download link has been revoked by the owner.");
            return;
        }

        if (share.isExpired()) {
            serveErrorPage(response, HttpServletResponse.SC_GONE, "Share Link Expired", "This direct download link has expired.");
            return;
        }

        File file = new File(share.getFilePath());
        if (!file.exists()) {
            serveErrorPage(response, HttpServletResponse.SC_NOT_FOUND, "File Not Found", "The shared file is no longer available on the server.");
            return;
        }

        // Increment download counter
        try {
            share.setDownloadCount(share.getDownloadCount() + 1);
            shareLinkRepository.save(share);
        } catch (Exception ignored) {}

        // Handle Folder Download as ZIP
        if (file.isDirectory()) {
            downloadFolderAsZip(file, response);
            return;
        }

        // Handle Single File Direct Download with Byte-Range Resumable Streaming
        long fileLength = file.length();
        long start = 0;
        long end = fileLength - 1;
        boolean isRange = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String rangeVal = rangeHeader.substring(6).trim();
                String[] parts = rangeVal.split("-");
                if (!parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0]);
                }
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1]);
                }
                if (end >= fileLength) {
                    end = fileLength - 1;
                }
                if (start <= end && start >= 0) {
                    isRange = true;
                }
            } catch (Exception ignored) {
                start = 0;
                end = fileLength - 1;
                isRange = false;
            }
        }

        long contentLength = end - start + 1;
        String contentType = getCustomMimeType(file.getAbsolutePath(), request);
        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/octet-stream";
        }

        org.springframework.http.ContentDisposition contentDisposition = org.springframework.http.ContentDisposition.attachment()
                .filename(file.getName(), StandardCharsets.UTF_8)
                .build();

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        response.setContentType(contentType);

        if (isRange) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));

        try (InputStream is = new BufferedInputStream(new FileInputStream(file), 262144);
             OutputStream os = response.getOutputStream()) {
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long s = is.skip(start - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
            }
            byte[] buffer = new byte[131072]; // 128KB buffer
            long remaining = contentLength;

            while (remaining > 0) {
                int readLen = (int) Math.min(buffer.length, remaining);
                int bytesRead = is.read(buffer, 0, readLen);
                if (bytesRead == -1) break;
                os.write(buffer, 0, bytesRead);
                remaining -= bytesRead;
            }
            os.flush();
        } catch (Exception e) {
            // Client cancelled or disconnected cleanly
        }
    }

    private void downloadFolderAsZip(File folder, HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        org.springframework.http.ContentDisposition contentDisposition = org.springframework.http.ContentDisposition.attachment()
                .filename(folder.getName() + ".zip", StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        try (BufferedOutputStream bos = new BufferedOutputStream(response.getOutputStream(), 131072);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED);
            zipFolder(folder, folder.getName(), zos);
            zos.finish();
            zos.flush();
        } catch (Exception ignored) {}
    }

    private void zipFolder(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) return;
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

    private void serveErrorPage(HttpServletResponse response, int statusCode, String title, String message) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("text/html;charset=UTF-8");
        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>" + title + " - Sakura Media Server</title>\n" +
                "  <style>\n" +
                "    body { margin: 0; padding: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #0b0f19; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #f8fafc; text-align: center; }\n" +
                "    .card { background: rgba(255, 255, 255, 0.04); border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 20px; padding: 40px; max-width: 460px; margin: 20px; box-shadow: 0 10px 40px rgba(0,0,0,0.6); }\n" +
                "    .badge { display: inline-block; padding: 6px 16px; border-radius: 20px; font-size: 13px; font-weight: 600; background: rgba(236, 72, 153, 0.15); color: #ec4899; margin-bottom: 20px; border: 1px solid rgba(236, 72, 153, 0.3); }\n" +
                "    h1 { font-size: 24px; font-weight: 700; margin: 0 0 12px 0; color: #ffffff; }\n" +
                "    p { font-size: 15px; color: #94a3b8; line-height: 1.6; margin: 0; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"card\">\n" +
                "    <div class=\"badge\">Sakura Media Server 🌸</div>\n" +
                "    <h1>" + title + "</h1>\n" +
                "    <p>" + message + "</p>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
        response.getWriter().write(html);
    }
}
