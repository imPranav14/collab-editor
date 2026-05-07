package com.pranav.collab_editor.repository;

import com.pranav.collab_editor.model.Document;
import com.pranav.collab_editor.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByOwner(User owner);
    Optional<Document> findByIdAndOwner(String id, User owner);

    // Find documents that the user owns, has been shared with, or has edited
    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN DocumentShare ds ON d = ds.document " +
           "LEFT JOIN Operation o ON d.id = o.documentId " +
           "WHERE d.owner = :user OR ds.sharedWithUser = :user OR o.clientId = :userId")
    List<Document> findDocumentsAccessibleByUser(@Param("user") User user, @Param("userId") String userId);

    // Check if user has access to a specific document (owner or shared with)
    @Query("SELECT COUNT(d) > 0 FROM Document d LEFT JOIN DocumentShare ds ON d = ds.document " +
           "WHERE d.id = :documentId AND (d.owner.id = :userId OR ds.sharedWithUser.id = :userId)")
    boolean hasUserAccessToDocument(@Param("documentId") String documentId, @Param("userId") String userId);
}
