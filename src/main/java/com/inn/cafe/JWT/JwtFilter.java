package com.inn.cafe.JWT;

import com.inn.cafe.POJO.User;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


/**
 * JWT authentication filter.
 *
 * FIX (DEFECT_REGISTER A.5/A.6): the legacy filter stored claims/userName in INSTANCE
 * fields of this singleton filter — concurrent requests raced on shared state (one
 * request could read another request's role claims). All per-request state now lives
 * in ThreadLocals that are strictly cleared in a finally block. isAdmin()/getCurrentUser()
 * are null-safe and default to "not admin" / null when no token was processed.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final ThreadLocal<Claims> CLAIMS = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_NAME = new ThreadLocal<>();

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CustomerUserDetailsService service;

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain) throws ServletException, IOException {
        try {
            if (httpServletRequest.getServletPath().matches("/user/login|/user/signup|/user/forgotPassword")) {
                // public endpoints — no token validation required
                filterChain.doFilter(httpServletRequest, httpServletResponse);
            } else {
                String authorizationHeader = httpServletRequest.getHeader("Authorization");
                String token = null;
                String userName = null;

                if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                    token = authorizationHeader.substring(7);
                    // FIX: malformed/expired/tampered tokens must degrade to "unauthenticated"
                    // (-> 403 via the security chain), never surface as a 500 from the filter.
                    try {
                        userName = jwtUtil.extractUsername(token);
                        CLAIMS.set(jwtUtil.extractAllClaims(token));
                    } catch (Exception e) {
                        userName = null;
                        CLAIMS.remove();
                    }
                }
                USER_NAME.set(userName);

                if (userName != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = service.loadUserByUsername(userName);
                    if (jwtUtil.validateToken(token, userDetails)) {
                        UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        usernamePasswordAuthenticationToken.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(httpServletRequest));
                        SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
                    }
                }
                filterChain.doFilter(httpServletRequest, httpServletResponse);
            }
        } finally {
            // ThreadLocals MUST be cleared so pooled threads never leak another request's identity.
            CLAIMS.remove();
            USER_NAME.remove();
        }
    }

    /** Null-safe role check (FIX A.6: previously NPE when claims was null). */
    public boolean isAdmin() {
        Claims claims = CLAIMS.get();
        return claims != null && "admin".equalsIgnoreCase(String.valueOf(claims.get("role")));
    }

    public boolean isUser() {
        Claims claims = CLAIMS.get();
        return claims != null && "user".equalsIgnoreCase(String.valueOf(claims.get("role")));
    }

    public String getCurrentUser() {
        return USER_NAME.get();
    }
}
