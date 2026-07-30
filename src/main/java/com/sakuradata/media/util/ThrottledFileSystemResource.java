package com.sakuradata.media.util;

import org.springframework.core.io.FileSystemResource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class ThrottledFileSystemResource extends FileSystemResource {
    private final long maxBytesPerSecond;

    public ThrottledFileSystemResource(File file, long maxBytesPerSecond) {
        super(file);
        this.maxBytesPerSecond = maxBytesPerSecond;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ThrottledInputStream(super.getInputStream(), maxBytesPerSecond);
    }
}
