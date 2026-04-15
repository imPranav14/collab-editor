# 📝 CollabEdit — Real-Time Collaborative Document Editor

> A production-grade, Google Docs-like collaborative editor where multiple users can edit the same document simultaneously with zero conflicts, powered by CRDT-based conflict resolution, WebSocket real-time sync, and a robust Java/Spring Boot backend.

---

## 📌 Table of Contents

- [What Are We Building?](#what-are-we-building)
- [Why This Project?](#why-this-project)
- [Core Features](#core-features)
- [System Architecture](#system-architecture)
- [Tech Stack](#tech-stack)
- [How It Works — The Big Picture](#how-it-works--the-big-picture)
- [Key Concepts Explained](#key-concepts-explained)
- [Project Structure](#project-structure)
- [Database Schema](#database-schema)
- [Redis Design](#redis-design)
- [API Reference](#api-reference)
- [WebSocket Events](#websocket-events)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Running Tests](#running-tests)
- [Performance & Benchmarks](#performance--benchmarks)
- [Scaling Strategy](#scaling-strategy)
- [Resume Impact](#resume-impact)

---

## What Are We Building?

CollabEdit is a **real-time collaborative text editor** — similar to Google Docs — where:

- Multiple users can open the **same document simultaneously**
- Every keystroke from any user appears on **all other screens in real time**
- Two users editing the **same spot at the same time never causes conflicts or data loss**
- The **complete edit history** of every document is preserved forever
- Users can see each other's **live cursor positions** with name labels
- Every user gets **undo/redo** that only affects their own edits

The hardest part of this project is not the UI or the API — it is solving the **distributed consistency problem**: how do you guarantee that two users editing the same character position at the same moment always end up with the same final document, regardless of network delays or ordering of messages?

We solve this using **CRDTs (Conflict-free Replicated Data Types)** — a mathematically proven approach used by Figma, Notion, and Linear in production.

---

## Why This Project?

This project is intentionally designed to be complex and multi-dimensional. It touches:

- **Distributed systems** — multi-client state synchronization
- **Real-time systems** — WebSocket-based low-latency communication
- **Advanced algorithms** — CRDT data structures, Lamport clocks
- **Database design** — event-sourced operation logs, snapshotting
- **Caching** — Redis for sub-millisecond presence data
- **Full-stack engineering** — non-trivial frontend state + serious backend logic
- **Performance engineering** — load testing, benchmarking, optimization
- **Systems design** — horizontally scalable multi-instance architecture

Every one of these topics is a direct talking point in SDE interviews at top companies.

---

## Core Features

### Real-Time Collaboration
- Live sync of all edits across all connected clients
- Sub-100ms propagation of changes under normal network conditions
- Works correctly even when users go offline briefly and reconnect

### CRDT Conflict Resolution
- Concurrent edits are automatically merged without any user intervention
- No "last write wins" — every character from every user is preserved intelligently
- Mathematically guaranteed convergence — all clients always reach identical state

### Persistent Operation Log
- Every insert and delete is recorded permanently in PostgreSQL
- New users joining a document replay the full history to reconstruct current state
- Full edit history is queryable — you can see who typed what and when

### Cursor Presence
- See every connected user's cursor position in real time
- Each user gets a unique color and name label on their cursor
- Cursor positions update via Redis with sub-10ms latency

### Document Snapshots
- Periodic snapshots of full document state stored in PostgreSQL
- New clients load from the latest snapshot then replay only recent ops
- Prevents unbounded replay time as documents grow

### Undo / Redo
- Each user has their own independent undo/redo stack
- Undoing an operation broadcasts an inverse operation to all clients
- Works correctly even when others have edited around your changes

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                            CLIENTS                                   │
│                                                                      │
│   ┌──────────────┐      ┌──────────────┐      ┌──────────────┐      │
│   │   Browser A  │      │   Browser B  │      │   Browser C  │      │
│   │  React +     │      │  React +     │      │  React +     │      │
│   │  ProseMirror │      │  ProseMirror │      │  ProseMirror │      │
│   │  CRDT (JS)   │      │  CRDT (JS)   │      │  CRDT (JS)   │      │
│   └──────┬───────┘      └──────┬───────┘      └──────┬───────┘      │
│          │ WebSocket/STOMP     │ WebSocket/STOMP      │ WebSocket    │
└──────────┼─────────────────────┼──────────────────────┼─────────────┘
           │                     │                      │
┌──────────▼─────────────────────▼──────────────────────▼─────────────┐
│                        SPRING BOOT SERVER                            │
│                                                                      │
│   ┌─────────────────┐    ┌──────────────────┐   ┌───────────────┐  │
│   │  WebSocket Hub  │    │   CRDT Service   │   │ Presence Svc  │  │
│   │  STOMP Broker   │    │  (Java CRDT impl)│   │ (Redis calls) │  │
│   └────────┬────────┘    └────────┬─────────┘   └───────┬───────┘  │
│            │                      │                      │          │
│   ┌────────▼──────────────────────▼──────────────────────▼───────┐  │
│   │              REST Controllers + Message Handlers              │  │
│   └────────────────────────────────────────────────────────────┘  │
│                                                                      │
└────────────────────────────┬─────────────────────┬──────────────────┘
                             │                     │
              ┌──────────────▼──────┐   ┌──────────▼────────┐
              │    PostgreSQL       │   │       Redis        │
              │                    │   │                    │
              │  - documents       │   │  - cursor presence │
              │  - operations log  │   │  - pub/sub (scale) │
              │  - snapshots       │   │                    │
              │  - users           │   └────────────────────┘
              └────────────────────┘
```

### Data Flow for a Single Keystroke

```
1.  User types 'H' in Browser A
          ↓
2.  React captures the keystroke
          ↓
3.  Client-side CRDT generates an operation:
    { type: INSERT, id: "userA:42", leftId: "userA:41", char: 'H' }
          ↓
4.  Operation sent via STOMP WebSocket to:
    /app/document/{docId}/op
          ↓
5.  CollabController receives the operation
          ↓
6.  CRDTService applies the op to server-side document state
          ↓
7.  OperationRepository persists op to PostgreSQL
          ↓
8.  SimpMessagingTemplate broadcasts to:
    /topic/document/{docId}
          ↓
9.  All other connected browsers receive the op
          ↓
10. Each browser's client-side CRDT applies the op locally
          ↓
11. React re-renders the editor with the new character
```

---

## Tech Stack

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| **Language** | Java | 21 | Backend language with virtual threads |
| **Framework** | Spring Boot | 3.x | Application framework |
| **WebSockets** | Spring WebSocket + STOMP | 3.x | Real-time bidirectional communication |
| **Database** | PostgreSQL | 15 | Persistent operation log and document storage |
| **ORM** | Spring Data JPA + Hibernate | 3.x | Database access layer |
| **Cache / Presence** | Redis | 7 | Cursor presence and pub/sub for scaling |
| **Redis Client** | Spring Data Redis | 3.x | Redis integration for Spring |
| **Frontend** | React | 18 | UI framework |
| **Editor** | ProseMirror | Latest | Rich text editor primitives |
| **WS Client** | SockJS + STOMP.js | Latest | WebSocket client with fallback |
| **Build Tool** | Maven | 3.x | Java dependency management |
| **Containerization** | Docker + Docker Compose | Latest | Local dev environment |
| **Testing** | JUnit 5 + Mockito | Latest | Unit and integration testing |
| **Load Testing** | JMeter | Latest | Concurrent user simulation |

---

## How It Works — The Big Picture

### The Problem: Concurrent Edits

Consider two users editing `"Hello World"` at the same time with network lag:

```
Original: "Hello World"

User A deletes "World"       →  produces: "Hello"
User B types " Everyone"     →  produces: "Hello World Everyone"

Both ops reach the server in different orders on different clients.
Without conflict resolution:
  Client 1 sees: "Hello"                ← User B's edit is lost
  Client 2 sees: "Hello World Everyone" ← User A's edit is lost
  Server sees:   ??? (unpredictable)

With CRDT:
  All clients converge to: "Hello Everyone" ✅
```

### The Solution: CRDT

Instead of storing the document as a string with integer indices (which shift when content is inserted or deleted), CRDT stores it as a **linked list of nodes** where each node has:

- A **globally unique ID** (clientId + Lamport clock, e.g. `"userA:42"`)
- The **character value**
- A pointer to its **left neighbor by ID** (not by position)
- A **tombstone flag** for soft deletes

Because every operation references stable IDs instead of shifting integer positions, operations from any client can be applied in any order and the result is always the same. This property is called **commutativity** — the mathematical guarantee behind CRDTs.

---

## Key Concepts Explained

### CRDT (Conflict-free Replicated Data Type)

A data structure designed so that:
- Multiple replicas (clients) can be updated independently and concurrently
- All replicas will always converge to the same state
- No coordination or central lock is required

We implement a variant called **RGA (Replicated Growable Array)** for text editing.

### Lamport Clock

A logical timestamp used to order events across distributed systems without relying on wall-clock time:
- Each client maintains a counter
- Counter increments on every local operation
- Counter is updated to `max(local, received) + 1` on receiving a message
- Together with the clientId, forms a globally unique, totally ordered identifier

### STOMP over WebSocket

STOMP (Simple Text Oriented Messaging Protocol) is a messaging protocol that runs over WebSocket. It provides:
- **Topics** — clients subscribe to `/topic/document/{id}` to receive ops for a specific document
- **Application destinations** — clients send to `/app/document/{id}/op` to submit operations
- **Simple broker** — Spring's built-in in-memory broker handles routing

### Operation Log (Event Sourcing)

Instead of storing only the current document state, we store every operation ever applied to the document. The current state can always be reconstructed by replaying all operations from the beginning. Benefits:
- Full audit trail — who changed what and when
- Time travel — reconstruct document at any point in time
- Recovery — no data is ever truly lost

---

## Project Structure

```
collab-editor/
│
├── backend/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/collab/
│   │       │   │
│   │       │   ├── config/
│   │       │   │   ├── WebSocketConfig.java       # STOMP endpoint + broker config
│   │       │   │   ├── RedisConfig.java            # RedisTemplate bean config
│   │       │   │   └── SecurityConfig.java         # JWT auth + CORS
│   │       │   │
│   │       │   ├── controller/
│   │       │   │   ├── DocumentController.java     # REST: CRUD for documents
│   │       │   │   ├── CollabController.java       # @MessageMapping: handle WS ops
│   │       │   │   └── AuthController.java         # Login, register, JWT issue
│   │       │   │
│   │       │   ├── service/
│   │       │   │   ├── CRDTService.java            # Core CRDT logic (apply, merge)
│   │       │   │   ├── DocumentService.java        # Save, load, snapshot documents
│   │       │   │   ├── PresenceService.java        # Redis cursor tracking
│   │       │   │   ├── OperationService.java       # Persist and replay operations
│   │       │   │   └── UserService.java            # User management
│   │       │   │
│   │       │   ├── crdt/
│   │       │   │   ├── CRDTDocument.java           # The document as a CRDT structure
│   │       │   │   ├── CRDTNode.java               # Single character node
│   │       │   │   └── CRDTOperation.java          # Insert / Delete operation record
│   │       │   │
│   │       │   ├── model/
│   │       │   │   ├── Document.java               # JPA entity — documents table
│   │       │   │   ├── Operation.java              # JPA entity — operations table
│   │       │   │   ├── Snapshot.java               # JPA entity — snapshots table
│   │       │   │   └── User.java                   # JPA entity — users table
│   │       │   │
│   │       │   ├── dto/
│   │       │   │   ├── OperationDTO.java           # WS message payload
│   │       │   │   ├── CursorDTO.java              # Cursor position payload
│   │       │   │   └── DocumentDTO.java            # REST response shape
│   │       │   │
│   │       │   └── repository/
│   │       │       ├── DocumentRepository.java
│   │       │       ├── OperationRepository.java
│   │       │       ├── SnapshotRepository.java
│   │       │       └── UserRepository.java
│   │       │
│   │       └── resources/
│   │           └── application.yml
│   │
│   ├── src/test/java/com/collab/
│   │   ├── crdt/
│   │   │   └── CRDTDocumentTest.java              # Unit tests for CRDT convergence
│   │   └── service/
│   │       └── CollabControllerTest.java          # Integration tests
│   │
│   ├── Dockerfile
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── Editor.jsx                         # Main editor component
│   │   │   ├── Cursors.jsx                        # Other users' cursor overlays
│   │   │   ├── Toolbar.jsx                        # Formatting controls
│   │   │   └── DocumentList.jsx                   # List of available documents
│   │   │
│   │   ├── crdt/
│   │   │   └── CRDTDocument.js                    # Client-side CRDT mirror (JS)
│   │   │
│   │   ├── websocket/
│   │   │   └── stompClient.js                     # SockJS + STOMP connection setup
│   │   │
│   │   ├── api/
│   │   │   └── documentApi.js                     # REST calls to Spring Boot
│   │   │
│   │   └── App.jsx
│   │
│   └── package.json
│
└── docker-compose.yml
```

---

## Database Schema

```sql
-- Users
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username    VARCHAR(50) UNIQUE NOT NULL,
    email       VARCHAR(255) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,              -- bcrypt hashed
    color       VARCHAR(7) NOT NULL DEFAULT '#3B82F6', -- cursor color hex
    created_at  TIMESTAMP DEFAULT now()
);

-- Documents
CREATE TABLE documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(255) NOT NULL DEFAULT 'Untitled',
    owner_id    UUID REFERENCES users(id),
    created_at  TIMESTAMP DEFAULT now(),
    updated_at  TIMESTAMP DEFAULT now()
);

-- Operation Log (the core of event sourcing)
-- Every character insertion and deletion is stored here permanently
CREATE TABLE operations (
    id             BIGSERIAL PRIMARY KEY,
    document_id    UUID NOT NULL REFERENCES documents(id),
    op_type        VARCHAR(10) NOT NULL CHECK (op_type IN ('INSERT', 'DELETE')),
    node_id        VARCHAR(100) NOT NULL,            -- CRDT node unique ID
    left_id        VARCHAR(100),                     -- left neighbor node ID (INSERT only)
    char_value     CHAR(1),                          -- character (INSERT only)
    client_id      VARCHAR(100) NOT NULL,            -- which client generated this op
    lamport_clock  BIGINT NOT NULL,                  -- logical timestamp for ordering
    created_at     TIMESTAMP DEFAULT now(),
    INDEX idx_ops_document (document_id, lamport_clock)
);

-- Snapshots (periodic full document state for fast loading)
CREATE TABLE snapshots (
    id               BIGSERIAL PRIMARY KEY,
    document_id      UUID NOT NULL REFERENCES documents(id),
    content          TEXT NOT NULL,                  -- serialized CRDT state (JSON)
    last_op_id       BIGINT NOT NULL,                -- ops after this ID still need replay
    created_at       TIMESTAMP DEFAULT now()
);
```

---

## Redis Design

Redis is used for exactly two jobs in this project:

### 1. Cursor Presence — Redis Hash

```
Key:    presence:{documentId}
Type:   Hash
Fields: { userId → JSON({ name, color, cursor: { line, ch } }) }
TTL:    30 seconds (refreshed every 10s by active clients)

Example:
HSET presence:doc-123 user-abc '{"name":"Alice","color":"#3B82F6","cursor":{"line":5,"ch":12}}'
HSET presence:doc-123 user-xyz '{"name":"Bob","color":"#EF4444","cursor":{"line":2,"ch":7}}'
HGETALL presence:doc-123   → returns all cursors for the document
```

### 2. Pub/Sub for Horizontal Scaling

```
Channel:  doc-ops:{documentId}
Purpose:  When multiple Spring Boot instances are running, an op received
          by Instance 1 must reach clients connected to Instance 2.
          Instance 1 PUBLISHES to this channel.
          All instances SUBSCRIBE and broadcast to their local clients.
```

---

## API Reference

### REST Endpoints

| Method | URL | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Register a new user |
| `POST` | `/api/auth/login` | Login and get JWT token |
| `GET` | `/api/documents` | List all documents for current user |
| `POST` | `/api/documents` | Create a new document |
| `GET` | `/api/documents/{id}` | Get document metadata + initial state |
| `DELETE` | `/api/documents/{id}` | Delete a document |
| `GET` | `/api/documents/{id}/history` | Get full operation log for a document |

### Document Load Response

When a client opens a document, it receives:

```json
{
  "documentId": "uuid",
  "title": "My Document",
  "snapshot": {
    "content": "[serialized CRDT state]",
    "lastOpId": 4821
  },
  "pendingOps": [
    {
      "type": "INSERT",
      "nodeId": "userB:4822",
      "leftId": "userA:100",
      "charValue": "X",
      "clientId": "userB",
      "lamportClock": 4822
    }
  ]
}
```

The client loads the snapshot first (fast), then applies only the pending ops (ops that happened after the snapshot).

---

## WebSocket Events

### Client → Server

```
Destination:  /app/document/{docId}/op
Payload:
{
  "type": "INSERT" | "DELETE",
  "nodeId": "clientId:lamportClock",
  "leftId": "clientId:lamportClock",   // INSERT only
  "charValue": "a",                    // INSERT only
  "clientId": "user-uuid",
  "lamportClock": 42
}

Destination:  /app/document/{docId}/cursor
Payload:
{
  "userId": "user-uuid",
  "name": "Alice",
  "color": "#3B82F6",
  "line": 5,
  "ch": 12
}
```

### Server → Client (Broadcast)

```
Topic:   /topic/document/{docId}
Payload: Same OperationDTO as above (echoed to all subscribers)

Topic:   /topic/document/{docId}/cursors
Payload: Array of all active cursors for the document
```

---

## Getting Started

### Prerequisites

- Java 21+
- Node.js 18+
- Docker + Docker Compose
- Maven 3.x

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/collab-editor.git
cd collab-editor
```

### 2. Start Infrastructure (PostgreSQL + Redis)

```bash
docker-compose up -d postgres redis
```

### 3. Run the Backend

```bash
cd backend
mvn spring-boot:run
```

Backend starts on `http://localhost:8080`

### 4. Run the Frontend

```bash
cd frontend
npm install
npm start
```

Frontend starts on `http://localhost:3000`

### 5. Open Two Browser Windows

Navigate both to `http://localhost:3000`, open the same document, and start typing. Changes in one window appear instantly in the other.

---

## Environment Variables

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/collabdb
    username: admin
    password: secret
  data:
    redis:
      host: localhost
      port: 6379
  websocket:
    allowed-origins: "http://localhost:3000"

app:
  jwt:
    secret: your-secret-key-here
    expiration-ms: 86400000   # 24 hours
  snapshot:
    interval-ops: 500          # take snapshot every 500 ops
```

---

## Running Tests

```bash
# Unit tests (CRDT convergence, service logic)
mvn test

# Integration tests (WebSocket + DB)
mvn verify

# Load test with JMeter (50 concurrent users)
jmeter -n -t tests/collab-load-test.jmx -l results.jtl
```

The CRDT unit tests are the most important. They simulate scenarios like:

- Two clients inserting at the same position simultaneously
- Client A deleting a character that Client B is inserting next to
- Out-of-order operation delivery (network reordering)
- Client going offline, accumulating ops, then reconnecting

All tests must pass with all clients converging to identical document state.

---

## Performance & Benchmarks

Target benchmarks to achieve and report on your resume:

| Metric | Target |
|---|---|
| Op broadcast latency (same region) | < 50ms p99 |
| Concurrent active users per instance | 500+ |
| Operation persistence throughput | 10,000 ops/sec |
| Document load time (10,000 ops, with snapshot) | < 200ms |
| Cursor presence update latency (Redis) | < 10ms |

Run JMeter load tests and record actual numbers. Real measured numbers on a resume are far more impressive than estimates.

---

## Scaling Strategy

### Single Instance (Default)
- Spring's in-memory STOMP broker handles all WebSocket routing
- Works fine for up to ~500 concurrent users per instance

### Multi-Instance (Bonus Resume Points)
When you deploy multiple Spring Boot instances behind a load balancer:

```java
// Replace in-memory broker with RabbitMQ relay
config.enableStompBrokerRelay("/topic")
      .setRelayHost("rabbitmq")
      .setRelayPort(61613);
```

Combined with Redis Pub/Sub for presence, this allows horizontal scaling to thousands of concurrent users. This is a production-level architecture decision that interviewers will ask about.

---

## Resume Impact

The one-line resume summary this project earns you:

> *"Built a real-time collaborative document editor supporting 50+ concurrent users with CRDT-based conflict resolution in Java/Spring Boot, WebSocket/STOMP broadcasting, PostgreSQL event-sourced operation log, and Redis cursor presence with sub-10ms latency."*

**Interview topics this project directly prepares you for:**

- How do you handle concurrent writes in a distributed system?
- What is eventual consistency and how do you achieve it?
- How does WebSocket differ from HTTP and when do you use it?
- Walk me through your database schema and why you designed it this way.
- How would you scale this to 100,000 concurrent users?
- What is a Lamport clock and why is it useful?
- How do you test distributed system correctness?
- What tradeoffs did you make in your CRDT implementation?
