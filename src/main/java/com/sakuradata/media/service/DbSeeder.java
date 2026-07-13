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
        java.util.Optional<User> sakuraOpt = userRepository.findByUsername("sakura");
        if (sakuraOpt.isPresent()) {
            User sakuraUser = sakuraOpt.get();
            String salt = BCrypt.gensalt(10);
            String hashed = BCrypt.hashpw("nishimiya", salt);
            sakuraUser.setPasswordHash(hashed);
            userRepository.save(sakuraUser);
            System.out.println("Owner creds updated to: sakura / nishimiya");
        } else {
            String salt = BCrypt.gensalt(10);
            String hashed = BCrypt.hashpw("nishimiya", salt);
            User defaultAdmin = new User("sakura", hashed, "admin");
            userRepository.save(defaultAdmin);
            System.out.println("H2 Database seeded with default owner admin account: sakura / nishimiya");
        }
    }
}
