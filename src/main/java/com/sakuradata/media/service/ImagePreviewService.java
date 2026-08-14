package com.sakuradata.media.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
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
import java.awt.geom.AffineTransform;
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
        boolean isConvertible = filenameLower.endsWith(".jpg") || filenameLower.endsWith(".jpeg")
                || filenameLower.endsWith(".png") || filenameLower.endsWith(".webp")
                || filenameLower.endsWith(".bmp") || filenameLower.endsWith(".jfif");

        if (!isConvertible) {
            return sourceFile;
        }

        int orientation = getExifOrientation(sourceFile);

        // If file is already small (under 600KB) and has standard orientation, serve original directly
        if (sourceFile.length() <= 600 * 1024 && orientation == 1) {
            return sourceFile;
        }

        String cacheKey = generateCacheKey(sourceFile.getAbsolutePath(), sourceFile.lastModified(), maxDim);
        File cachedFile = new File(cacheDir, cacheKey + ".jpg");

        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile;
        }

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

                int visualW = (orientation == 6 || orientation == 8 || orientation == 5 || orientation == 7) ? origH : origW;
                int visualH = (orientation == 6 || orientation == 8 || orientation == 5 || orientation == 7) ? origW : origH;

                double scale = Math.min((double) maxDim / visualW, (double) maxDim / visualH);
                if (scale > 1.0) scale = 1.0;

                int targetW = (int) Math.round(visualW * scale);
                int targetH = (int) Math.round(visualH * scale);

                if (targetW <= 0) targetW = 1;
                if (targetH <= 0) targetH = 1;

                BufferedImage scaledImage = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = scaledImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                AffineTransform at = new AffineTransform();
                switch (orientation) {
                    case 6: // 90 deg CW
                        at.translate(targetW, 0);
                        at.rotate(Math.PI / 2.0);
                        at.scale(scale, scale);
                        break;
                    case 8: // 270 deg CW (90 CCW)
                        at.translate(0, targetH);
                        at.rotate(-Math.PI / 2.0);
                        at.scale(scale, scale);
                        break;
                    case 3: // 180 deg
                        at.translate(targetW, targetH);
                        at.rotate(Math.PI);
                        at.scale(scale, scale);
                        break;
                    default: // 1 (Normal)
                        at.scale(scale, scale);
                        break;
                }

                g2d.drawImage(originalImage, at, null);
                g2d.dispose();
                originalImage.flush();

                File tempFile = new File(cacheDir, cacheKey + ".tmp");
                writeCompressedJpeg(scaledImage, tempFile, 0.85f);
                scaledImage.flush();

                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(cachedFile);
                    log.info("Generated optimized preview for {} (orientation={}, original {} -> preview {})",
                            sourceFile.getName(), orientation, sourceFile.length(), cachedFile.length());
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

    private int getExifOrientation(File file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {}
        return 1;
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
