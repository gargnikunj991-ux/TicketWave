# Low-Level Design (LLD) & Concurrency Architecture — TicketWave

## 1. System Architecture Overview

TicketWave decouples **fast in-memory locking and hold leases** from **durable transactional persistence**:

```
[ HTTP Client ]
      │
      ▼
[ ReservationController ]
      │
      ▼
[ SeatHoldService ] ──────► [ Redisson Lock Manager ] ──► (Acquire Distributed Locks)
      │                                                               │
      ▼                                                               ▼
[ Redis Hold Manager ] ◄─────────────────────────────────────────────┘
  • Sets `hold:show:{showId}:seat:{seatId}` (TTL: 600s)
  • Releases distributed locks
      │
      ▼ (Client proceeds to checkout)
[ BookingCheckoutService ]
  • Validates Idempotency-Key in Redis
  • Executes Mock Payment
  • PostgreSQL @Transactional Boundary:
      - Inserts `bookings` row
      - Inserts `tickets` rows
      - Updates `show_seats` to BOOKED
  • Removes Redis hold keys
```

---

## 2. Seat Lifecycle State Machine

```
                  ┌───────────────┐
                  │   AVAILABLE   │◄─────────────────────────────┐
                  └───────┬───────┘                              │
                          │                                      │
               (Acquire Redisson Lock)                           │
               (Set Redis Hold with TTL)               (TTL Expiration Event OR
                          │                             Manual Hold Cancellation)
                          ▼                                      │
                  ┌───────────────┐                              │
                  │     HELD      │──────────────────────────────┘
                  └───────┬───────┘
                          │
               (Validate Idempotency)
               (Mock Payment Success)
               (PostgreSQL ACID Commit)
                          │
                          ▼
                  ┌───────────────┐
                  │    BOOKED     │
                  └───────────────┘
```

### State Definitions & Invariants:
* **`AVAILABLE`:** Seat is free to be held or viewed. No Redis hold key exists. In PostgreSQL, `show_seats.status = 'AVAILABLE'`.
* **`HELD`:** Seat is exclusively leased to a user for up to 600 seconds (10 minutes). Redis holds the key `hold:show:{showId}:seat:{seatId}`. PostgreSQL reflects `status = 'HELD'` with `hold_expires_at` timestamp.
* **`BOOKED`:** Final permanent state. Confirmed via payment. Row exists in `bookings` and `tickets`. Redis hold keys are wiped. PostgreSQL `status = 'BOOKED'`.

---

## 3. Distributed Locking & Deadlock Prevention

### 3.1 Why Redisson Over Plain Database Locks?
* PostgreSQL row locks (`SELECT ... FOR UPDATE`) consume active connections from the HikariCP pool. Under 10,000 requests/sec, this starves read traffic.
* Redisson implements distributed locks using Redis single-threaded atomic Lua scripts, acquiring and releasing locks in sub-milliseconds without touching database connections.

### 3.2 Deadlock Prevention for Multi-Seat Bookings
**The Problem (Circular Wait):**
* User A attempts to book seats `[Seat 5, Seat 12]`.
* User B attempts to book seats `[Seat 12, Seat 5]`.
* If locks are acquired in request order, User A locks `Seat 5` while User B locks `Seat 12`. Both wait indefinitely for the other, causing a distributed deadlock.

**The Solution (Deterministic Natural Ordering):**
All seat IDs in a hold request are sorted in ascending natural order before acquiring locks:
```java
List<Long> sortedSeatIds = request.seatIds().stream().sorted().toList();
List<RLock> locks = sortedSeatIds.stream()
    .map(seatId -> redissonClient.getLock("lock:show:" + showId + ":seat:" + seatId))
    .toList();
```
Because every thread requests locks in the exact same sequence (`Seat 5` before `Seat 12`), **circular wait is mathematically impossible**.

### 3.3 Lock Acquisition Parameters:
* **Wait Time:** `2,000 ms` (Thread waits up to 2 seconds to acquire the lock before throwing a `409 Conflict`).
* **Lease Time:** `5,000 ms` (Automatic safeguard against lock leaks if an instance crashes midway during hold execution).

---

## 4. Redis Key-Space & Lease Design

| Key Pattern | Type | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `lock:show:{showId}:seat:{seatId}` | String (Redisson Lock) | 5s | Protects critical section while creating the hold |
| `hold:show:{showId}:seat:{seatId}` | String / JSON | 600s (10 min) | Holds reservation lease; stores `userId` and `holdReference` |
| `user_hold:{userId}:{holdReference}` | Set of `seatId`s | 600s (10 min) | Fast lookup of all seats tied to a user's active checkout session |
| `idempotency:{key}` | Hash | 86,400s (24 hr) | Prevents duplicate payment/checkout executions |

---

## 5. Automated Inventory Reclamation (Dual-Tier Strategy)

To guarantee that expired seats never get stuck in limbo:

### Tier 1: Reactive Redis Keyspace Notifications
1. Redis configuration: `notify-keyspace-events Ex` enabled.
2. Spring Boot subscribes to channel `__keyevent@*__:expired`.
3. When `hold:show:{showId}:seat:{seatId}` expires:
   * Event listener extracts `showId` and `seatId`.
   * Invokes an asynchronous reconciliation task to update PostgreSQL `show_seats` to `AVAILABLE`.

### Tier 2: Proactive Safety Sweep Worker (`@Scheduled`)
* In the event of a network partition or Redis reconnect blip where a pub/sub message is missed, a Spring scheduled cron job runs every 60 seconds:
```sql
UPDATE show_seats 
SET status = 'AVAILABLE', hold_reference = NULL, hold_expires_at = NULL 
WHERE status = 'HELD' AND hold_expires_at < NOW();
```

---

## 6. Payment Idempotency Engine

Network timeouts often cause web clients or payment gateways to retry checkout requests. Without idempotency, this results in double debits and duplicate bookings.

### Idempotency Flow:
1. Client generates a unique UUID `Idempotency-Key` sent in request headers:
   `Idempotency-Key: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d`
2. **Filter Check in Redis:**
   * Uses Redis `SETNX` on `idempotency:{key}` with status `IN_PROGRESS` and 120-second TTL.
   * If key already exists with `IN_PROGRESS`: Returns `409 Conflict ("Transaction currently in progress")`.
   * If key already exists with `COMPLETED`: Returns the cached HTTP status code and response payload from Redis without re-executing logic.
3. **Execution:**
   * Processes mock payment.
   * Commits PostgreSQL transaction within `@Transactional(isolation = Isolation.READ_COMMITTED)`.
   * Updates `idempotency:{key}` in Redis to `COMPLETED` with full response body, extending TTL to 24 hours.

---

## 7. Concurrency Verification Harness (`CountDownLatch`)

To scientifically prove thread safety:
```java
int totalThreads = 100;
ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
CountDownLatch readyLatch = new CountDownLatch(totalThreads);
CountDownLatch startLatch = new CountDownLatch(1);
CountDownLatch doneLatch = new CountDownLatch(totalThreads);

// All 100 threads fire at the exact same nanosecond when startLatch counts down
```
* **Success Criteria:**
  * Exactly 1 thread receives HTTP `200 OK` / `201 Created`.
  * Exactly 99 threads receive HTTP `409 Conflict`.
  * Zero database exceptions, zero double records in `bookings` or `tickets`.
