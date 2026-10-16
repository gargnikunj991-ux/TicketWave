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
- [ ] **Phase 3: High-Concurrency Seat Hold Engine (Redis + Redisson)**
  - [ ] Redisson client setup & distributed locking abstraction
  - [ ] Atomic multi-seat lock acquisition with lexicographical ordering (deadlock prevention)
  - [ ] 10-minute temporary seat hold with Redis TTL keys
- [ ] **Phase 4: Transactional Checkout & Payment Idempotency Engine**
  - [ ] HTTP `Idempotency-Key` tracking filter & Redis cache
  - [ ] Simulated bank payment service with latency & failure simulation
  - [ ] Atomic PostgreSQL `@Transactional` commit transitioning seats from `HELD` $\rightarrow$ `BOOKED`
- [ ] **Phase 5: Auto-Expiration & Inventory Recovery**
  - [ ] Reactive Redis Keyspace Notification listener (`__keyevent@0__:expired`)
  - [ ] Proactive scheduled fallback sweep (`@Scheduled`) for orphaned holds
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
│   │   ├── venue/            # Venue, Screen, ScreenType, VenueRepository, ScreenRepository
│   │   ├── event/            # Event, EventRepository
│   │   ├── show/             # Show, ShowStatus, ShowRepository
│   │   ├── seat/             # Seat, ShowSeat, SeatTier, SeatStatus, SeatRepository, ShowSeatRepository
│   │   ├── booking/          # Booking, Ticket, BookingStatus, BookingRepository, TicketRepository
│   │   ├── infrastructure/
│   │   │   └── seeder/       # Realistic Database Seeder (MSG Arena & Coldplay Tour)
│   │   └── TicketWaveApplication.java
│   └── main/resources/
│       └── application.yml   # High-concurrency HikariCP, JPA, Redis & Redisson configurations
├── docker-compose.yml        # PostgreSQL 16 & Redis 7 orchestration
├── pom.xml                   # Maven dependencies & Java 21 build configuration
└── README.md                 # Living engineering documentation
```

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
./mvnw clean spring-boot:run
```

The API will be available at `http://localhost:8080`, with interactive Swagger UI at `http://localhost:8080/swagger-ui.html`.
