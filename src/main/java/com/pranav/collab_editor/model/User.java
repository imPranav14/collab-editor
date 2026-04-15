package com.pranav.collab_editor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;                  // stored as bcrypt hash

    private String color = "#3B82F6";         // cursor color for this user

    @CreationTimestamp
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "owner")
    @JsonIgnore
    private List<Document> documents;
}
