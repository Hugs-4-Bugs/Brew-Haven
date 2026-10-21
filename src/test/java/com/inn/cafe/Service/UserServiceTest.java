package com.inn.cafe.Service;

import com.inn.cafe.DAO.UserDao;
import com.inn.cafe.JWT.CustomerUserDetailsService;
import com.inn.cafe.JWT.JwtFilter;
import com.inn.cafe.JWT.JwtUtil;
import com.inn.cafe.POJO.User;
import com.inn.cafe.ServiceImpl.UserServiceImpl;
import com.inn.cafe.Utils.EmailUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserServiceTest — regression coverage for the authentication fixes
 * (DEFECT_REGISTER A.1/A.2/A.3): changePassword must bcrypt-encode, forgotPassword
 * must mail a temporary password (never the stored hash) and report mail failures
 * honestly.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserServiceImpl userService;

    @Mock
    private UserDao userDao;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private CustomerUserDetailsService customerUserDetailsService;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private JwtFilter jwtFilter;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailUtils emailUtils;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1);
        user.setEmail("user@cafe.local");
        user.setPassword("$2a$10$hashedpasswordvalue");
        user.setRole("user");
        user.setStatus("true");
    }

    @Test
    void changePassword_encodesNewPassword_bcryptRegression() {
        // FIX A.1 regression: the legacy implementation stored the RAW password
        when(jwtFilter.getCurrentUser()).thenReturn("user@cafe.local");
        when(userDao.findByEmail("user@cafe.local")).thenReturn(user);
        when(passwordEncoder.matches("oldSecret", user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("newSecret123")).thenReturn("$2a$10$newhash");
        when(userDao.save(any(User.class))).thenReturn(user);

        ResponseEntity<String> response = userService.changePassword(
                Map.of("oldPassword", "oldSecret", "newPassword", "newSecret123"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Password Updated Successfully"));
        verify(passwordEncoder).encode("newSecret123");         // MUST be encoded
        assertEquals("$2a$10$newhash", user.getPassword());     // stored hash, not plaintext
    }

    @Test
    void changePassword_rejectsWrongOldPassword() {
        when(jwtFilter.getCurrentUser()).thenReturn("user@cafe.local");
        when(userDao.findByEmail("user@cafe.local")).thenReturn(user);
        when(passwordEncoder.matches("wrong", user.getPassword())).thenReturn(false);

        ResponseEntity<String> response = userService.changePassword(
                Map.of("oldPassword", "wrong", "newPassword", "newSecret123"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Incorrect Old Password"));
    }

    @Test
    void changePassword_rejectsShortNewPassword() {
        ResponseEntity<String> response = userService.changePassword(
                Map.of("oldPassword", "oldSecret", "newPassword", "abc"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void changePassword_handlesUnknownUser_withoutNpe() {
        // FIX A.2 regression: legacy code called userObj.equals(null) -> NPE -> 500
        when(jwtFilter.getCurrentUser()).thenReturn("ghost@cafe.local");
        when(userDao.findByEmail("ghost@cafe.local")).thenReturn(null);

        ResponseEntity<String> response = userService.changePassword(
                Map.of("oldPassword", "oldSecret", "newPassword", "newSecret123"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void forgotPassword_mailsTemporaryPassword_neverStoredHash() throws Exception {
        // FIX A.3 regression: the legacy implementation emailed the stored BCRYPT HASH
        when(userDao.findByEmail("user@cafe.local")).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedTemp");

        ResponseEntity<String> response = userService.forgotPassword(Map.of("email", "user@cafe.local"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Check your mail"));
        verify(emailUtils).forgotMail(eq("user@cafe.local"), anyString(), anyString());
        // the mailed password is the generated temp password, NOT the stored hash
        verify(emailUtils).forgotMail(eq("user@cafe.local"), anyString(),
                argThat(pw -> pw != null && pw.length() == 12 && !pw.startsWith("$2a$")));
        // the stored value is the ENCODED temp password
        assertEquals("$2a$10$encodedTemp", user.getPassword());
    }

    @Test
    void forgotPassword_reportsMailFailureHonestly() throws Exception {
        when(userDao.findByEmail("user@cafe.local")).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encodedTemp");
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailUtils).forgotMail(anyString(), anyString(), anyString());

        ResponseEntity<String> response = userService.forgotPassword(Map.of("email", "user@cafe.local"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("Unable to send email"));
    }

    @Test
    void signUp_registersNormalUserActiveImmediately() {
        // Registration default: a normal customer account is created ACTIVE
        // (status "true", role "user") so login works right after sign-up.
        // The admin disable/approve mechanism (updateStatus) is unaffected.
        when(userDao.findByEmailId("new@cafe.local")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$10$encoded");
        when(userDao.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<String> response = userService.signUp(Map.of(
                "name", "New Customer",
                "contactNumber", "9876543210",
                "email", "new@cafe.local",
                "password", "secret123"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Registration Completed"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDao).save(captor.capture());
        assertEquals("true", captor.getValue().getStatus());  // active immediately
        assertEquals("user", captor.getValue().getRole());    // normal user role
    }

    @Test
    void forgotPassword_genericResponseForUnknownEmail() {
        // anti-enumeration: unknown email must look identical to a successful request
        when(userDao.findByEmail("ghost@nowhere.xx")).thenReturn(null);

        ResponseEntity<String> response = userService.forgotPassword(Map.of("email", "ghost@nowhere.xx"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Check your mail"));
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
