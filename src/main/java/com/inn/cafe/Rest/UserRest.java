package com.inn.cafe.Rest;

import com.inn.cafe.Wrapper.UserWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequestMapping(path = "/user")    // we need this class level because in a 1 class we can have multiple api
public interface UserRest {

// for creating an account
    @PostMapping(path = "/signup")
    public ResponseEntity<String> signUp(@RequestBody(required = true) Map<String, String> requestMap);

// for login
    @PostMapping(path = "/login")
    public ResponseEntity<String> login(@RequestBody(required = true) Map<String, String> requestMap);

// to get all the user by admin role
    @GetMapping(path = "/get")
    public ResponseEntity<List<UserWrapper>> getAllUser();

    // api to update the account (enabled or disabled)
    @PostMapping(path = "/update")
    public ResponseEntity<String> update(@RequestBody(required = true) Map<String, String> requestMap);

    // api to check the token for validation. if you try to hit the api with invalid token then it will return 403(insufficient permission), if token is validated it will return 200 status code
    @GetMapping(path = "/checkToken")
    ResponseEntity<String> checkToken();


    // api to change/update the password when the user is logged in and knows the current password
    @PostMapping(path = "/changePassword")
    ResponseEntity<String> changePassword(@RequestBody Map<String, String> requestMap);


    // method for FORGET PASSWORD using @RequestBody 👇👇👇👇👇 when the user doesn't remember it
    // FIX (DEFECT_REGISTER A.3 note): @RequestBody is declared on the INTERFACE so the JSON
    // body binds reliably regardless of which layer Spring reads the annotation from.
    @PostMapping(path = "/forgotPassword")
    ResponseEntity<String> forgotPassword(@RequestBody(required = false) Map<String, String> requestMap);


//    // method for FORGET PASSWORD using @RequestParam 👇👇👇👇👇
//    ResponseEntity<String> forgotPassword(@RequestParam("email") String email);

}
