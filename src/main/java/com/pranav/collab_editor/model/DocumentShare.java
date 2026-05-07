package com.pranav.collab_editor.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_shares")
@Data
@NoArgsConstructor
public class DocumentShare {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne
    @JoinColumn(name = "document_id")
    private Document document;

    @ManyToOne
    @JoinColumn(name = "shared_with_user_id")
    private User sharedWithUser;

    @ManyToOne
    @JoinColumn(name = "shared_by_user_id")
    private User sharedByUser;

    @Enumerated(EnumType.STRING)
    private Permission permission = Permission.EDIT;

    @CreationTimestamp
    private LocalDateTime sharedAt;

    public enum Permission {
        VIEW, EDIT
    }
}