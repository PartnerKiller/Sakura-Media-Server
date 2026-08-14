package com.sakuradata.media.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImagePreviewService {

    private static final Logger log = LoggerFactory.getLogger(ImagePreviewService.class);
    private final File cacheDir;
    private final ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<>();

    public ImagePreviewService() {
        String homeDir = System.getProperty("user.home", "/tmp");
        this.cacheDir = new File(homeDir, ".cache/media-server/previews");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        System.setProperty("java.awt.headless", "true");
    }

    public File getOrCreatePreview(File sourceFile, int maxDim) {
        if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
            return null;
        }

        String filenameLower = sourceFile.getName().toLowerCase();
        // Check if file is an image format we can optimize
        boolean isConvertible = filenameLower.endsWith(".jpg") || filenameLower.endsWith(".jpeg")
                || filenameLower.endsWith(".png") || filenameLower.endsWith(".webp")
                || filenameLower.endsWith(".bmp") || filenameLower.endsWith(".jfif");

        if (!isConvertible) {
            return sourceFile;
        }

        // If file is already small (under 600KB), serve original directly
        if (sourceFile.length() <= 600 * 1024) {
            return sourceFile;
        }

        String cacheKey = generateCacheKey(sourceFile.getAbsolutePath(), sourceFile.lastModified(), maxDim);
        File cachedFile = new File(cacheDir, cacheKey + ".jpg");

        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile;
        }

        // Synchronize per cacheKey to avoid duplicate resizing work
        Object lock = lockMap.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            try {
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    return cachedFile;
                }

                BufferedImage originalImage = ImageIO.read(sourceFile);
                if (originalImage == null) {
                    return sourceFile;
                }

                int origW = originalImage.getWidth();
                int origH = originalImage.getHeight();

                // If image is already smaller than maxDim in both dimensions, return source
                if (origW <= maxDim && origH <= maxDim) {
                    return sourceFile;
                }

                double scale = Math.min((double) maxDim / origW, (double) maxDim / origH);
                int targetW = (int) Math.round(origW * scale);
                int targetH = (int) Math.round(origH * scale);

                if (targetW <= 0) targetW = 1;
                if (targetH <= 0) targetH = 1;

                BufferedImage scaledImage = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = scaledImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2d.drawImage(originalImage, 0, 0, targetW, targetH, null);
                g2d.dispose();
                originalImage.flush();

                File tempFile = new File(cacheDir, cacheKey + ".tmp");
                writeCompressedJpeg(scaledImage, tempFile, 0.82f);
                scaledImage.flush();

                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(cachedFile);
                    log.info("Generated optimized preview for {} (original {} -> preview {})",
                            sourceFile.getName(), sourceFile.length(), cachedFile.length());
                    return cachedFile;
                }
            } catch (Exception e) {
                log.warn("Failed to generate preview for {}: {}", sourceFile.getName(), e.getMessage());
            } finally {
                lockMap.remove(cacheKey);
            }
        }

        return sourceFile;
    }

    private void writeCompressedJpeg(BufferedImage image, File destination, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", destination);
            return;
        }

        ImageWriter writer = writers.next();
        try (FileOutputStream fos = new FileOutputStream(destination);
             ImageOutputStream ios = ImageIO.createImageOutputStream(fos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private String generateCacheKey(String path, long lastModified, int maxDim) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String raw = path + "_" + lastModified + "_" + maxDim;
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf((path + "_" + lastModified + "_" + maxDim).hashCode());
        }
    }
}
