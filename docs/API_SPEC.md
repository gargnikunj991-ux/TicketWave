# RESTful API Specification — TicketWave

## 1. Global Conventions

* **Base URL:** `http://localhost:8080/api/v1`
* **Content-Type:** `application/json`
* **Datetime Format:** ISO-8601 UTC (`2026-10-15T10:00:00Z`)
* **Standard Error Format:** RFC-7807 Problem Details

```json
{
  "type": "about:blank",
  "title": "Seat Hold Conflict",
  "status": 409,
  "detail": "Seat A12 is currently held by another user",
  "instance": "/api/v1/shows/1/hold",
  "timestamp": "2026-10-15T14:40:00Z"
}
```

---

## 2. API Endpoints

### 2.1 Get Real-Time Seat Matrix
Retrieves the seat map and real-time availability for a given show.

* **Endpoint:** `GET /shows/{showId}/seats`
* **Response:** `200 OK`
```json
{
  "showId": 1,
  "eventName": "Coldplay: Music of the Spheres Tour",
  "venueName": "DY Patil Stadium, Mumbai",
  "screenName": "Main Arena",
  "startTime": "2026-11-15T19:30:00Z",
  "seats": [
    {
      "seatId": 101,
      "row": "A",
      "number": 1,
      "tier": "VIP",
      "price": 7500.00,
      "status": "AVAILABLE"
    },
    {
      "seatId": 102,
      "row": "A",
      "number": 2,
      "tier": "VIP",
      "price": 7500.00,
      "status": "HELD"
    },
    {
      "seatId": 103,
      "row": "A",
      "number": 3,
      "tier": "VIP",
      "price": 7500.00,
      "status": "BOOKED"
    }
  ]
}
```

---

### 2.2 Acquire Temporary Seat Hold (10-Minute Lease)
Locks selected seats and creates a temporary hold with a 600-second TTL in Redis.

* **Endpoint:** `POST /shows/{showId}/hold`
* **Request Body:**
```json
{
  "userId": "usr_98124",
  "seatIds": [101, 102]
}
```
* **Success Response:** `200 OK`
```json
{
  "holdReference": "a8098c1a-f86e-11da-bd1a-00112444be1e",
  "showId": 1,
  "userId": "usr_98124",
  "seatIds": [101, 102],
  "totalHoldAmount": 15000.00,
  "expiresAt": "2026-10-15T14:50:00Z",
  "ttlSeconds": 600
}
```
* **Error Responses:**
  * `409 Conflict`: One or more seats are already `HELD` or `BOOKED`.
  * `400 Bad Request`: Empty seat list or requested more than 10 seats.

---

### 2.3 Release Temporary Seat Hold (Voluntary Cancellation)
Allows a user to release their held seats prior to expiration.

* **Endpoint:** `POST /shows/{showId}/release`
* **Request Body:**
```json
{
  "userId": "usr_98124",
  "holdReference": "a8098c1a-f86e-11da-bd1a-00112444be1e"
}
```
* **Success Response:** `200 OK`
```json
{
  "status": "SUCCESS",
  "message": "Hold released successfully. Seats are now available."
}
```

---

### 2.4 Checkout & Confirm Booking (Idempotent)
Executes payment verification and converts a valid seat hold into a permanently booked ticket.

* **Endpoint:** `POST /bookings/checkout`
* **Required Headers:**
  * `Idempotency-Key: <UUID>` (e.g. `c03264c1-4b16-43b6-9bb5-47e17cb2a609`)
* **Request Body:**
```json
{
  "userId": "usr_98124",
  "holdReference": "a8098c1a-f86e-11da-bd1a-00112444be1e",
  "paymentMethod": "UPI",
  "paymentToken": "tok_mock_success_upi"
}
```
* **Success Response:** `201 Created`
```json
{
  "bookingReference": "TW-BK-789123",
  "status": "CONFIRMED",
  "showId": 1,
  "userId": "usr_98124",
  "paymentTxnId": "TXN_UPI_982341908",
  "totalAmount": 15000.00,
  "tickets": [
    {
      "ticketSerial": "TKT-2026-001A01",
      "seatId": 101,
      "row": "A",
      "number": 1,
      "tier": "VIP",
      "price": 7500.00
    },
    {
      "ticketSerial": "TKT-2026-001A02",
      "seatId": 102,
      "row": "A",
      "number": 2,
      "tier": "VIP",
      "price": 7500.00
    }
  ],
  "bookedAt": "2026-10-15T14:45:12Z"
}
```
* **Error Responses:**
  * `410 Gone`: Seat hold has expired. Seats returned to pool.
  * `409 Conflict`: Idempotent request currently in progress.
  * `402 Payment Required`: Simulated payment declined.

---

### 2.5 Get Booking Details
Retrieves confirmed booking information.

* **Endpoint:** `GET /bookings/{bookingReference}`
* **Response:** `200 OK` (returns booking object with tickets).
* **Error Response:** `404 Not Found` if reference does not exist.

---

### 2.6 System Health Probe
* **Endpoint:** `GET /actuator/health`
* **Response:** `200 OK`
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP", "details": { "version": "7.2.4" } },
    "diskSpace": { "status": "UP" }
  }
}
```
