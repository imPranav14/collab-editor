package com.pranav.collab_editor.repository;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.DocumentShare;
import com.pranav.collab_editor.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentShareRepository extends JpaRepository<DocumentShare, String> {
    List<DocumentShare> findBySharedWithUser(User user);
    List<DocumentShare> findByDocument(Document document);
    Optional<DocumentShare> findByDocumentAndSharedWithUser(Document document, User user);
    boolean existsByDocumentAndSharedWithUser(Document document, User user);
}