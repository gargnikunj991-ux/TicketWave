# Database & In-Memory Data Schema — TicketWave

## 1. PostgreSQL Relational Entity-Relationship Model (ERD)

```
 ┌──────────────┐          ┌──────────────┐          ┌──────────────┐
 │    venues    │ 1      N │   screens    │ 1      N │    seats     │
 ├──────────────┼──────────┼──────────────┼──────────┼──────────────┤
 │ id (PK)      │          │ id (PK)      │          │ id (PK)      │
 │ name         │          │ venue_id(FK) │          │ screen_id(FK)│
 │ city         │          │ name         │          │ row_label    │
 │ address      │          │ screen_type  │          │ seat_number  │
 └──────────────┘          └──────┬───────┘          │ tier         │
                                  │                  └──────┬───────┘
                                  │ 1                       │ 1
 ┌──────────────┐                 │                         │
 │    events    │ 1               │                         │
 ├──────────────┼─────┐           │                         │
 │ id (PK)      │     │           │                         │
 │ title        │     │ N         ▼ N                       ▼ N
 │ duration_min │   ┌─┴───────────────┐ 1      N ┌─────────────────────┐
 │ genre        │   │      shows      ├──────────┤     show_seats      │
 └──────────────┘   ├─────────────────┤          ├─────────────────────┤
                    │ id (PK)         │          │ id (PK)             │
                    │ event_id (FK)   │          │ show_id (FK)        │
                    │ screen_id (FK)  │          │ seat_id (FK)        │
                    │ start_time      │          │ status              │
                    │ end_time        │          │ price               │
                    └────────┬────────┘          │ hold_reference      │
                             │ 1                 │ hold_expires_at     │
                             │                   └──────────┬──────────┘
                             │ N                            │
                    ┌────────┴────────┐ 1                 N │
                    │    bookings     ├─────────────────────┤
                    ├─────────────────┤                     │
                    │ id (PK)         │ 1                   │
                    │ booking_ref (UQ)│                     │
                    │ show_id (FK)    │ N                   │
                    │ user_id         │ ┌───────────────────┴──┐
                    │ total_amount    │ │       tickets        │
                    │ status          │ ├──────────────────────┤
                    │ idempotency_key │ │ id (PK)              │
                    │ payment_txn_id  │ │ booking_id (FK)      │
                    │ created_at      │ │ seat_id (FK)         │
                    └─────────────────┘ │ ticket_serial (UQ)   │
                                        │ price                │
                                        └──────────────────────┘
```

---

## 2. Table DDL & Column Specifications

### 2.1 Venues (`venues`)
```sql
CREATE TABLE venues (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    city VARCHAR(100) NOT NULL,
    address TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

### 2.2 Screens (`screens`)
```sql
CREATE TABLE screens (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    screen_type VARCHAR(30) NOT NULL, -- 'IMAX', 'STANDARD', 'DOLBY_ATMOS'
    total_capacity INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

### 2.3 Events (`events`)
```sql
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    duration_minutes INT NOT NULL,
    genre VARCHAR(50) NOT NULL,
    rating VARCHAR(10) NOT NULL, -- 'U', 'UA', 'A'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

### 2.4 Shows (`shows`)
```sql
CREATE TABLE shows (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    screen_id BIGINT NOT NULL REFERENCES screens(id) ON DELETE RESTRICT,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'CANCELLED', 'COMPLETED'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

### 2.5 Seats (`seats`)
Physical seat blueprint on a screen:
```sql
CREATE TABLE seats (
    id BIGSERIAL PRIMARY KEY,
    screen_id BIGINT NOT NULL REFERENCES screens(id) ON DELETE CASCADE,
    row_label VARCHAR(5) NOT NULL, -- 'A', 'B', 'C'
    seat_number INT NOT NULL,      -- 1, 2, 3
    tier VARCHAR(20) NOT NULL,     -- 'VIP', 'PREMIUM', 'STANDARD'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_screen_seat UNIQUE (screen_id, row_label, seat_number)
);
```

### 2.6 Show Seats (`show_seats`)
Specific state of a seat for a given show:
```sql
CREATE TABLE show_seats (
    id BIGSERIAL PRIMARY KEY,
    show_id BIGINT NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_id BIGINT NOT NULL REFERENCES seats(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', -- 'AVAILABLE', 'HELD', 'BOOKED'
    price NUMERIC(10, 2) NOT NULL,
    hold_reference UUID,
    hold_expires_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_show_seat UNIQUE (show_id, seat_id)
);

-- Fast lookup indexes
CREATE INDEX idx_show_seats_matrix ON show_seats (show_id, status);
CREATE INDEX idx_show_seats_expiry ON show_seats (status, hold_expires_at) 
    WHERE status = 'HELD';
```

### 2.7 Bookings (`bookings`)
```sql
CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    booking_reference VARCHAR(36) NOT NULL UNIQUE, -- e.g. 'TW-BK-789123'
    show_id BIGINT NOT NULL REFERENCES shows(id) ON DELETE RESTRICT,
    user_id VARCHAR(100) NOT NULL,
    total_amount NUMERIC(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED', -- 'CONFIRMED', 'CANCELLED'
    payment_txn_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX idx_bookings_user ON bookings (user_id);
```

### 2.8 Tickets (`tickets`)
```sql
CREATE TABLE tickets (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    seat_id BIGINT NOT NULL REFERENCES seats(id) ON DELETE RESTRICT,
    ticket_serial VARCHAR(50) NOT NULL UNIQUE, -- e.g. 'TKT-2026-98124'
    price NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

---

## 3. Redis In-Memory Data Structures

| Key Pattern | Data Type | Serialized Format | TTL | Description |
| :--- | :--- | :--- | :--- | :--- |
| `lock:show:{sId}:seat:{seatId}` | String | Lock ownership token | 5 sec | Redisson distributed lock key used during hold acquisition. |
| `hold:show:{sId}:seat:{seatId}` | String (JSON) | `{"userId":"usr_1","holdRef":"uuid","expiresAt":17600000}` | 600 sec | Fast in-memory check to immediately reject hold attempts for already-reserved seats. |
| `user_hold:{userId}:{holdRef}` | Set of Strings | `["101", "102"]` (seat IDs) | 600 sec | Maps a user's active checkout session to the seats they currently hold. |
| `idempotency:{key}` | Hash | `status`, `response_code`, `response_body` | 86,400 sec | Idempotency registry preventing duplicate charges/orders for 24 hours. |
