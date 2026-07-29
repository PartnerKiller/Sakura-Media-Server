package com.sakuradata.media.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    public static final String JWT_SECRET = System.getenv("JWT_SECRET") != null 
            ? System.getenv("JWT_SECRET") 
            : "sakura-media-server-secret-key-default";

    @Autowired
    private UserRepository userRepository;

    private DecodedJWT verifyToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(JWT_SECRET);
            JWTVerifier verifier = JWT.require(algorithm).build();
            return verifier.verify(token);
        } catch (Exception e) {
            try {
                Algorithm legacyAlgo = Algorithm.HMAC256("sakura-media-server-secret-key-2026");
                JWTVerifier legacyVerifier = JWT.require(legacyAlgo).build();
                return legacyVerifier.verify(token);
            } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Allow pre-flight CORS requests
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        // Skip auth check for auth endpoints, static resources, and h2-console
        if (path.startsWith("/api/auth/login") || path.startsWith("/h2-console") || !path.startsWith("/api/")) {
            return true;
        }

        String token = null;

        // Try getting token from Authorization Header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        // Try getting token from query parameters (critical for direct video streaming and downloads)
        if (token == null || token.trim().isEmpty()) {
            token = request.getParameter("token");
        }



        if (token == null || token.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Access token required\"}");
            return false;
        }

        try {
            DecodedJWT jwt = verifyToken(token);

            Long userId = jwt.getClaim("id").asLong();
            if (userId == null) {
                // Fallback to string representation if claim was mapped as integer/string
                String idStr = jwt.getClaim("id").asString();
                if (idStr != null) {
                    userId = Long.parseLong(idStr);
                } else {
                    // Try to get as integer
                    Integer idInt = jwt.getClaim("id").asInt();
                    if (idInt != null) {
                        userId = idInt.longValue();
                    }
                }
            }

            if (userId == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Invalid token payload\"}");
                return false;
            }

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"User no longer exists\"}");
                return false;
            }

            // Set user in request context attribute
            request.setAttribute("user", userOpt.get());
            return true;
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Invalid or expired token\"}");
            return false;
        }
    }
}
