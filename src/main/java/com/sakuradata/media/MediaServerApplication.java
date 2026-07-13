package com.sakuradata.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MediaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServerApplication.class, args);
        System.out.println("Media sharing server running on port 5000 with HTTP/2 enabled");
    }
}
