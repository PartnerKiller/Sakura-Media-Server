package com.sakuradata.media.scratch;

import org.mindrot.jbcrypt.BCrypt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class TempAdmin {
    public static void main(String[] args) {
        try {
            Class.forName("org.h2.Driver");
            Connection conn = DriverManager.getConnection("jdbc:h2:file:/home/sakura/media-server/mediadb", "sakura", "sakura");
            String salt = BCrypt.gensalt(10);
            String hashed = BCrypt.hashpw("testpass", salt);

            if (args.length > 0 && "delete".equals(args[0])) {
                PreparedStatement psDel = conn.prepareStatement("DELETE FROM users WHERE username = 'test_admin'");
                psDel.executeUpdate();
                System.out.println("Temporary admin 'test_admin' deleted successfully!");
            } else {
                // Delete if exists
                PreparedStatement psDel = conn.prepareStatement("DELETE FROM users WHERE username = 'test_admin'");
                psDel.executeUpdate();

                // Insert
                PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password_hash, role, download_bandwidth_limit, upload_bandwidth_limit) VALUES (?, ?, ?, ?, ?)"
                );
                ps.setString(1, "test_admin");
                ps.setString(2, hashed);
                ps.setString(3, "admin");
                ps.setNull(4, java.sql.Types.DOUBLE);
                ps.setNull(5, java.sql.Types.DOUBLE);
                ps.executeUpdate();

                System.out.println("Temporary admin 'test_admin' created successfully with password 'testpass'!");
            }
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
