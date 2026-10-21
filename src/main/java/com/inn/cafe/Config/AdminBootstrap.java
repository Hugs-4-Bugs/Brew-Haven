package com.inn.cafe.Config;

import com.inn.cafe.DAO.UserDao;
import com.inn.cafe.POJO.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * First-admin bootstrap (FIX DEFECT_REGISTER A.4).
 *
 * Problem: signUp always creates role="user"/status="false" accounts, so on a fresh
 * database NO administrator can ever log in and no account can ever be approved — the
 * application is unusable without manual SQL.
 *
 * Solution (config-gated, additive, no change to signUp semantics):
 *  - If cafe.bootstrap.admin-email / cafe.bootstrap.admin-password are set AND no admin
 *    exists, an approved admin (bcrypt password) is created at startup.
 *  - If they are not set and no admin exists, a prominent WARNING is logged telling the
 *    operator exactly how to bootstrap.
 *  - If an admin already exists, this runner does nothing (idempotent, non-destructive).
 *
 * SECURITY: bootstrap values must come from environment variables, never from source
 * control. The password is stored bcrypt-encoded exactly like every other account.
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    @Autowired
    private UserDao userDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${cafe.bootstrap.admin-email:}")
    private String bootstrapAdminEmail;

    @Value("${cafe.bootstrap.admin-password:}")
    private String bootstrapAdminPassword;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> admins = userDao.getAllAdmin();
            boolean adminExists = admins != null && !admins.isEmpty();
            boolean configured = bootstrapAdminEmail != null && !bootstrapAdminEmail.isBlank()
                    && bootstrapAdminPassword != null && !bootstrapAdminPassword.isBlank();

            if (adminExists) {
                return; // nothing to do — idempotent
            }
            if (!configured) {
                log.warn("======================================================================");
                log.warn("NO ADMIN ACCOUNT EXISTS in the database. Nobody can approve users.");
                log.warn("Set CAFE_BOOTSTRAP_ADMIN_EMAIL / CAFE_BOOTSTRAP_ADMIN_PASSWORD (or the");
                log.warn("cafe.bootstrap.admin-* properties) and restart to create the first admin.");
                log.warn("Alternatively insert one manually with a bcrypt password hash.");
                log.warn("======================================================================");
                return;
            }
            String email = bootstrapAdminEmail.trim().toLowerCase();
            User existing = userDao.findByEmailId(email);
            if (existing != null) {
                // promote an existing account to an approved admin rather than duplicating
                existing.setRole("admin");
                existing.setStatus("true");
                userDao.save(existing);
                log.info("Admin bootstrap: existing account '{}' promoted to approved admin.", email);
                return;
            }
            User admin = new User();
            admin.setName("Administrator");
            admin.setContactNumber("0000000000");
            admin.setEmail(email);
            admin.setPassword(passwordEncoder.encode(bootstrapAdminPassword));
            admin.setStatus("true");
            admin.setRole("admin");
            userDao.save(admin);
            log.info("Admin bootstrap: first admin '{}' created (approved).", email);
        } catch (Exception e) {
            log.error("Admin bootstrap failed: {}", e.getMessage());
        }
    }
}
