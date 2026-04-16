package com.pranav.collab_editor.security;

import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.UserRepository;
import com.pranav.collab_editor.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            // Extract the Bearer token from Authorization header
            String authHeader = request.getHeader("Authorization");
            
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);  // Remove "Bearer " prefix
                
                // Validate token
                String validationResult = jwtService.validateToken(token);
                if (validationResult == null) {
                    // Extract username from token
                    String username = jwtService.extractUsername(token);
                    
                    if (username != null) {
                        Optional<User> userOpt = userRepository.findByUsername(username);
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            UsernamePasswordAuthenticationToken auth = 
                                new UsernamePasswordAuthenticationToken(user, null, null);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        } else {
                            logger.warn("Authenticated username not found in database: " + username);
                        }
                    }
                } else {
                    logger.warn("Token validation failed: " + validationResult);
                }
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication in security context", e);
        }
        
        // Continue the filter chain
        filterChain.doFilter(request, response);
    }
}
