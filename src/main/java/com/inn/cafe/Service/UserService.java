package com.inn.cafe.Service;

import com.inn.cafe.Wrapper.UserWrapper;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

public interface UserService {

    ResponseEntity<String> signUp(Map<String, String> requestMap);

    ResponseEntity<String> login(Map<String, String> requestMap);

    ResponseEntity<List<UserWrapper>> getAllUser();  // to get all the user by role (admin or user)

    ResponseEntity<String> update(Map<String, String> requestMap); // to update the information of users

    ResponseEntity<String> checkToken();   // to check the token

    ResponseEntity<String> changePassword(Map<String, String> requestMap);  // to change the password

    // ADDITIVE: the signed-in account's own profile (name/email/contact/role).
    ResponseEntity<String> getMyProfile();


    // method for FORGET PASSWORD using @RequestBody 👇👇👇👇👇
    ResponseEntity<String> forgotPassword(Map<String, String> requestMap);


//    // OR method for FORGET PASSWORD using @RequestParam 👇👇👇👇👇
//    ResponseEntity<String> forgotPassword(String email);

}
