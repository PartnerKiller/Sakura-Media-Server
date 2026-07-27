package com.sakuradata.media.service;

import com.sakuradata.media.model.User;
import com.sakuradata.media.repository.UserRepository;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DbSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) throws Exception {
        String adminUser = System.getenv("DEFAULT_ADMIN_USER") != null ? System.getenv("DEFAULT_ADMIN_USER") : "sakura";
        String adminPass = System.getenv("DEFAULT_ADMIN_PASS") != null ? System.getenv("DEFAULT_ADMIN_PASS") : "sakura";

        java.util.Optional<User> adminOpt = userRepository.findByUsername(adminUser);
        if (adminOpt.isEmpty() && userRepository.count() == 0) {
            String salt = BCrypt.gensalt(10);
            String hashed = BCrypt.hashpw(adminPass, salt);
            User defaultAdmin = new User(adminUser, hashed, "admin");
            userRepository.save(defaultAdmin);
            System.out.println("Database initialized with default administrator account.");
        }
    }
}
