# Product Requirements Document (PRD) — TicketWave

## 1. Executive Summary
**TicketWave** is a backend ticket reservation engine built to manage high-demand ticket sales (concerts, movies, sporting events) where thousands of users compete for limited seating inventory simultaneously. The system prioritizes data consistency, zero-overbooking guarantees, sub-100ms lock acquisition, and seamless inventory recycling when reservations expire.

---

## 2. Problem Statement & Motivation
In standard ticket booking architectures, seat inventory checks and reservations are executed directly against relational databases:
```sql
SELECT * FROM seats WHERE id = ? FOR UPDATE;
```
Under flash-sale conditions (e.g. 10,000 requests/second):
1. **Database Lock Saturation:** Relational row-level locks hold database connection pools open, leading to thread starvation and cascading 504 Gateway Timeouts.
2. **Double-Booking Vulnerabilities:** Without strict atomic locking across distributed nodes, concurrent transactions read outdated availability and assign the same seat to multiple buyers.
3. **Ghost Inventory (Abandoned Carts):** Users lock seats and abandon the checkout screen. Without an automated, reliable expiration mechanism, inventory remains trapped indefinitely.
4. **Duplicate Charges:** Network drops lead users to retry payment buttons, resulting in duplicate financial transactions and duplicate ticket generation.

**TicketWave** resolves these challenges using an in-memory distributed locking layer (Redis + Redisson), time-to-live (TTL) reservation leases, and transactional idempotency.

---

## 3. User Personas & Core Workflows

### Primary Persona: Event Attendee / Ticket Buyer
* **Browse Shows:** Views upcoming events, venues, showtimes, and real-time seat availability matrices.
* **Hold Seats:** Selects one or multiple seats (e.g., A1, A2) and receives an exclusive 10-minute reservation lease.
* **Confirm & Pay:** Submits payment details with a client-generated idempotency key to finalize the booking.
* **Release/Cancel:** Can voluntarily cancel an active hold or an existing booking before the show start time.

### System Persona: Inventory Reclamation Engine
* Automatically monitors seat hold expirations.
* Restores expired seats to the public availability pool without manual database intervention.

---

## 4. Functional Requirements

### FR-1: Show Catalog & Real-Time Seat Matrix
* **FR-1.1:** System shall provide show details including venue, screen, start time, end time, and event description.
* **FR-1.2:** System shall return the complete seat layout for a specific show, indicating each seat's tier (VIP, Premium, Standard), price, and current real-time status (`AVAILABLE`, `HELD`, `BOOKED`).

### FR-2: Distributed Temporary Seat Hold (10-Minute Lease)
* **FR-2.1:** System shall permit a user to request a hold on 1 to 10 seats for a specific show.
* **FR-2.2:** System shall acquire distributed locks on all requested seats in atomic fashion.
* **FR-2.3:** If any seat is already `HELD` or `BOOKED`, the entire hold request must fail atomically (all-or-nothing).
* **FR-2.4:** Upon successful hold, the system assigns a `holdReference` (UUID) with an exact 600-second (10-minute) TTL.

### FR-3: Idempotent Payment & Booking Finalization
* **FR-3.1:** System shall require a unique `Idempotency-Key` header for every checkout/booking request.
* **FR-3.2:** System shall verify that the provided `holdReference` belongs to the requesting user and has not expired.
* **FR-3.3:** System shall simulate bank processing with configurable latency and deterministic success/failure codes.
* **FR-3.4:** Upon successful payment, system shall atomically commit the booking record into PostgreSQL, change seat status to `BOOKED`, generate unique ticket serials, and remove Redis hold keys.
* **FR-3.5:** If a request with an identical `Idempotency-Key` is received within 24 hours, the system shall return the cached initial response without re-executing payment or creating duplicate bookings.

### FR-4: Automated Hold Expiration & Recovery
* **FR-4.1:** System shall listen for Redis TTL expiration events (`__keyevent@0__:expired`).
* **FR-4.2:** Upon receiving an expiration event, system shall transition the seat back to `AVAILABLE` and publish an audit event.
* **FR-4.3:** A scheduled background job (`@Scheduled`) shall run every 60 seconds as a safety reconciliation worker to catch any orphaned holds caused by Redis connection blips.

---

## 5. Non-Functional Requirements (NFR)

* **Consistency (Strict Zero Overbooking):** Under no circumstance shall two different users receive confirmed bookings for the exact same seat in a show.
* **Latency:** Seat hold lock acquisition shall execute in $< 50\text{ ms}$ under normal conditions and $< 150\text{ ms}$ under 95th percentile load.
* **Deadlock Freedom:** Multi-seat lock acquisition must be guaranteed deadlock-free through deterministic resource sorting.
* **Resilience:** If the database or payment service fails during checkout, distributed locks must be safely released or allowed to expire via TTL without corrupting the state.

---

## 6. Project Scope Boundaries

### In Scope (Core Backend Engineering)
* REST API endpoints for catalog, seat matrix, hold, checkout, and cancellation.
* Redis 7 + Redisson distributed locks with lease times.
* Redis TTL keys with keyspace event notifications.
* PostgreSQL 16 database with relational schema, foreign keys, and unique indexes.
* Payment idempotency validation layer.
* Multithreaded concurrency stress tests validating zero overbooking under 100+ parallel threads.
* Docker Compose for running PostgreSQL and Redis.

### Explicitly Out of Scope (Avoiding Bloat)
* Frontend user interface (HTML/CSS/React/Next.js) — all testing conducted via Swagger UI / Postman / Automated JUnit 5.
* Live payment gateway onboarding (Razorpay / Stripe) — simulated via mock payment adapter.
* Email / SMS external delivery gateways — simulated via structured SLF4J logs.
