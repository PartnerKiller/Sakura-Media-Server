package com.sakuradata.media.service;

import com.sakuradata.media.controller.SseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ConcurrentHashMap<String, ImportTask> taskMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static class ImportTask {
        private String taskId;
        private String url;
        private String targetPath;
        private String fileName;
        private String status; // PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
        private long downloadedBytes;
        private long totalBytes;
        private int percent;
        private String speed;
        private String error;
        private long startTime;
        private long endTime;
        private volatile boolean cancelled = false;

        private transient Future<?> future;
        private transient Process process;

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getTargetPath() { return targetPath; }
        public void setTargetPath(String targetPath) { this.targetPath = targetPath; }

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public long getDownloadedBytes() { return downloadedBytes; }
        public void setDownloadedBytes(long downloadedBytes) { this.downloadedBytes = downloadedBytes; }

        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

        public int getPercent() { return percent; }
        public void setPercent(int percent) { this.percent = percent; }

        public String getSpeed() { return speed; }
        public void setSpeed(String speed) { this.speed = speed; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }

        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }

        public boolean isCancelled() { return cancelled; }
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public Future<?> getFuture() { return future; }
        public void setFuture(Future<?> future) { this.future = future; }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public Process getProcess() { return process; }
        public void setProcess(Process process) { this.process = process; }
    }

    public ImportTask startImport(String url, String targetDirectory, String customFileName) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }
        if (targetDirectory == null || targetDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("Target directory cannot be empty");
        }

        File targetDir = new File(targetDirectory);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            throw new IllegalArgumentException("Target directory does not exist: " + targetDirectory);
        }

        String taskId = "import_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);
        ImportTask task = new ImportTask();
        task.setTaskId(taskId);
        task.setUrl(url.trim());
        task.setTargetPath(targetDirectory);
        task.setFileName(customFileName != null && !customFileName.trim().isEmpty() ? customFileName.trim() : "Detecting...");
        task.setStatus("PENDING");
        task.setStartTime(System.currentTimeMillis());
        task.setDownloadedBytes(0);
        task.setTotalBytes(-1);
        task.setPercent(0);
        task.setSpeed("0 KB/s");

        taskMap.put(taskId, task);

        Future<?> future = executor.submit(() -> {
            try {
                executeDownload(task, customFileName);
            } catch (Throwable t) {
                log.error("Import failed for task {}", taskId, t);
                task.setStatus("FAILED");
                task.setError(t.getMessage() != null ? t.getMessage() : "Unknown download error");
                task.setEndTime(System.currentTimeMillis());
                broadcastProgress(task);
            }
        });
        task.setFuture(future);

        return task;
    }

    public ImportTask getTask(String taskId) {
        return taskMap.get(taskId);
    }

    public List<ImportTask> getAllTasks() {
        return new ArrayList<>(taskMap.values());
    }

    public boolean cancelTask(String taskId) {
        ImportTask task = taskMap.get(taskId);
        if (task == null) return false;
        task.setCancelled(true);
        task.setStatus("CANCELLED");
        task.setEndTime(System.currentTimeMillis());

        if (task.getFuture() != null) {
            task.getFuture().cancel(true);
        }
        if (task.getProcess() != null && task.getProcess().isAlive()) {
            task.getProcess().destroyForcibly();
        }

        // Clean up partial file
        if (task.getFileName() != null && task.getTargetPath() != null) {
            try {
                File partFile = new File(task.getTargetPath(), task.getFileName() + ".part");
                if (partFile.exists()) partFile.delete();
            } catch (Exception ignored) {}
        }

        broadcastProgress(task);
        return true;
    }

    private void executeDownload(ImportTask task, String customFileName) throws Exception {
        String url = task.getUrl();
        log.info("Executing import task {} for URL: {}", task.getTaskId(), url);

        task.setStatus("DOWNLOADING");
        broadcastProgress(task);

        // 1. Check if it's a Google Drive folder link
        if (isGoogleDriveFolder(url)) {
            executeGdownFolderDownload(task);
            return;
        }

        // 2. Check if it's a media site link supported by yt-dlp (YouTube, TikTok, Vimeo, Twitter, etc.)
        if (isMediaSite(url) && isYtDlpAvailable()) {
            executeYtDlpDownload(task, customFileName);
            return;
        }

        // 3. Check if it's a Google Drive single file link
        if (isGoogleDriveLink(url)) {
            executeGoogleDriveDownload(task, customFileName);
            return;
        }

        // 4. Standard Direct HTTP/HTTPS download
        executeHttpDownload(task, url, customFileName);
    }

    private boolean isGoogleDriveLink(String url) {
        return url.contains("drive.google.com") || url.contains("docs.google.com");
    }

    private boolean isGoogleDriveFolder(String url) {
        return isGoogleDriveLink(url) && (url.contains("/folders/") || url.contains("/drive/folders/"));
    }

    private boolean isMediaSite(String url) {
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be")
                || lower.contains("tiktok.com") || lower.contains("vimeo.com")
                || lower.contains("twitter.com") || lower.contains("x.com")
                || lower.contains("instagram.com") || lower.contains("facebook.com")
                || lower.contains("dailymotion.com") || lower.contains("reddit.com");
    }

    private boolean isYtDlpAvailable() {
        return new File("/usr/local/bin/yt-dlp").exists() || new File("/usr/bin/yt-dlp").exists();
    }

    private String extractGoogleDriveFileId(String url) {
        Pattern p1 = Pattern.compile("/file/d/([a-zA-Z0-9_-]{20,})");
        Matcher m1 = p1.matcher(url);
        if (m1.find()) return m1.group(1);

        Pattern p2 = Pattern.compile("[?&]id=([a-zA-Z0-9_-]{20,})");
        Matcher m2 = p2.matcher(url);
        if (m2.find()) return m2.group(1);

        Pattern p3 = Pattern.compile("/d/([a-zA-Z0-9_-]{20,})");
        Matcher m3 = p3.matcher(url);
        if (m3.find()) return m3.group(1);

        Pattern p4 = Pattern.compile("/uc\\?id=([a-zA-Z0-9_-]{20,})");
        Matcher m4 = p4.matcher(url);
        if (m4.find()) return m4.group(1);

        Pattern p5 = Pattern.compile("(?:/folders/|/drive/folders/)([a-zA-Z0-9_-]{20,})");
        Matcher m5 = p5.matcher(url);
        if (m5.find()) return m5.group(1);

        Pattern p6 = Pattern.compile("^([a-zA-Z0-9_-]{25,})$");
        Matcher m6 = p6.matcher(url.trim());
        if (m6.find()) return m6.group(1);

        return null;
    }

    private void executeGdownFolderDownload(ImportTask task) throws Exception {
        String gdownBin = new File("/usr/local/bin/gdown").exists() ? "/usr/local/bin/gdown" : "/home/sakura/.local/bin/gdown";
        if (!new File(gdownBin).exists()) {
            gdownBin = "gdown";
        }

        task.setFileName("Google Drive Folder");
        task.setStatus("DOWNLOADING");
        broadcastProgress(task);

        List<String> command = new ArrayList<>(Arrays.asList(
                gdownBin,
                "--folder",
                "--continue",
                task.getUrl()
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(task.getTargetPath()));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        task.setProcess(process);

        StringBuilder outputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            Pattern progressPattern = Pattern.compile("([0-9.]+)%\\s*\\|.*?\\|\\s*([0-9.]+[A-Za-z/]+)");
            Pattern filePattern = Pattern.compile("(?:Processing file|Downloading|Saved):?\\s*(.+)");

            long lastBroadcastTime = 0;

            while ((line = reader.readLine()) != null) {
                if (task.isCancelled()) {
                    process.destroyForcibly();
                    return;
                }

                log.info("[gdown] {}", line);
                outputLog.append(line).append("\n");

                Matcher fileMatcher = filePattern.matcher(line);
                if (fileMatcher.find()) {
                    task.setFileName(new File(fileMatcher.group(1).trim()).getName());
                }

                Matcher progMatcher = progressPattern.matcher(line);
                if (progMatcher.find()) {
                    try {
                        float pct = Float.parseFloat(progMatcher.group(1));
                        task.setPercent((int) pct);
                        task.setSpeed(progMatcher.group(2));
                    } catch (Exception ignored) {}
                }

                long now = System.currentTimeMillis();
                if (now - lastBroadcastTime > 400) {
                    broadcastProgress(task);
                    lastBroadcastTime = now;
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0 && !task.isCancelled()) {
            String outStr = outputLog.toString();
            if (outStr.contains("404") || outStr.contains("permission") || outStr.contains("Failed to retrieve folder contents")) {
                throw new IOException("Google Drive folder requires 'Anyone with the link' view permission. Please ensure the link is shared publicly.");
            }
            throw new IOException("Folder download failed: " + (outStr.length() > 200 ? outStr.substring(outStr.length() - 200) : outStr));
        }

        task.setStatus("COMPLETED");
        task.setPercent(100);
        task.setEndTime(System.currentTimeMillis());
        task.setSpeed("Done");
        broadcastProgress(task);
        broadcastFileCreated(task);
    }

    private void executeGoogleDriveDownload(ImportTask task, String customFileName) throws Exception {
        String fileId = extractGoogleDriveFileId(task.getUrl());
        if (fileId == null) {
            throw new IllegalArgumentException("Could not extract Google Drive File ID from URL: " + task.getUrl());
        }

        log.info("Extracted Google Drive file ID: {}", fileId);

        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        String directUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(directUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            throw new IOException("Google Drive returned HTTP " + statusCode + " error");
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String disposition = response.headers().firstValue("Content-Disposition").orElse("");

        // If Google Drive returns an HTML virus scan confirmation page (for large files)
        if (contentType.toLowerCase().contains("text/html") && !disposition.contains("filename")) {
            InputStream is = response.body();
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            String confirmUrl = null;
            // Look for form or download link with confirm code
            Pattern formPattern = Pattern.compile("action=\"([^\"]+)\"");
            Matcher formMatcher = formPattern.matcher(html);
            if (formMatcher.find()) {
                String action = formMatcher.group(1);
                if (action.startsWith("/")) action = "https://drive.google.com" + action;
                confirmUrl = action.replace("&amp;", "&");
            }

            if (confirmUrl == null) {
                Pattern confirmPattern = Pattern.compile("href=\"(/uc\\?export=download[^\"]+confirm=[^\"]+)\"");
                Matcher confirmMatcher = confirmPattern.matcher(html);
                if (confirmMatcher.find()) {
                    confirmUrl = "https://drive.google.com" + confirmMatcher.group(1).replace("&amp;", "&");
                }
            }

            if (confirmUrl == null) {
                // Fallback confirm token
                confirmUrl = "https://drive.google.com/uc?export=download&id=" + fileId + "&confirm=t";
            }

            log.info("Following Google Drive confirmation URL: {}", confirmUrl);

            HttpRequest confirmRequest = HttpRequest.newBuilder()
                    .uri(URI.create(confirmUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofMinutes(60))
                    .GET()
                    .build();

            response = client.send(confirmRequest, HttpResponse.BodyHandlers.ofInputStream());
        }

        // Extract filename
        String finalFileName = resolveFileName(customFileName, response.headers().firstValue("Content-Disposition").orElse(null), task.getUrl(), "gdrive_" + fileId);
        task.setFileName(finalFileName);

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        task.setTotalBytes(contentLength);

        streamToFile(response.body(), task);
    }

    private void executeHttpDownload(ImportTask task, String urlStr, String customFileName) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofMinutes(60))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            throw new IOException("Server returned HTTP " + statusCode + " error");
        }

        String disposition = response.headers().firstValue("Content-Disposition").orElse(null);
        String finalFileName = resolveFileName(customFileName, disposition, urlStr, "downloaded_file_" + System.currentTimeMillis());
        task.setFileName(finalFileName);

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        task.setTotalBytes(contentLength);

        streamToFile(response.body(), task);
    }

    private void executeYtDlpDownload(ImportTask task, String customFileName) throws Exception {
        String ytdlpBin = new File("/usr/local/bin/yt-dlp").exists() ? "/usr/local/bin/yt-dlp" : "/usr/bin/yt-dlp";

        String outputTemplate = "%(title)s.%(ext)s";
        if (customFileName != null && !customFileName.trim().isEmpty()) {
            outputTemplate = customFileName.trim();
        }

        List<String> command = new ArrayList<>(Arrays.asList(
                ytdlpBin,
                "--newline",
                "--no-colors",
                "--no-playlist",
                "-o", outputTemplate,
                "-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
                task.getUrl()
        ));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(task.getTargetPath()));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        task.setProcess(process);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            Pattern progressPattern = Pattern.compile("\\[download\\]\\s+([0-9.]+)%\\s+of\\s+~?([0-9.]+[A-Za-z]+)\\s+at\\s+([0-9.]+[A-Za-z/]+)");
            Pattern destPattern = Pattern.compile("\\[download\\] Destination:\\s+(.+)");
            Pattern mergingPattern = Pattern.compile("\\[Merger\\] Merging formats into \"([^\"]+)\"");

            long lastBroadcastTime = 0;

            while ((line = reader.readLine()) != null) {
                if (task.isCancelled()) {
                    process.destroyForcibly();
                    return;
                }

                Matcher destMatcher = destPattern.matcher(line);
                if (destMatcher.find()) {
                    task.setFileName(new File(destMatcher.group(1)).getName());
                }

                Matcher mergeMatcher = mergingPattern.matcher(line);
                if (mergeMatcher.find()) {
                    task.setFileName(new File(mergeMatcher.group(1)).getName());
                }

                Matcher progMatcher = progressPattern.matcher(line);
                if (progMatcher.find()) {
                    try {
                        float pct = Float.parseFloat(progMatcher.group(1));
                        task.setPercent((int) pct);
                        task.setSpeed(progMatcher.group(3));
                    } catch (Exception ignored) {}
                }

                long now = System.currentTimeMillis();
                if (now - lastBroadcastTime > 500) {
                    broadcastProgress(task);
                    lastBroadcastTime = now;
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0 && !task.isCancelled()) {
            throw new IOException("yt-dlp exited with error code " + exitCode);
        }

        task.setStatus("COMPLETED");
        task.setPercent(100);
        task.setEndTime(System.currentTimeMillis());
        broadcastProgress(task);
        broadcastFileCreated(task);
    }

    private void streamToFile(InputStream in, ImportTask task) throws Exception {
        File targetDir = new File(task.getTargetPath());
        File partFile = new File(targetDir, task.getFileName() + ".part");
        File finalFile = new File(targetDir, task.getFileName());

        // Handle collision: if file already exists, generate a unique name
        if (finalFile.exists()) {
            String name = task.getFileName();
            String base = name;
            String ext = "";
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx > 0) {
                base = name.substring(0, dotIdx);
                ext = name.substring(dotIdx);
            }
            int counter = 1;
            while (finalFile.exists()) {
                finalFile = new File(targetDir, base + " (" + counter + ")" + ext);
                partFile = new File(targetDir, base + " (" + counter + ")" + ext + ".part");
                counter++;
            }
            task.setFileName(finalFile.getName());
        }

        long downloaded = 0;
        long total = task.getTotalBytes();
        long startTime = System.currentTimeMillis();
        long lastBroadcast = 0;

        byte[] buffer = new byte[131072]; // 128KB buffer for maximum throughput

        try (InputStream is = new BufferedInputStream(in, 131072);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(partFile), 131072)) {

            int read;
            while ((read = is.read(buffer)) != -1) {
                if (task.isCancelled()) {
                    os.close();
                    if (partFile.exists()) partFile.delete();
                    return;
                }

                os.write(buffer, 0, read);
                downloaded += read;
                task.setDownloadedBytes(downloaded);

                if (total > 0) {
                    task.setPercent((int) ((downloaded * 100) / total));
                }

                long now = System.currentTimeMillis();
                if (now - lastBroadcast > 400) {
                    double elapsedSec = Math.max(0.1, (now - startTime) / 1000.0);
                    double bytesPerSec = downloaded / elapsedSec;
                    task.setSpeed(formatSpeed(bytesPerSec));
                    broadcastProgress(task);
                    lastBroadcast = now;
                }
            }
            os.flush();
        }

        if (task.isCancelled()) {
            if (partFile.exists()) partFile.delete();
            return;
        }

        // Atomic rename to final file
        if (partFile.exists()) {
            partFile.renameTo(finalFile);
        }

        task.setStatus("COMPLETED");
        task.setPercent(100);
        task.setDownloadedBytes(downloaded);
        task.setTotalBytes(downloaded);
        task.setEndTime(System.currentTimeMillis());
        task.setSpeed("Done");

        log.info("Import task {} completed successfully: {}", task.getTaskId(), finalFile.getAbsolutePath());

        broadcastProgress(task);
        broadcastFileCreated(task);
    }

    private String resolveFileName(String customFileName, String contentDisposition, String urlStr, String fallback) {
        if (customFileName != null && !customFileName.trim().isEmpty()) {
            return sanitizeFileName(customFileName.trim());
        }

        if (contentDisposition != null) {
            // Check filename*=UTF-8''...
            Pattern pStar = Pattern.compile("filename\\*=UTF-8''([^;]+)");
            Matcher mStar = pStar.matcher(contentDisposition);
            if (mStar.find()) {
                try {
                    return sanitizeFileName(URLDecoder.decode(mStar.group(1), StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }

            // Check filename="..." or filename=...
            Pattern pNorm = Pattern.compile("filename=\"?([^\";]+)\"?");
            Matcher mNorm = pNorm.matcher(contentDisposition);
            if (mNorm.find()) {
                String fn = mNorm.group(1).trim();
                if (!fn.isEmpty()) {
                    return sanitizeFileName(fn);
                }
            }
        }

        // Try extracting from URL path
        try {
            URI uri = URI.create(urlStr);
            String path = uri.getPath();
            if (path != null && path.contains("/")) {
                String lastSegment = path.substring(path.lastIndexOf('/') + 1).trim();
                if (lastSegment.contains(".")) {
                    return sanitizeFileName(URLDecoder.decode(lastSegment, StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {}

        return sanitizeFileName(fallback);
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "downloaded_file";
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isEmpty() ? "downloaded_file" : clean;
    }

    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.2f MB/s", bytesPerSec / (1024 * 1024));
        } else if (bytesPerSec >= 1024) {
            return String.format("%.1f KB/s", bytesPerSec / 1024);
        } else {
            return String.format("%.0f B/s", bytesPerSec);
        }
    }

    private void broadcastProgress(ImportTask task) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("taskId", task.getTaskId());
            data.put("status", task.getStatus());
            data.put("fileName", task.getFileName());
            data.put("targetPath", task.getTargetPath());
            data.put("percent", task.getPercent());
            data.put("downloadedBytes", task.getDownloadedBytes());
            data.put("totalBytes", task.getTotalBytes());
            data.put("speed", task.getSpeed());
            data.put("error", task.getError());
            SseController.broadcast("import_progress", data);
        } catch (Exception ignored) {}
    }

    private void broadcastFileCreated(ImportTask task) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("path", task.getTargetPath() + "/" + task.getFileName());
            data.put("targetPath", task.getTargetPath());
            data.put("fileName", task.getFileName());
            SseController.broadcast("file_created", data);
        } catch (Exception ignored) {}
    }
}
