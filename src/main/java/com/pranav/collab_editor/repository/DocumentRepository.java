package com.pranav.collab_editor.repository;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByOwner(User owner);
}
