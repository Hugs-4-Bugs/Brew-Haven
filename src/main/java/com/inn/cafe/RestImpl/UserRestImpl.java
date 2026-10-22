package com.inn.cafe.RestImpl;

import com.inn.cafe.Constents.CafeConstants;
import com.inn.cafe.Rest.UserRest;
import com.inn.cafe.Service.UserService;
import com.inn.cafe.Utils.CafeUtils;
import com.inn.cafe.Wrapper.UserWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping(path = "/user")
public class UserRestImpl implements UserRest {

    /**
   to get below method directly click on the line in which compiler error is showing
   with red underline and then red bulb will arise and just click on that bulb, you will
   get multiple option then just click on "Implement Methods" and ok, and it will override the methods
   [ here UserRestImpl class is implementing ie, Overriding the method of UserRest interface]

   and replace the 'return null' and put everything inside the try and catch block

   @Override
   public ResponseEntity<String> signUp(Map<String, String> requestMapping) {
       return null;
   }
     */

    @Autowired  // by autowiring UserService we'll be able to access all the methods available in userService
    private UserService userService;

    @Override
    public ResponseEntity<String> getMyProfile() {
        try {
            return userService.getMyProfile();
        } catch (Exception ex) {
            log.error("Exception in getMyProfile: ", ex);
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


    @Override
    public ResponseEntity<String> signUp(Map<String, String> requestMap) {
        try{
            return userService.signUp(requestMap);

        } catch(Exception ex){
          ex.printStackTrace();
            log.error("Exception in signUp: ", ex);  // Changed to logging instead of printStackTrace()

            // since i don't want to send the exception to the end user so in that case we'll return the custom message as

        }

        // we can write the below return statement here also but the problem is we need to write this again and again so we will move this into the
        // utility class so that whenever this will be called then it will be called from utility class [CafeUtil class] directly

        // return new ResponseEntity<String>("{\"message\":\"Something Went Wrong\"}", HttpStatus.INTERNAL_SERVER_ERROR);



        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);

    }

    @Override
    public ResponseEntity<String> login(Map<String, String> requestMap) {
        try {
          return userService.login(requestMap);

//            ResponseEntity<String> response = userService.login(requestMap);
//            if(response.getStatusCode() == HttpStatus.OK) {
//                String token = response.getBody();  // Assuming token is returned by login method
//                return new ResponseEntity<String>("{\"token\":\"" + token + "\"}", HttpStatus.OK);
//            }
//            return response;  // Return original response if status is not OK

        } catch (Exception ex){
            ex.printStackTrace();
            log.error("Exception in login: ", ex);  // Changed to logging instead of printStackTrace()
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);

    }


// to get all the user by admin role
    @Override
    public ResponseEntity<List<UserWrapper>> getAllUser() {
        try {
            return userService.getAllUser();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new ResponseEntity<List<UserWrapper>>(new ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);

    }


// method to update the user account (or to enable or disable the user account)
    @Override
    public ResponseEntity<String> update(Map<String, String> requestMap) {
        try {
            return userService.update(requestMap);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }


// method to check the token for validation whether api is being accessed by user or admin. if you try to hit the api with invalid token then it will return 403(insufficient permission), if token is validated it will return 200 status code
    @Override
    public ResponseEntity<String> checkToken() {
        try{
            return userService.checkToken();
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
//        return CafeUtils.getResponseEntity((CafeConstants.SOMETHING_WENT_WRONG), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    public ResponseEntity<String> changePassword(Map<String, String> requestMap) {
        try{
            return userService.changePassword(requestMap);
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
//        return CafeUtils.getResponseEntity((CafeConstants.SOMETHING_WENT_WRONG), HttpStatus.INTERNAL_SERVER_ERROR);
    }



/**
// method to FORGET PASSWORD using @RequestParam 👇👇👇👇👇

// URL: http://localhost:8000/user/forgotPassword?email=erprabhat72@gmail.com

 @Override
    @PostMapping("/forgotPassword")
    public ResponseEntity<String> forgotPassword(@RequestParam("email") String email) {
        try {
            // Calling service method and passing the email
            ResponseEntity<String> responseEntity = userService.forgotPassword(email);
            return responseEntity;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }
*/



// method to FORGET PASSWORD using @RequestBody  👇👇👇👇👇

    @Override
    public ResponseEntity<String> forgotPassword(@RequestBody Map<String, String> requestMap) {
        try{
            ResponseEntity<String> responseEntity = userService.forgotPassword(requestMap);
//            return userService.forgotPassword(requestMap);
            return responseEntity;
        }catch (Exception ex){
        ex.printStackTrace();
        }
        // in case something went wrong then we will return the below message with status code 500 (internal server error)
        return CafeUtils.getResponseEntity(CafeConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }



}
