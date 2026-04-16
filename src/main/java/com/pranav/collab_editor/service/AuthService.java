package com.pranav.collab_editor.service;

import com.pranav.collab_editor.model.User;
import com.pranav.collab_editor.repository.UserRepository;
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

    public static record RegisterRequest(String username, String email, String password) {}
    public static record LoginRequest(String username, String password) {}
    public static record AuthResponse(String token, String error) {}
}