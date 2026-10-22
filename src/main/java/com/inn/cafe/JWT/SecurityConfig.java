package com.inn.cafe.JWT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.BeanIds;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Autowired
     CustomerUserDetailsService customerUserDetailsService;
    @Autowired
    JwtFilter jwtFilter;



    @Bean  // used to declare the bean into the configuration class
    public PasswordEncoder passwordEncoder() {    // define passwordEncoder() as a PasswordEncoder bean that returns a BCryptPasswordEncoder.
        return new BCryptPasswordEncoder();
//        return NoOpPasswordEncoder.getInstance();
    }

/**
//    @Bean
//    public BCryptPasswordEncoder bCryptPasswordEncoder() {
//        return new BCryptPasswordEncoder();
//    }
*/

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(customerUserDetailsService)
                .passwordEncoder(passwordEncoder());
    }



    @Bean(name = BeanIds.AUTHENTICATION_MANAGER)


    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
    /**
     * Override this 👆 method to expose the {@link AuthenticationManager} from
     * {@link #configure(AuthenticationManagerBuilder)} to be exposed as a Bean. For
     * example:
     * <pre>
     * &#064;Bean(name name="myAuthenticationManager")
     * &#064;Override
     * public AuthenticationManager authenticationManagerBean() throws Exception {
     *     return super.authenticationManagerBean();
     * }
     * </pre>
     */





/**
    SESSION MANAGEMENT IMPLEMENTATION:

    Session data refers to information stored on the server to track a user's state during their session, such as
    authentication details, user preferences, shopping cart contents, form inputs, tracking data, and temporary
    information. In a stateless session model, the server doesn’t store session data between requests, relying on the
    client (e.g., JWT tokens or cookies) to maintain state, which enhances scalability and reduces server memory usage.
 */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.cors().configurationSource(request -> new CorsConfiguration().applyPermitDefaultValues())
//                .and() Chains multiple configurations together.
                .and()

//                .csrf().disable()  Disables CSRF protection, usually for stateless APIs.
                .csrf().disable()

//                .authorizeRequests()  Starts the request authorization configuration.
                .authorizeRequests()
//                .authorizeHttpRequests()

//                .antMatcher() Restricts the security configurations to specific user-related endpoints (login, signup, and forgot password)
//                 or it Specifies which URLs are publicly accessible.
                .antMatchers("/user/login","/user/signup","/user/forgotPassword").permitAll()

                // FIX (DEFECT_REGISTER A.9): permit the /error dispatch so unauthenticated
                // requests to missing/broken endpoints return proper 404/500 semantics
                // instead of a misleading 403.
                .antMatchers("/error").permitAll()

                // Payment provider webhook — unauthenticated by design (provider servers
                // cannot carry our JWT); authenticity is enforced by the HMAC signature
                // verification inside the handler (X-Razorpay-Signature).
                .antMatchers("/checkout/webhook").permitAll()

                // NOTE (DEFECT_REGISTER A.8): the legacy matcher "/api/category/**" never
                // matched the real /category/** paths and was removed. Admin enforcement for
                // /category/**, /product/** mutations, /user/get, /user/update, bill deletion
                // and Excel upload is performed authoritatively at the service layer via
                // JwtFilter.isAdmin().

//                .permitAll() Grants unrestricted access to the specified URLs.
//                .permitAll()

                // used for Swagger Implementation
                .antMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/webjars/**"
                ).permitAll()    // swagger implementation ends here

//                .anyRequest() Specifies that any other request should be authenticated.
                .anyRequest()

//                .authenticated() Requires authentication for other requests.
                .authenticated()

//                .and().exceptionHandling()  Begins the configuration for handling exceptions.
                .and().exceptionHandling()

//                .and() Chains additional configurations.
                .and()

//                .sessionManagement() Starts the session management configuration.
                .sessionManagement()

//                .sessionCreationPolicy(SessionCreationPolicy.STATELESS);  Configures session creation policy to be stateless.
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS);  // Stateless session management


        // The `http.addFilterBefore()` method adds a custom filter before the specified filter in the security filter
        // chain, allowing for additional processing (e.g., logging requests, validating JWT tokens, or custom authentication)
        // before the `UsernamePasswordAuthenticationFilter`.
        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);


    }
    /**
     * Override this above 👆 method [ie. http.cors() method] to configure the {@link HttpSecurity}. Typically subclasses
     * should not invoke this method by calling super as it may override their
     * configuration. The default configuration is:
     * <pre>
     * http.authorizeRequests().anyRequest().authenticated().and().formLogin().and().httpBasic();
     * </pre>
     * Any endpoint that requires defense against common vulnerabilities can be specified
     * here, including public ones. See {@link HttpSecurity#authorizeRequests} and the
     * `permitAll()` authorization rule for more details on public endpoints.
     */

}
