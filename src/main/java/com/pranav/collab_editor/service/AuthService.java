package com.pranav.collab_editor.service;

import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.UserRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            return new AuthResponse(null, "Username is already taken");
        }

        if (userRepository.existsByEmail(request.email())) {
            return new AuthResponse(null, "Email is already taken");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        String token = jwtService.generateToken(user.getUsername());
        return new AuthResponse(token, null);
    }

    public AuthResponse login(LoginRequest request) {
        Optional<User> userOpt = userRepository.findByUsername(request.username());
        if (userOpt.isEmpty()) {
            return new AuthResponse(null, "Invalid username or password");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            return new AuthResponse(null, "Invalid username or password");
        }

        String token = jwtService.generateToken(user.getUsername());
        return new AuthResponse(token, null);
    }

    public static record RegisterRequest(
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 20, message = "Username must be 3-20 characters")
            String username,

            @Email(message = "Invalid email format")
            @NotBlank(message = "Email is required")
            String email,

            @NotBlank(message = "Password is required")
            @Size(min = 3, message = "Password must be at least 3 characters")
            String password
    ) {}

    public static record LoginRequest(
            @NotBlank(message = "Username is required")
            String username,

            @NotBlank(message = "Password is required")
            String password
    ) {}
    
    public static record AuthResponse(String token, String error) {}
}