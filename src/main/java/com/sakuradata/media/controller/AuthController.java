package com.sakuradata.media.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.sakuradata.media.config.JwtInterceptor;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.UserRepository;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> loginData) {
        String username = loginData.get("username") != null ? String.valueOf(loginData.get("username")).trim() : null;
        String password = loginData.get("password") != null ? String.valueOf(loginData.get("password")) : null;

        if (username == null || password == null) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Username and password required");
            return ResponseEntity.badRequest().body(err);
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty() || !BCrypt.checkpw(password, userOpt.get().getPasswordHash())) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Invalid username or password");
            return ResponseEntity.status(401).body(err);
        }

        User user = userOpt.get();

        boolean rememberMe = false;
        if (loginData.containsKey("rememberMe")) {
            Object rm = loginData.get("rememberMe");
            if (rm instanceof Boolean) {
                rememberMe = (Boolean) rm;
            } else if (rm != null) {
                rememberMe = Boolean.parseBoolean(rm.toString());
            }
        }

        // Expire token in 365 days if Remember Me is checked, otherwise 24 hours
        long expirationTime = rememberMe ? (365L * 24 * 60 * 60 * 1000L) : (24L * 60 * 60 * 1000L);
        Date expDate = new Date(System.currentTimeMillis() + expirationTime);

        Algorithm algorithm = Algorithm.HMAC256(JwtInterceptor.JWT_SECRET);
        String token = JWT.create()
                .withClaim("id", user.getId())
                .withClaim("username", user.getUsername())
                .withClaim("role", user.getRole())
                .withClaim("remember", rememberMe)
                .withExpiresAt(expDate)
                .sign(algorithm);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("rememberMe", rememberMe);
        response.put("expiresAt", expDate.getTime());

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("role", user.getRole());
        userInfo.put("theme", user.getTheme());
        userInfo.put("uiStyle", user.getUiStyle());
        response.put("user", userInfo);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("userId", user.getId());

        DecodedJWT currentJwt = (DecodedJWT) request.getAttribute("jwt");
        if (currentJwt != null) {
            boolean rememberMe = false;
            if (!currentJwt.getClaim("remember").isMissing()) {
                rememberMe = Boolean.TRUE.equals(currentJwt.getClaim("remember").asBoolean());
            }

            Date expiresAt = currentJwt.getExpiresAt();
            long now = System.currentTimeMillis();
            // Renew if rememberMe is true and less than 180 days remain, or if regular session and less than 12 hours remain
            long renewalThreshold = rememberMe ? (180L * 24 * 60 * 60 * 1000L) : (12L * 60 * 60 * 1000L);
            if (expiresAt == null || (expiresAt.getTime() - now) < renewalThreshold) {
                long expirationTime = rememberMe ? (365L * 24 * 60 * 60 * 1000L) : (24L * 60 * 60 * 1000L);
                Date newExp = new Date(now + expirationTime);
                Algorithm algorithm = Algorithm.HMAC256(JwtInterceptor.JWT_SECRET);
                String newToken = JWT.create()
                        .withClaim("id", user.getId())
                        .withClaim("username", user.getUsername())
                        .withClaim("role", user.getRole())
                        .withClaim("remember", rememberMe)
                        .withExpiresAt(newExp)
                        .sign(algorithm);
                resp.put("token", newToken);
                resp.put("expiresAt", newExp.getTime());
            }
        }

        return ResponseEntity.ok(resp);
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(HttpServletRequest request, @RequestBody Map<String, String> body) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        String username = body.get("username") != null ? body.get("username").trim() : null;
        String password = body.get("password") != null ? body.get("password") : null;
        String theme = body.get("theme") != null ? body.get("theme").trim() : null;
        String uiStyle = body.get("uiStyle") != null ? body.get("uiStyle").trim() : (body.get("ui_style") != null ? body.get("ui_style").trim() : null);

        if (username != null && !username.isEmpty() && !username.equals(user.getUsername())) {
            if (userRepository.findByUsername(username).isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
            }
            user.setUsername(username);
        }

        if (password != null && !password.trim().isEmpty()) {
            String salt = BCrypt.gensalt(10);
            String hashed = BCrypt.hashpw(password, salt);
            user.setPasswordHash(hashed);
            user.setPlainPassword(password);
        }

        if (theme != null && !theme.isEmpty()) {
            user.setTheme(theme);
        }

        if (uiStyle != null && !uiStyle.isEmpty()) {
            user.setUiStyle(uiStyle);
        }

        userRepository.save(user);

        // Check if the current token had remember=true
        boolean rememberMe = false;
        DecodedJWT currentJwt = (DecodedJWT) request.getAttribute("jwt");
        if (currentJwt != null && !currentJwt.getClaim("remember").isMissing()) {
            rememberMe = Boolean.TRUE.equals(currentJwt.getClaim("remember").asBoolean());
        }
        if (body.containsKey("rememberMe")) {
            rememberMe = Boolean.parseBoolean(body.get("rememberMe"));
        }

        // Generate a new token with updated username/role and preserved expiration
        long expirationTime = rememberMe ? (365L * 24 * 60 * 60 * 1000L) : (24L * 60 * 60 * 1000L);
        Date expDate = new Date(System.currentTimeMillis() + expirationTime);
        Algorithm algorithm = Algorithm.HMAC256(JwtInterceptor.JWT_SECRET);
        String token = JWT.create()
                .withClaim("id", user.getId())
                .withClaim("username", user.getUsername())
                .withClaim("role", user.getRole())
                .withClaim("remember", rememberMe)
                .withExpiresAt(expDate)
                .sign(algorithm);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("plainPassword", user.getPlainPassword());
        response.put("theme", user.getTheme());
        response.put("uiStyle", user.getUiStyle());
        response.put("rememberMe", rememberMe);
        response.put("expiresAt", expDate.getTime());

        return ResponseEntity.ok(response);
    }

    private static final String AVATARS_DIR = "./data/avatars";

    @PostMapping("/profile/avatar")
    public ResponseEntity<?> uploadAvatar(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        if (file.getSize() > 5 * 1024 * 1024) { // 5MB limit
            return ResponseEntity.badRequest().body(Map.of("error", "File size exceeds 5MB limit"));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
        }

        try {
            File dir = new File(AVATARS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // Extract extension
            String origName = file.getOriginalFilename();
            String ext = ".png";
            if (origName != null && origName.lastIndexOf('.') > 0) {
                ext = origName.substring(origName.lastIndexOf('.'));
            }

            // Create unique filename
            String fileName = "avatar_" + user.getId() + "_" + UUID.randomUUID().toString() + ext;
            Path targetPath = Paths.get(AVATARS_DIR).resolve(fileName);

            // Clean up old avatar if exists
            if (user.getProfilePicture() != null) {
                try {
                    Files.deleteIfExists(Paths.get(AVATARS_DIR).resolve(user.getProfilePicture()));
                } catch (Exception ignored) {}
            }

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            user.setProfilePicture(fileName);
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("profilePicture", "/api/users/avatar/" + user.getUsername()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to save file"));
        }
    }

    @DeleteMapping("/profile/avatar")
    public ResponseEntity<?> deleteAvatar(HttpServletRequest request) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        if (user.getProfilePicture() != null) {
            try {
                Files.deleteIfExists(Paths.get(AVATARS_DIR).resolve(user.getProfilePicture()));
            } catch (Exception ignored) {}
            user.setProfilePicture(null);
            userRepository.save(user);
        }

        return ResponseEntity.ok(Map.of("success", true));
    }
}
