package com.sakuradata.media.controller;

import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DebugController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/public-users-debug")
    public ResponseEntity<?> getUsersDebug() {
        List<User> users = userRepository.findAll();
        return ResponseEntity.ok(users);
    }
}
