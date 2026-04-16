package com.pranav.collab_editor.security;

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

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Autowired
    private JwtService jwtService;

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
                        // Create authentication object and set it in the security context
                        UsernamePasswordAuthenticationToken auth = 
                            new UsernamePasswordAuthenticationToken(username, null, null);
                        SecurityContextHolder.getContext().setAuthentication(auth);
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
