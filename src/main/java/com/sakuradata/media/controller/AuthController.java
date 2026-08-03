package com.sakuradata.media.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sakuradata.media.config.JwtInterceptor;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.UserRepository;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<?> login(@RequestBody Map<String, String> loginData) {
        String username = loginData.get("username");
        String password = loginData.get("password");

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

        // Expire token in 24 hours
        long expirationTime = 24 * 60 * 60 * 1000L;
        Date expDate = new Date(System.currentTimeMillis() + expirationTime);

        Algorithm algorithm = Algorithm.HMAC256(JwtInterceptor.JWT_SECRET);
        String token = JWT.create()
                .withClaim("id", user.getId())
                .withClaim("username", user.getUsername())
                .withClaim("role", user.getRole())
                .withExpiresAt(expDate)
                .sign(algorithm);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("role", user.getRole());
        response.put("user", userInfo);

        return ResponseEntity.ok(response);
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(HttpServletRequest request, @RequestBody Map<String, String> body) {
        User user = (User) request.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        String username = body.get("username") != null ? body.get("username").trim() : null;
        String password = body.get("password") != null ? body.get("password") : null;

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

        userRepository.save(user);

        // Generate a new token with updated username/role
        long expirationTime = 24 * 60 * 60 * 1000L;
        Date expDate = new Date(System.currentTimeMillis() + expirationTime);
        Algorithm algorithm = Algorithm.HMAC256(JwtInterceptor.JWT_SECRET);
        String token = JWT.create()
                .withClaim("id", user.getId())
                .withClaim("username", user.getUsername())
                .withClaim("role", user.getRole())
                .withExpiresAt(expDate)
                .sign(algorithm);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("username", user.getUsername());
        response.put("plainPassword", user.getPlainPassword());

        return ResponseEntity.ok(response);
    }
}
