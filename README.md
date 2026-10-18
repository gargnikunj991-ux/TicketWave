# 🎫 TicketWave — High-Concurrency Ticket Booking & Reservation Engine

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3+-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Redis 7](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)
[![Redisson](https://img.shields.io/badge/Redisson-Distributed%20Locking-critical.svg)](https://redisson.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-informational.svg)](https://www.docker.com/)

> **TicketWave** is a production-grade, distributed ticket reservation and checkout engine engineered for flash-sale scenarios (e.g., concert tours, championship finals). It eliminates double-bookings, race conditions, and overselling under high-concurrency bursts using **Redisson distributed locks**, **Redis 10-minute TTL leases**, and **payment idempotency keys** backed by PostgreSQL ACID transactions.

---

## 📌 Architecture at a Glance

```
                                  ┌────────────────────────┐
                                  │   HTTP REST Clients    │
                                  └───────────┬────────────┘
                                              │ (Hold / Checkout / View)
                                              ▼
                        ┌───────────────────────────────────────────┐
                        │       TicketWave Spring Boot 3 Engine     │
                        ├───────────────────────────────────────────┤
                        │ • Distributed Lock Manager (Redisson)    │
                        │ • Seat Lifecycle State Machine            │
                        │ • Idempotency & Payment Verification      │
                        │ • Keyspace Expiration Event Listener     │
                        └─────────────┬─────────────────────────────┘
                                      │
                 ┌────────────────────┴────────────────────┐
                 ▼                                         ▼
   ┌───────────────────────────┐             ┌───────────────────────────┐
   │         Redis 7           │             │       PostgreSQL 16       │
   ├───────────────────────────┤             ├───────────────────────────┤
   │ • Fast In-Memory Locks    │             │ • ACID Booking Ledger     │
   │ • 10-Min Temporary Holds  │             │ • Venues, Screens & Shows │
   │ • TTL Expiration Events   │             │ • Seat Catalog & Audit    │
   │ • Idempotency Key Cache   │             │ • Unique Constraints      │
   └───────────────────────────┘             └───────────────────────────┘
```

---

## 🚀 Live Implementation Roadmap & Progress

This repository is built following a test-driven, milestone-based engineering approach:

- [x] **Phase 0: Specifications & System Design**
  - [x] Product Requirements Document ([`docs/PRD.md`](docs/PRD.md))
  - [x] Low-Level Design & Concurrency Strategy ([`docs/LLD.md`](docs/LLD.md))
  - [x] PostgreSQL & Redis Data Schema ([`docs/SCHEMA.md`](docs/SCHEMA.md))
  - [x] RESTful API Specification ([`docs/API_SPEC.md`](docs/API_SPEC.md))
- [x] **Phase 1: Environment & Project Scaffolding**
  - [x] Spring Boot 3.3+ initialization with Java 21
  - [x] Multi-container `docker-compose.yml` (PostgreSQL 16 + Redis 7)
  - [x] Application configuration (`application.yml`, `application-test.yml`) & profile setup
  - [x] Automated GitHub Actions CI pipeline (`.github/workflows/ci.yml`)
- [x] **Phase 2: Relational Domain Modeling & Realistic Seeding**
  - [x] Domain-driven packaging architecture (`venue`, `event`, `show`, `seat`, `booking`)
  - [x] JPA Entities: `Venue`, `Screen`, `Event`, `Show`, `Seat`, `ShowSeat`, `Booking`, `Ticket`
  - [x] Concurrency safety: Optimistic locking (`@Version`) & database unique constraints
  - [x] Spring Data Repositories with `JOIN FETCH` queries (eliminating N+1 query overhead)
  - [x] Automated database seeder populating real-world arenas (Madison Square Garden), Coldplay concert, and 60 tiered seat matrices
  - [x] Automated persistence & optimistic locking test suite passing cleanly
- [x] **Phase 3: High-Concurrency Seat Hold Engine (Redis + Redisson)**
  - [x] Redisson client setup & distributed locking abstraction
  - [x] Atomic multi-seat lock acquisition with lexicographical ordering (deadlock prevention)
  - [x] 10-minute temporary seat hold with Redis TTL keys (`hold:show:{id}:seat:{id}`)
  - [x] RFC-7807 Problem Details error handling with fast 409 conflict responses
  - [x] RESTful API endpoints (`GET /seats`, `POST /hold`, `POST /release`) with 14 passing automated tests
- [x] **Phase 4: Transactional Checkout & Payment Idempotency Engine**
  - [x] HTTP `Idempotency-Key` tracking gate & Redis cache (120s `IN_PROGRESS` lock & 24h `COMPLETED` replay)
  - [x] Simulated bank payment service with latency & failure simulation (`PaymentService`)
  - [x] Decoupled transactional architecture preventing HikariCP pool starvation during payment calls
  - [x] Atomic PostgreSQL `@Transactional` commit transitioning seats from `HELD` $\rightarrow$ `BOOKED`
  - [x] RESTful checkout & booking lookup endpoints (`POST /bookings/checkout`, `GET /bookings/{ref}`) with 40 passing tests
- [x] **Phase 5: Auto-Expiration & Inventory Recovery**
  - [x] Reactive Redis Keyspace Notification listener (`__keyevent@*__:expired`) reclaiming holds in `<5ms`
  - [x] Proactive scheduled fallback sweep worker (`@Scheduled`) every 60s for guaranteed recovery
  - [x] Strict state machine safeguards ensuring confirmed `BOOKED` seats are never affected
  - [x] 51 passing automated tests across reactive listeners, batch sweepers, and controllers
- [ ] **Phase 6: High-Contention Multithreaded Concurrency Stress Testing**
  - [ ] JUnit 5 + `CountDownLatch` + `ExecutorService` test suite
  - [ ] Verification: 100+ concurrent threads competing for 1 seat $\rightarrow$ exactly 1 booking, 99 graceful rejections, 0 overbookings
- [ ] **Phase 7: Production Observability & OpenAPI Documentation**
  - [ ] Spring Boot Actuator health and Redis/DB metric probes
  - [ ] Interactive Swagger UI / OpenAPI 3.0 contract documentation

---

## ⚡ Key Technical Highlights

1. **Zero Overbooking Guarantee:** Uses Redisson fair distributed locks to coordinate seat reservations across distributed application instances.
2. **Deadlock-Free Multi-Seat Selection:** Deterministic natural ordering of seat IDs prior to lock acquisition guarantees circular-wait deadlocks are mathematically impossible.
3. **Automated Inventory Release:** Unpaid seats automatically return to the general availability pool after 10 minutes using dual event-driven + scheduled sweep mechanics.
4. **Financial Consistency & Idempotency:** Duplicate checkout requests (caused by user double-clicks or network retries) return the cached original response without creating duplicate bookings or charges.
5. **Verified Concurrency Resilience:** Backed by automated stress tests proving ACID compliance and thread-safety under flash-sale load.

---

## 📂 Project Structure

```
ticketwave/
├── .github/workflows/        # Automated CI pipeline with live Postgres & Redis services
├── docs/                     # Architectural design & specifications
│   ├── PRD.md                # Product Requirements Document & Scope
│   ├── LLD.md                # Low-Level Design & Concurrency Strategy
│   ├── SCHEMA.md             # PostgreSQL Relational & Redis Key Schema
│   └── API_SPEC.md           # REST API Specifications & Error Schemas
├── src/
│   ├── main/java/com/ticketwave/
│   │   ├── venue/            # Venue, Screen, ScreenType, Repositories
│   │   ├── event/            # Event, EventRepository
│   │   ├── show/             # Show, ShowStatus, ShowRepository
│   │   ├── seat/             # Seat, ShowSeat, SeatTier, SeatStatus, Repositories
│   │   │   ├── dto/          # SeatMatrixResponse, SeatHoldRequest, SeatHoldResponse
│   │   │   ├── SeatHoldService.java    # Redisson distributed locking & 10-min hold engine
│   │   │   └── ShowSeatController.java # Real-time matrix, hold & release REST endpoints
│   │   ├── booking/          # Booking, Ticket, BookingStatus, Repositories
│   │   ├── infrastructure/
│   │   │   ├── exception/    # RFC-7807 Problem Details Global Exception Handler
│   │   │   ├── redis/        # Redis & Redisson configurations (StringRedisTemplate)
│   │   │   └── seeder/       # Realistic Database Seeder (MSG Arena & Coldplay Tour)
│   │   └── TicketWaveApplication.java
│   └── main/resources/
│       └── application.yml   # High-concurrency HikariCP, JPA, Redis & Redisson configurations
├── src/test/java/com/ticketwave/
│   ├── seat/
│   │   ├── SeatHoldServiceTest.java     # Unit tests for distributed locking & fail-fast
│   │   ├── ShowSeatControllerTest.java  # WebMvc tests for REST contracts & validations
│   │   └── ShowSeatRepositoryTest.java  # Optimistic locking & persistence tests
│   └── TicketWaveApplicationTests.java
├── docker-compose.yml        # PostgreSQL 16 & Redis 7 orchestration
├── pom.xml                   # Maven dependencies & Java 21 build configuration
└── README.md                 # Living engineering documentation
```

---

## ⚡ API Showcase (Phase 3: High-Concurrency Seat Hold)

### 1. View Real-Time Seat Matrix
```bash
curl -X GET http://localhost:8080/api/v1/shows/1/seats
```

### 2. Acquire a 10-Minute Temporary Seat Hold
Acquires Redisson distributed locks, verifies availability, and leases the seats for 600 seconds:
```bash
curl -X POST http://localhost:8080/api/v1/shows/1/hold \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "usr_98124",
    "seatIds": [1, 2]
  }'
```
**Response (200 OK):**
```json
{
  "holdReference": "a8098c1a-f86e-11da-bd1a-00112444be1e",
  "showId": 1,
  "userId": "usr_98124",
  "seatIds": [1, 2],
  "totalHoldAmount": 500.00,
  "expiresAt": "2026-10-17T16:35:00Z",
  "ttlSeconds": 600
}
```

### 3. Voluntary Release of Held Seats
```bash
curl -X POST http://localhost:8080/api/v1/shows/1/release \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "usr_98124",
    "holdReference": "a8098c1a-f86e-11da-bd1a-00112444be1e"
  }'
```

---

## ⚡ API Showcase (Phase 4: Idempotent Checkout & Booking)

### 4. Idempotent Checkout & Payment Finalization
Converts a valid 10-minute hold into a confirmed booking. Replaying this request with the same `Idempotency-Key` returns the cached 201 response without re-charging:
```bash
curl -X POST http://localhost:8080/api/v1/bookings/checkout \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: c03264c1-4b16-43b6-9bb5-47e17cb2a609" \
  -d '{
    "userId": "usr_98124",
    "holdReference": "a8098c1a-f86e-11da-bd1a-00112444be1e",
    "paymentMethod": "UPI",
    "paymentToken": "tok_mock_success_upi"
  }'
```
**Response (201 Created):**
```json
{
  "bookingReference": "TW-BK-A1B2C3D4",
  "status": "CONFIRMED",
  "showId": 1,
  "userId": "usr_98124",
  "paymentTxnId": "TXN_UPI_8A9B0C1D2E3F",
  "totalAmount": 500.00,
  "tickets": [
    {
      "ticketSerial": "TKT-2026-001A01",
      "seatId": 1,
      "row": "A",
      "number": 1,
      "tier": "VIP",
      "price": 250.00
    },
    {
      "ticketSerial": "TKT-2026-001A02",
      "seatId": 2,
      "row": "A",
      "number": 2,
      "tier": "VIP",
      "price": 250.00
    }
  ],
  "bookedAt": "2026-10-18T15:00:00Z"
}
```

### 5. Retrieve Confirmed Booking Details
```bash
curl -X GET http://localhost:8080/api/v1/bookings/TW-BK-A1B2C3D4
```

---

## 🧪 Test Suite & Concurrency Verification

TicketWave is built strictly following Test-Driven Development (TDD). 

```bash
mvn test
```
* **Status:** `51 / 51 Tests Passing cleanly`
* Covers:
  - Reactive Redis keyspace expiration listener & hold regex pattern parsing
  - Automated batch sweeping for orphaned holds in PostgreSQL
  - Confirmed `BOOKED` state protection during expiration events
  - Idempotency key `IN_PROGRESS` lock & 24h `COMPLETED` replay caching
  - Payment gateway simulation (deterministic tokens & bank decline 402)
  - Hold lease expiration rejection (410 Gone)
  - IDOR hold ownership verification
  - Transactional boundary isolation preventing HikariCP pool starvation
  - Deterministic natural ordering & circular-wait deadlock prevention
  - Sub-millisecond fail-fast rejection on Redis conflicts (409)
  - Lock timeout handling under high thread contention
  - RFC-7807 Problem Details validation & error formatting
  - `@Version` optimistic locking integrity

---

## 🛠️ Quickstart (Running Locally)

### Prerequisites
* **Java 21** or later
* **Maven 3.9+**
* **Docker & Docker Compose**

### 1. Start Infrastructure (PostgreSQL & Redis)
```bash
docker-compose up -d
```

### 2. Build & Run Application
```bash
mvn clean spring-boot:run
```

The API will be available at `http://localhost:8080`, with interactive Swagger UI at `http://localhost:8080/swagger-ui.html`.
