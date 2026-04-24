package com.pranav.collab_editor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures the WebSocket server using STOMP as the messaging sub-protocol.
 *
 * STOMP (Simple Text Oriented Messaging Protocol) adds a layer on top of raw
 * WebSocket that gives us:
 *   - Named destinations (like topics) so clients can subscribe to specific documents
 *   - A message broker that handles routing/broadcasting for us
 *   - SockJS fallback for browsers that don't support WebSocket
 *
 * How messages flow:
 *   Client sends  →  /app/document/{id}/op     →  @MessageMapping handler
 *   Handler broadcasts  →  /topic/document/{id}  →  all subscribed clients
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configures the message broker — the component that routes messages
     * between clients.
     *
     * enableSimpleBroker("/topic"):
     *   Activates Spring's built-in in-memory broker.
     *   Any message sent to a destination starting with /topic is automatically
     *   broadcast to all clients subscribed to that destination.
     *   Example: broadcasting an op to /topic/document/doc-123 delivers it to
     *   every client currently editing document doc-123.
     *
     * setApplicationDestinationPrefixes("/app"):
     *   Messages from clients that start with /app are routed to
     *   @MessageMapping methods in our controllers (not to the broker directly).
     *   Example: client sends to /app/document/doc-123/op → hits our handler.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Registers the WebSocket handshake endpoint that clients connect to.
     *
     * /ws-collab:
     *   The URL clients use to establish a WebSocket connection.
     *   Full URL: ws://localhost:8080/ws-collab
     *
     * setAllowedOriginPatterns("*"):
     *   Allows connections from any origin during development.
     *   In production, restrict this to your frontend domain:
     *   .setAllowedOriginPatterns("https://yourdomain.com")
     *
     * withSockJS():
     *   Adds a fallback transport for environments where WebSocket is blocked
     *   (some corporate proxies, older browsers). SockJS tries WebSocket first,
     *   then falls back to long-polling or other transports transparently.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-collab")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}