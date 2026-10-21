package com.inn.cafe.JWT;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;


@Service
public class JwtUtil {
    // FIX (security hardening): secret externalized to configuration (cafe.jwt.secret /
    // JWT_SECRET env) with a dev-only default so existing local setups keep working.
    // Production MUST override it — see docs/DEPLOYMENT.md.
    @org.springframework.beans.factory.annotation.Value("${cafe.jwt.secret:itachiuchiha12345678901234567890itachiuchiha12345678901234567890itachiuchiha12345678901234567890}")
    private String secret;


    /**
        Here below Secret Key [i.e. itachiuchiha]  will give an error because HS256 needs bit size >= 256(32 byte) and here it is 12 * 8 = 96 bits (12 bytes)
        How to calculate the bit/byte
          1 byte = 8 bit
          And length of 'itachiuchiha' = 12 character which represents 1 byte
          So, 12 * 8 = 96 which is equal to 12 bytes, and we are using the HS256 to hash the password which
          requires the secret key length >= 32 bytes (or >= 256 bits).
     */
//    private String secret = "itachiuchiha";




    // Logic to extract the userName
    public String extractUsername(String token){
        return extractClaims(token, Claims::getSubject); //we'll set the username and getSubject is key here So we'll write "getSubject"
    }

    // Logic to extract the expiration time
    public Date extractExpiration(String token){
        return extractClaims(token, Claims::getExpiration);
    }


    public <T> T extractClaims(String token, Function<Claims, T> claimsResolver){
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public Claims extractAllClaims(String token){
        return Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody();
    }

    // method to pass the value inside the "createToken" to generate the token [value will be: username and claims(in claim we'll put role)]
    public String generateToken(String  usernamme, String role){
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        return createToken(claims, usernamme);  // this will generate the token
    }


    // method to create the jwt token
    // here we have to simply pass the subject or claims to generate the token
    private String createToken(Map<String, Object> claims, String subject){
        byte[] apiKeySecretBytes = secret.getBytes();  // Convert to byte array to ensure proper encoding
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                // 1000ms * 60s * 60m * 10h = 10 hours (ie. 1000ms*60s=1min, 1min*60min=1hr, 1hr*10hr=10hr)
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10))
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    /**
     // createToken CAN ALSO BE IMPLEMENTED AS 👇

     * import io.jsonwebtoken.security.Keys;
     * import javax.crypto.SecretKey;
     *
     * private SecretKey secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256); // Generates a 256-bit key
     *
     * private String createToken(Map<String, Object> claims, String subject){
     *     return Jwts.builder()
     *             .setClaims(claims)
     *             .setSubject(subject)
     *             .setIssuedAt(new Date(System.currentTimeMillis()))
     *             .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10))
     *             .signWith(secretKey)  // Use the generated key
     *             .compact();
     * }
     * @param token
     * @return
     */

    // method to check whether the token is expired or not [we'll use the method "extractExpiration"]
    private Boolean isTokenExpired(String token){
        return extractExpiration(token).before(new Date());
    }

    // we'll use the "isTokenExpired" and "extractUsername" method to validate the token if it is not expired
    public Boolean validateToken(String token, UserDetails userDetails) {
        final  String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));

    }
}
