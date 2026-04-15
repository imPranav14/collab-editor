# 🗺️ CollabEdit — Build Phases & Step-by-Step Execution Plan

> This document breaks the entire project into sequential phases. Each phase has a clear goal, detailed steps, what to build, how to build it, and what "done" looks like before moving to the next phase. Follow these in order — each phase builds directly on the previous one.

---

## Phase Overview

| Phase | Name | Duration | What You Build |
|---|---|---|---|
| **1** | Project Foundation & Dev Environment | 3–4 days | Docker, Spring Boot skeleton, DB connection |
| **2** | Core REST API + Authentication | 4–5 days | User auth, document CRUD, JWT |
| **3** | CRDT Data Structure | 5–7 days | The heart of the project — conflict resolution |
| **4** | WebSocket + Real-Time Sync | 4–5 days | STOMP, live op broadcasting |
| **5** | Persistence — Operation Log | 3–4 days | PostgreSQL op log, document replay |
| **6** | Redis — Cursor Presence | 2–3 days | Live cursor tracking via Redis |
| **7** | React Frontend | 5–7 days | Editor UI, WebSocket client, cursor rendering |
| **8** | Undo / Redo | 2–3 days | Per-user undo stack with inverse ops |
| **9** | Snapshots & Performance | 3–4 days | Fast document loading at scale |
| **10** | Load Testing & Polish | 3–4 days | JMeter, benchmarks, README, architecture diagram |

**Total estimated time:** 8–10 weeks (part-time) / 4–5 weeks (full-time focus)

---

---

# PHASE 1 — Project Foundation & Dev Environment

## Goal
Get the full local development environment running with all services connected and talking to each other. No features yet — just a solid, runnable foundation.

## What You Will Have at the End
- Spring Boot app starts without errors
- PostgreSQL database is reachable from Spring Boot
- Redis is reachable from Spring Boot
- Docker Compose spins up all services with one command
- A `/health` endpoint returns `200 OK`

---

## Step 1.1 — Create the Spring Boot Project

Go to [start.spring.io](https://start.spring.io) and generate a project with these settings:

```
Project:      Maven
Language:     Java
Spring Boot:  3.x (latest stable)
Java:         21
Packaging:    Jar

Dependencies to add:
  - Spring Web
  - Spring WebSocket
  - Spring Data JPA
  - Spring Data Redis
  - Spring Security
  - PostgreSQL Driver
  - Lombok
  - Spring Boot DevTools
```

Download, unzip, and open in IntelliJ IDEA or VS Code.

---

## Step 1.2 — Create Docker Compose File

In the project root, create `docker-compose.yml`:

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:15
    container_name: collab-postgres
    environment:
      POSTGRES_DB: collabdb
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: secret
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data

  redis:
    image: redis:7
    container_name: collab-redis
    ports:
      - "6379:6379"

volumes:
  postgres-data:
```

Start both services:
```bash
docker-compose up -d postgres redis
```

Verify they are running:
```bash
docker ps
```

---

## Step 1.3 — Configure application.yml

In `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/collabdb
    username: admin
    password: secret
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true

  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8080
```

---

## Step 1.4 — Add a Health Check Endpoint

Create `controller/HealthController.java`:

```java
@RestController
public class HealthController {
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("CollabEdit is running");
    }
}
```

Run the app (`mvn spring-boot:run`) and visit `http://localhost:8080/health`. It should return `CollabEdit is running`.

---

## Step 1.5 — Verify Redis Connection

Add this temporary test bean to confirm Redis is reachable:

```java
@Bean
CommandLineRunner testRedis(StringRedisTemplate redis) {
    return args -> {
        redis.opsForValue().set("test", "hello");
        System.out.println("Redis test: " + redis.opsForValue().get("test"));
    };
}
```

You should see `Redis test: hello` in the startup logs. Remove this bean after confirming.

## ✅ Phase 1 Done When
- `docker-compose up -d` starts Postgres and Redis without errors
- `mvn spring-boot:run` starts the app without errors
- `GET /health` returns 200
- Redis connection log shows `hello`

---

---

# PHASE 2 — Core REST API + Authentication

## Goal
Build user registration, login with JWT tokens, and full CRUD for documents. No collaboration yet — just standard REST API work.

## What You Will Have at the End
- Users can register and login
- JWT token is issued on login
- All subsequent requests use JWT in the `Authorization` header
- Users can create, list, and delete documents

---

## Step 2.1 — Create the User Entity

```java
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
}
```

---

## Step 2.2 — Create the Document Entity

```java
@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String title = "Untitled";

    @ManyToOne
    @JoinColumn(name = "owner_id")
    private User owner;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

---

## Step 2.3 — Implement JWT Authentication

Add the `jjwt` library to `pom.xml`:

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.3</version>
</dependency>
```

Create a `JwtService` that can:
- Generate a token from a username
- Validate a token
- Extract username from a token

Create a `JwtAuthFilter` (extends `OncePerRequestFilter`) that intercepts every request, reads the `Authorization: Bearer <token>` header, validates it, and sets the security context.

---

## Step 2.4 — Build Auth Endpoints

```
POST /api/auth/register   → accepts {username, email, password}, returns {token}
POST /api/auth/login      → accepts {username, password}, returns {token}
```

Password must be hashed with BCrypt before saving. Never store plaintext passwords.

---

## Step 2.5 — Build Document Endpoints

```
GET    /api/documents          → returns list of documents for logged-in user
POST   /api/documents          → creates a new document, returns its ID
GET    /api/documents/{id}     → returns document metadata
DELETE /api/documents/{id}     → deletes document (owner only)
```

All endpoints must be protected — reject requests without a valid JWT.

---

## Step 2.6 — Configure Spring Security

```java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/health", "/ws-collab/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

## ✅ Phase 2 Done When
- Can register a user with Postman/curl
- Can login and receive a JWT token
- Can create a document using the JWT token
- Can list documents for the logged-in user
- Requests without JWT return 401

---

---

# PHASE 3 — CRDT Data Structure

## Goal
Build the CRDT core as a standalone Java library — completely decoupled from Spring, WebSockets, or databases. This is the most algorithmically complex part of the project. Write it in isolation and test it exhaustively before wiring it into anything else.

## What You Will Have at the End
- A `CRDTDocument` Java class that represents the document state
- `insert()` and `delete()` methods that accept operations
- All concurrent edit scenarios converge to identical state
- Comprehensive unit tests passing for all edge cases

---

## Step 3.1 — Design the CRDTNode

Each character in the document is a node:

```java
public class CRDTNode {
    private final String id;            // e.g. "userA:42" — globally unique
    private final char value;           // the character
    private final String leftId;        // ID of the left neighbor at time of insert
    private boolean deleted;            // tombstone — soft delete, never remove nodes

    // Constructor, getters
}
```

Key insight: nodes are **never physically removed**. When a character is deleted, `deleted = true`. The document text is the sequence of non-deleted nodes. This makes undo trivial (just flip the flag) and prevents position-shift bugs.

---

## Step 3.2 — Design the CRDTDocument

```java
public class CRDTDocument {
    // The document as an ordered list of CRDT nodes
    // Ordering is determined by leftId references, not array index
    private final List<CRDTNode> nodes = new ArrayList<>();

    // Fast lookup: nodeId → CRDTNode
    private final Map<String, CRDTNode> nodeIndex = new HashMap<>();

    public void insert(CRDTOperation op) { ... }
    public void delete(CRDTOperation op) { ... }
    public String getText() { ... }     // return only non-deleted chars as a String
    public List<CRDTNode> getNodes() { ... }
}
```

---

## Step 3.3 — Implement the Insert Algorithm

This is the core of CRDT. When inserting a node:

1. Find the position of `leftId` in the nodes list
2. Start scanning right from that position
3. If there are other nodes with the same `leftId` (concurrent inserts at same spot):
   - Use the node's ID as a tiebreaker (e.g. lexicographic comparison)
   - This ensures all clients order concurrent inserts identically
4. Insert the new node at the resolved position
5. Add to `nodeIndex` for O(1) lookup

```java
public void insert(CRDTOperation op) {
    CRDTNode newNode = new CRDTNode(op.getNodeId(), op.getCharValue(), op.getLeftId());

    // Find insertion index
    int leftIndex = -1;
    if (op.getLeftId() != null) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getId().equals(op.getLeftId())) {
                leftIndex = i;
                break;
            }
        }
    }

    // Scan right for concurrent inserts with same leftId — use ID as tiebreaker
    int insertIndex = leftIndex + 1;
    while (insertIndex < nodes.size()
           && nodes.get(insertIndex).getLeftId().equals(op.getLeftId())
           && nodes.get(insertIndex).getId().compareTo(op.getNodeId()) > 0) {
        insertIndex++;
    }

    nodes.add(insertIndex, newNode);
    nodeIndex.put(newNode.getId(), newNode);
}
```

---

## Step 3.4 — Implement the Delete Algorithm

Delete is simpler — just mark the node as deleted:

```java
public void delete(CRDTOperation op) {
    CRDTNode node = nodeIndex.get(op.getNodeId());
    if (node != null) {
        node.setDeleted(true);
    }
    // If node not found, op arrived before the insert — store for later (out-of-order handling)
}
```

---

## Step 3.5 — Write Exhaustive Unit Tests

This is the most important step in the entire project. Write tests for:

```java
// Test 1: Basic insert
// Test 2: Basic delete
// Test 3: Two clients insert at same position simultaneously
//         → both clients must converge to same ordering
// Test 4: Client A deletes char, Client B inserts next to it simultaneously
//         → both must converge
// Test 5: Ops arrive out of order (Client B gets op2 before op1)
//         → must still converge
// Test 6: Client inserts, then undoes (delete), then redoes (insert again)
// Test 7: 3 clients all editing same document simultaneously
// Test 8: Empty document operations
// Test 9: Large document (10,000 nodes) performance
```

Run: `mvn test -Dtest=CRDTDocumentTest`

Do not move to Phase 4 until all tests pass.

## ✅ Phase 3 Done When
- `CRDTDocument` insert and delete work correctly
- All concurrent edit unit tests pass and converge to identical state
- getText() returns correct string from any sequence of ops
- No failing tests

---

---

# PHASE 4 — WebSocket + Real-Time Sync

## Goal
Wire the CRDT into a live WebSocket system. Two browser clients connected to the same document should see each other's edits in real time.

## What You Will Have at the End
- STOMP WebSocket endpoint is running
- Clients can connect, subscribe to a document topic, and send operations
- Server applies each op to the server-side CRDT and broadcasts to all other subscribers
- Two browser tabs stay in sync (test with Postman WebSocket or a simple HTML test page)

---

## Step 4.1 — Configure WebSocket + STOMP

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-collab")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
```

---

## Step 4.2 — Create the OperationDTO

This is the shape of the message sent over WebSocket:

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationDTO {
    private String type;            // "INSERT" or "DELETE"
    private String nodeId;          // CRDT node ID
    private String leftId;          // left neighbor ID (INSERT only)
    private Character charValue;    // character (INSERT only)
    private String clientId;        // who sent this
    private long lamportClock;      // logical timestamp
}
```

---

## Step 4.3 — Create the CollabController

```java
@Controller
public class CollabController {

    @Autowired
    private CRDTService crdtService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/document/{docId}/op")
    public void handleOperation(
            @DestinationVariable String docId,
            OperationDTO op,
            Principal user) {

        // 1. Apply to server-side CRDT (in-memory, per document)
        crdtService.apply(docId, op);

        // 2. Broadcast to all other subscribers of this document
        messagingTemplate.convertAndSend("/topic/document/" + docId, op);
    }
}
```

---

## Step 4.4 — Create CRDTService (Server-Side)

The server needs to maintain a `CRDTDocument` per active document in memory:

```java
@Service
public class CRDTService {

    // One CRDTDocument per active document
    private final Map<String, CRDTDocument> activeDocuments = new ConcurrentHashMap<>();

    public void apply(String docId, OperationDTO op) {
        CRDTDocument doc = activeDocuments.computeIfAbsent(docId, id -> new CRDTDocument());

        CRDTOperation crdtOp = new CRDTOperation(
            op.getType(), op.getNodeId(), op.getLeftId(),
            op.getCharValue(), op.getClientId(), op.getLamportClock()
        );

        if ("INSERT".equals(op.getType())) {
            doc.insert(crdtOp);
        } else {
            doc.delete(crdtOp);
        }
    }
}
```

---

## Step 4.5 — Test with a Simple HTML Client

Before building the React frontend, test WebSocket sync with a raw HTML page:

```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/sockjs-client/1.6.1/sockjs.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>
<script>
    const socket = new SockJS('http://localhost:8080/ws-collab');
    const stompClient = Stomp.over(socket);

    stompClient.connect({}, () => {
        stompClient.subscribe('/topic/document/test-doc', (msg) => {
            console.log('Received op:', JSON.parse(msg.body));
        });
    });

    function sendOp() {
        stompClient.send('/app/document/test-doc/op', {}, JSON.stringify({
            type: 'INSERT',
            nodeId: 'client1:1',
            leftId: null,
            charValue: 'H',
            clientId: 'client1',
            lamportClock: 1
        }));
    }
</script>
<button onclick="sendOp()">Send Op</button>
```

Open two browser tabs with this page. Click "Send Op" in one tab and confirm the other tab receives the message in the console.

## ✅ Phase 4 Done When
- WebSocket endpoint is accessible at `ws://localhost:8080/ws-collab`
- Two clients can connect and subscribe to the same document topic
- An op sent from one client appears in the other client's console
- Server-side CRDT updates on every received op

---

---

# PHASE 5 — Persistence — Operation Log

## Goal
Persist every operation to PostgreSQL so the document survives server restarts and new clients can reconstruct the full document state by replaying the operation log.

## What You Will Have at the End
- Every insert and delete is stored in the `operations` table
- When a new client opens a document, the server replays all ops to build current state
- Document state survives server restart

---

## Step 5.1 — Create the Operation Entity

```java
@Entity
@Table(name = "operations")
@Data
public class Operation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private String documentId;

    @Column(name = "op_type", nullable = false)
    private String opType;              // INSERT or DELETE

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "left_id")
    private String leftId;

    @Column(name = "char_value")
    private Character charValue;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "lamport_clock")
    private long lamportClock;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

---

## Step 5.2 — Persist Ops in CollabController

Update `CollabController.handleOperation()` to also save to DB:

```java
@MessageMapping("/document/{docId}/op")
public void handleOperation(@DestinationVariable String docId, OperationDTO op) {
    // 1. Apply to in-memory CRDT
    crdtService.apply(docId, op);

    // 2. Persist to PostgreSQL
    operationService.persist(docId, op);

    // 3. Broadcast
    messagingTemplate.convertAndSend("/topic/document/" + docId, op);
}
```

---

## Step 5.3 — Replay Ops on Document Load

Update `GET /api/documents/{id}` to replay the operation log and return the full document state to new clients:

```java
@GetMapping("/api/documents/{id}")
public DocumentLoadResponse loadDocument(@PathVariable String id) {
    Document doc = documentService.findById(id);

    // Fetch all operations for this document in order
    List<Operation> ops = operationRepository
        .findByDocumentIdOrderByLamportClockAsc(id);

    // Replay ops to build current CRDT state (also caches in-memory)
    crdtService.replay(id, ops);

    return new DocumentLoadResponse(doc, ops);
}
```

The client receives all ops and replays them on the client-side CRDT to build the current document state.

## ✅ Phase 5 Done When
- Every op is visible in the `operations` table after sending it via WebSocket
- After restarting the server, opening a document shows the correct content
- New clients receive the full op log on load and reconstruct the document correctly

---

---

# PHASE 6 — Redis — Cursor Presence

## Goal
Show every connected user's cursor position in real time, stored and retrieved via Redis.

## What You Will Have at the End
- Clients broadcast their cursor position every time it moves
- All cursor positions for a document are stored in Redis
- Cursor data is broadcast to all clients in the document
- Cursors disappear when a user disconnects (TTL expiry)

---

## Step 6.1 — Configure RedisTemplate

```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

---

## Step 6.2 — Create PresenceService

```java
@Service
public class PresenceService {

    @Autowired
    private RedisTemplate<String, String> redis;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int PRESENCE_TTL_SECONDS = 30;

    public void updateCursor(String docId, String userId, CursorDTO cursor) throws Exception {
        String key = "presence:" + docId;
        String value = objectMapper.writeValueAsString(cursor);
        redis.opsForHash().put(key, userId, value);
        redis.expire(key, Duration.ofSeconds(PRESENCE_TTL_SECONDS));
    }

    public List<CursorDTO> getAllCursors(String docId) {
        String key = "presence:" + docId;
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        return entries.values().stream()
            .map(v -> objectMapper.readValue((String) v, CursorDTO.class))
            .collect(Collectors.toList());
    }

    public void removeCursor(String docId, String userId) {
        redis.opsForHash().delete("presence:" + docId, userId);
    }
}
```

---

## Step 6.3 — Handle Cursor WebSocket Messages

```java
@MessageMapping("/document/{docId}/cursor")
public void handleCursor(@DestinationVariable String docId, CursorDTO cursor) {
    // Store in Redis
    presenceService.updateCursor(docId, cursor.getUserId(), cursor);

    // Broadcast all cursors to all subscribers
    List<CursorDTO> allCursors = presenceService.getAllCursors(docId);
    messagingTemplate.convertAndSend("/topic/document/" + docId + "/cursors", allCursors);
}
```

---

## Step 6.4 — Handle Disconnect

Use Spring's `SessionDisconnectEvent` to clean up cursor when a user closes the tab:

```java
@EventListener
public void handleDisconnect(SessionDisconnectEvent event) {
    String sessionId = event.getSessionId();
    // Look up which docId and userId this session belongs to (store in a map on connect)
    // Then call presenceService.removeCursor(docId, userId)
}
```

## ✅ Phase 6 Done When
- Moving the cursor in one browser tab shows the cursor moving in another tab
- Closing a tab removes that user's cursor from all other tabs within 30 seconds
- Redis `HGETALL presence:{docId}` shows correct cursor data

---

---

# PHASE 7 — React Frontend

## Goal
Build the full browser-side application: a rich text editor that connects to the Spring Boot backend via WebSocket and renders other users' cursors.

## What You Will Have at the End
- A working collaborative editor in the browser
- Multiple browser windows stay in sync in real time
- Other users' cursors are visible with color labels

---

## Step 7.1 — Setup React Project

```bash
npx create-react-app frontend --template typescript
cd frontend
npm install @stomp/stompjs sockjs-client prosemirror-state prosemirror-view prosemirror-schema-basic
```

---

## Step 7.2 — Build the STOMP WebSocket Client

Create `src/websocket/stompClient.js`:

```javascript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

export function createStompClient(docId, onOperation, onCursors) {
    const client = new Client({
        webSocketFactory: () => new SockJS('http://localhost:8080/ws-collab'),
        onConnect: () => {
            client.subscribe(`/topic/document/${docId}`, (msg) => {
                onOperation(JSON.parse(msg.body));
            });
            client.subscribe(`/topic/document/${docId}/cursors`, (msg) => {
                onCursors(JSON.parse(msg.body));
            });
        }
    });

    client.activate();
    return client;
}

export function sendOperation(client, docId, op) {
    client.publish({
        destination: `/app/document/${docId}/op`,
        body: JSON.stringify(op)
    });
}
```

---

## Step 7.3 — Build the Client-Side CRDT

Create `src/crdt/CRDTDocument.js` — a JavaScript port of your Java `CRDTDocument`. It must implement the exact same insert/delete algorithm so client and server always agree.

This JavaScript CRDT is what converts keystrokes into operations and applies received operations to update the displayed text.

---

## Step 7.4 — Build the Editor Component

The `Editor.jsx` component should:

1. On mount: fetch the document from `GET /api/documents/{id}`, replay all ops through the JS CRDT
2. On mount: connect to WebSocket and subscribe to op and cursor topics
3. On user keystroke: generate a CRDT op, apply it locally (optimistic update), send via WebSocket
4. On received op from server: apply to local CRDT, update the displayed text
5. On cursor move: send cursor position via WebSocket

---

## Step 7.5 — Build the Cursors Overlay

Create `Cursors.jsx` that renders colored cursor lines with name labels for all other connected users. Each cursor has a color (from the user's profile) and a name badge at the top.

## ✅ Phase 7 Done When
- Opening two browser windows on the same document and typing in one shows changes in the other
- Cursors from other users are visible and labeled
- Closing one window removes that user's cursor

---

---

# PHASE 8 — Undo / Redo

## Goal
Give each user an independent undo/redo stack for their own operations.

---

## Step 8.1 — Track User's Own Operations

On the client side, maintain a stack of the user's own operations in order:

```javascript
const undoStack = [];   // ops the user has done
const redoStack = [];   // ops the user has undone
```

Every time the user generates an op, push it onto `undoStack` and clear `redoStack`.

---

## Step 8.2 — Implement Undo

When the user presses Ctrl+Z:

1. Pop the last op from `undoStack`
2. Generate its inverse:
   - If op was `INSERT(nodeId, char)` → create `DELETE(nodeId)`
   - If op was `DELETE(nodeId)` → create `INSERT(nodeId, char, leftId)` (you stored these)
3. Apply the inverse op locally
4. Send the inverse op to the server via WebSocket
5. Push the original op onto `redoStack`

---

## Step 8.3 — Implement Redo

When the user presses Ctrl+Y:

1. Pop the last op from `redoStack`
2. Apply it locally
3. Send it to the server
4. Push it back onto `undoStack`

## ✅ Phase 8 Done When
- Ctrl+Z undoes the user's last operation and the change disappears from all clients
- Ctrl+Y redoes it and the change reappears on all clients
- Undoing does not undo other users' changes

---

---

# PHASE 9 — Snapshots & Performance Optimization

## Goal
Prevent documents with thousands of operations from becoming slow to load by adding periodic snapshots.

---

## Step 9.1 — Create Snapshot Entity

```java
@Entity
@Table(name = "snapshots")
public class Snapshot {
    @Id
    @GeneratedValue
    private Long id;
    private String documentId;
    @Column(columnDefinition = "TEXT")
    private String content;         // full serialized CRDT state as JSON
    private Long lastOpId;          // replay only ops with id > lastOpId
    private LocalDateTime createdAt;
}
```

---

## Step 9.2 — Trigger Snapshot Creation

After every 500 operations on a document, take a snapshot:

```java
// In OperationService.persist()
long opCount = operationRepository.countByDocumentId(docId);
if (opCount % 500 == 0) {
    snapshotService.takeSnapshot(docId);
}
```

`takeSnapshot()` serializes the current in-memory `CRDTDocument` to JSON and stores it in the `snapshots` table.

---

## Step 9.3 — Use Snapshot on Document Load

```java
public DocumentLoadResponse loadDocument(String docId) {
    // Find the latest snapshot
    Optional<Snapshot> snapshot = snapshotRepository
        .findTopByDocumentIdOrderByCreatedAtDesc(docId);

    if (snapshot.isPresent()) {
        // Load from snapshot (fast) + replay only ops after lastOpId
        List<Operation> pendingOps = operationRepository
            .findByDocumentIdAndIdGreaterThan(docId, snapshot.get().getLastOpId());
        return new DocumentLoadResponse(snapshot.get(), pendingOps);
    } else {
        // No snapshot — replay all ops from beginning
        List<Operation> allOps = operationRepository
            .findByDocumentIdOrderByLamportClockAsc(docId);
        return new DocumentLoadResponse(null, allOps);
    }
}
```

## ✅ Phase 9 Done When
- Documents with 10,000+ ops load in under 200ms (snapshot makes this fast)
- New snapshots are created automatically every 500 ops
- Rolling back to a snapshot + replaying pending ops produces correct state

---

---

# PHASE 10 — Load Testing, Polish & Resume Wrap-Up

## Goal
Stress-test the system, measure real performance numbers, write the architecture diagram, and finalize the README. This is what makes the project resume-ready.

---

## Step 10.1 — Load Test with JMeter

Install Apache JMeter. Create a test plan that simulates 50 concurrent users all editing the same document:

- 50 threads (users), each sending 1 op per second
- Measure: average latency, p99 latency, throughput (ops/sec), error rate
- Run for 5 minutes

Record real numbers. Sample resume-worthy results:
- "Handles 50 concurrent users with p99 op latency of 48ms"
- "Sustains 3,000 ops/sec with 0% error rate"

---

## Step 10.2 — Draw the Architecture Diagram

Use [Excalidraw](https://excalidraw.com) or [draw.io](https://draw.io) to create a clean architecture diagram showing:

- Browser clients connecting via WebSocket
- Spring Boot server with labeled components
- PostgreSQL and Redis boxes
- Arrows showing data flow for a single keystroke

Export as PNG and include in the README.

---

## Step 10.3 — Write Comprehensive Tests

Ensure you have:
- Unit tests for all CRDT convergence scenarios
- Integration tests for the WebSocket flow
- Repository tests for operation log queries
- At least 70% code coverage

Run: `mvn verify` and attach the coverage report.

---

## Step 10.4 — Finalize README

Your README must include:
- Clear one-paragraph description of what the project does
- Architecture diagram
- Tech stack table
- How to run locally (must work in one command: `docker-compose up`)
- Key design decisions and why you made them
- Real benchmark numbers from JMeter

---

## Step 10.5 — Deploy (Bonus)

Deploy to a free tier or low-cost cloud:

- **Backend**: Railway.app or Render (free Spring Boot hosting)
- **Frontend**: Vercel or Netlify (free React hosting)
- **Database**: Supabase (free PostgreSQL)
- **Redis**: Upstash (free Redis)

A live demo link on your resume is worth more than any description.

---

## Step 10.6 — Craft the Resume Bullet

```
• Built a real-time collaborative document editor (Google Docs-style) supporting
  50+ concurrent users with CRDT-based conflict resolution in Java/Spring Boot,
  WebSocket/STOMP broadcasting, PostgreSQL event-sourced operation log for full
  edit history, and Redis cursor presence with sub-10ms latency.
```

## ✅ Phase 10 Done When
- JMeter test runs successfully with measured numbers
- Architecture diagram is in the README
- Project is on GitHub with a clean commit history
- README is complete and a stranger could run it in under 10 minutes
- (Bonus) Live demo URL is accessible

---

---

## 🎯 Final Checklist Before Adding to Resume

- [ ] GitHub repo is public with a descriptive name
- [ ] README has architecture diagram, setup instructions, and benchmark numbers
- [ ] All CRDT unit tests pass
- [ ] Docker Compose runs everything with one command
- [ ] You can explain CRDT, Lamport clocks, and STOMP in an interview without notes
- [ ] You have real JMeter numbers to quote
- [ ] (Bonus) Live demo is deployed and accessible
