# Trading Platform

A full-stack paper-trading platform: users register, receive a funded wallet, browse
market data, place **BUY**/**SELL** orders that execute against server-side prices, and
track their portfolio, watchlists, wallet and transaction history. Administrators get
system-wide statistics and cross-user management endpoints.

Built as a **Spring Boot REST API + React SPA** with JWT authentication and
role-based access control (`ROLE_USER`, `ROLE_ADMIN`).

---

## Table of Contents

- [Key Features](#key-features)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Repository Layout](#repository-layout)
- [Quick Start](#quick-start)
- [Backend Documentation](#backend-documentation)
  - [Module Overview](#backend-module-overview)
  - [Configuration](#backend-configuration)
  - [Database](#backend-database)
  - [Security Model](#backend-security-model)
  - [REST API Reference](#rest-api-reference)
  - [Error Contract](#error-contract)
  - [Trading Engine](#trading-engine)
  - [Tests](#backend-tests)
  - [Build & Run](#backend-build--run)
  - [Docker](#backend-docker)
- [Frontend Documentation](#frontend-documentation)
  - [Module Overview](#frontend-module-overview)
  - [Configuration](#frontend-configuration)
  - [Routing](#frontend-routing)
  - [State & Data Flow](#frontend-state--data-flow)
  - [Design System](#frontend-design-system)
  - [Conventions](#frontend-conventions)
  - [Build & Run](#frontend-build--run)
  - [Docker](#frontend-docker)
- [Environment Variables](#environment-variables)
- [Troubleshooting](#troubleshooting)
- [Roadmap Notes](#roadmap-notes)

---

## Key Features

| Area | Capability |
|------|-----------|
| Authentication | Register / login with username **or** email, BCrypt hashing, stateless JWT, 10 req/min rate limit on `/api/auth/**` |
| Market data | Public read API (list, lookup by symbol, search); admin-only create/update/delete |
| Trading | Instant-fill BUY/SELL orders priced from server-side market data, configurable fee, balance and holdings validation |
| Portfolio | Per-symbol positions (quantity, average buy price, current value, P/L, P/L %) plus an aggregated summary |
| Wallet | Balance / available balance, admin deposits and withdrawals with row-level locking |
| Watchlists | Multiple named watchlists per user with unique symbols |
| History | Immutable transaction ledger written for every filled order |
| Admin console | User list and delete, global stats, cross-user orders/portfolios/watchlists, force-cancel |
| Frontend UX | MUI design system, light/dark mode, responsive shell, skeleton loading, toast notifications |

---

## Architecture

```
   ┌────────────────────────────┐          JSON / HTTPS          ┌────────────────────────────┐
   │  Browser · React 19 SPA    │ ─────────────────────────────▶ │  Spring Boot 4 REST API    │
   │  Vite · MUI 7 · Axios      │ ◀───────────────────────────── │  Spring Security + JWT     │
   └────────────────────────────┘      Authorization: Bearer     └──────────────┬─────────────┘
                                                                                 │ JPA / Hibernate
                                                                                 ▼
                                                                ┌────────────────────────────┐
                                                                │  PostgreSQL                │
                                                                │  10 tables · FKs · indexes │
                                                                └────────────────────────────┘
```

**Authenticated request flow**

1. The SPA calls `POST /api/auth/login` and stores the returned JWT in `localStorage`.
2. An Axios request interceptor attaches `Authorization: Bearer <token>` to every call.
3. `AuthRateLimitFilter` guards the auth endpoints; `JwtAuthFilter` validates the token,
   loads the `UserDetails` and populates the `SecurityContext`.
4. `SecurityConfig` applies URL rules (`permitAll` vs `authenticated`) and `@PreAuthorize`
   applies method-level role checks.
5. Controllers resolve the caller via `CurrentUserUtils` and delegate to services.
6. Services transact through Spring Data JPA repositories and return DTOs (never entities).
7. Failures are normalised by `GlobalExceptionHandler` into a single JSON error shape.
8. A 401 on any non-auth request clears the session and redirects the SPA to `/login`.

**Principles applied throughout**

- Stateless sessions (no server-side HTTP session, CSRF disabled accordingly).
- Environment-specific values and secrets come from environment variables only.
- Prices, fees and balances are computed server-side — never trusted from the client.
- Money is handled with `BigDecimal`; concurrent mutations use pessimistic row locks.
- Client and server share one error contract (`status`, `error`, `message`, `path`, `fieldErrors`).

---

## Technology Stack

### Backend

| Component | Technology / Version |
|-----------|----------------------|
| Framework | Spring Boot **4.1.0** (Web, Data JPA, Security, Validation, Actuator) |
| Language | Java **17** |
| Build | Maven (wrapper committed: `./mvnw`) |
| Database | PostgreSQL (runtime driver) |
| Test database | H2 (test scope) |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security 6 + JJWT **0.12.6** (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`) |
| API docs | Springdoc OpenAPI **2.8.6** (`springdoc-openapi-starter-webmvc-ui`) |
| Boilerplate | Lombok |
| Testing | JUnit 5, Mockito (`mockito-junit-jupiter`), AssertJ, `spring-boot-starter-test`, `spring-security-test` |

### Frontend

| Component | Technology / Version |
|-----------|----------------------|
| Framework | React **19** (`react`, `react-dom`) |
| Build tool | Vite **8** with `@vitejs/plugin-react` |
| Routing | React Router DOM **7** |
| UI library | MUI **7** (`@mui/material`, `@mui/icons-material`, `@mui/lab`, `@emotion/*`) |
| Charts | Recharts **2** |
| HTTP client | Axios **1** |
| Toasts | Notistack **3** |
| Lint | ESLint **10** with `eslint-plugin-react-hooks` and `eslint-plugin-react-refresh` |
| Fonts | Inter (UI) and JetBrains Mono (numerics), loaded in `index.html` |

---

## Repository Layout

```
v1/
├── backend/                                   # Spring Boot API
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd / .mvn/                # Maven wrapper
│   ├── Dockerfile                             # multi-stage build → JRE image
│   └── src/
│       ├── main/java/com/ftp/backend/
│       │   ├── BackendApplication.java        # @SpringBootApplication entry point
│       │   ├── config/
│       │   │   ├── CorsConfig.java            # CORS rules for /api/**
│       │   │   ├── OpenApiConfig.java         # Swagger/OpenAPI metadata
│       │   │   ├── RoleInitializer.java       # seeds ROLE_USER / ROLE_ADMIN on startup
│       │   │   └── SecurityConfig.java        # filter chain, encoder, auth provider
│       │   ├── controller/                    # 8 REST controllers
│       │   ├── dto/request/                   # inbound payloads + validation
│       │   ├── dto/response/                  # outbound view models
│       │   ├── entity/                        # JPA entities + enums
│       │   ├── exception/                     # ApiException hierarchy + handler
│       │   ├── repository/                    # Spring Data JPA repositories
│       │   ├── security/                      # JWT provider/filter/handlers, rate limit
│       │   └── service/ + service/impl/       # interfaces + implementations
│       ├── main/resources/
│       │   ├── application.properties
│       │   └── schema.sql                     # reference PostgreSQL DDL
│       └── test/java/com/ftp/backend/         # context + integration tests
│
├── frontend/                                  # React SPA
│   ├── package.json
│   ├── vite.config.js                         # port 3000 + /api proxy
│   ├── nginx.conf                             # production SPA + API proxy
│   ├── Dockerfile                             # multi-stage build → nginx
│   ├── index.html
│   └── src/
│       ├── main.jsx                           # React root
│       ├── App.jsx                            # providers + route table
│       ├── api/                               # axios instance + resource modules
│       ├── components/                        # app shell, guards, charts, ui/
│       ├── context/AuthContext.jsx            # session state
│       ├── pages/                             # one component per route
│       ├── theme/                             # MUI theme, tokens, mode context
│       └── utils/                             # formatting + error helpers
│
└── README.md
```

---

## Quick Start

### Prerequisites

| Tool | Version | Needed for |
|------|---------|-----------|
| JDK | **17+** | Backend |
| Maven | 3.8+ (or the committed `./mvnw`) | Backend |
| PostgreSQL | **12+** | Backend persistence |
| Node.js | **18+** (20+ recommended) | Frontend |
| npm | 9+ | Frontend |
| Docker | optional | Containerised deployment |

### 1. Create the database

```sql
CREATE DATABASE trading_platform;
```

### 2. Apply the schema

```bash
psql -h localhost -U postgres -d trading_platform \
  -f backend/src/main/resources/schema.sql
```

### 3. Start the backend (port 8080)

```bash
cd backend
export JWT_SECRET="$(openssl rand -base64 48)"   # required, base64, >= 32 bytes
export DB_USERNAME=postgres
export DB_PASSWORD="<your-db-password>"
./mvnw spring-boot:run
```

### 4. Start the frontend (port 3000)

```bash
cd frontend
npm install
npm run dev
```

### 5. Use the app

- Open <http://localhost:3000> and register an account (new users get a funded wallet).
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health check: <http://localhost:8080/actuator/health>

> **Market data first.** Trading validates the symbol against the `market_data`
> table, so seed a few symbols before placing orders — either insert rows directly
> or, as an admin, call `POST /api/market/data`. See [Troubleshooting](#troubleshooting).

---

## Backend Documentation

### Backend Module Overview

| Package | Responsibility |
|---------|----------------|
| `config` | Security filter chain and beans, CORS, OpenAPI metadata, role seeding |
| `security` | JWT creation/validation, request filter, 401/403 writers, rate limiter, current-user helper |
| `controller` | HTTP layer: routing, validation triggers, status codes, admin guards |
| `service` + `service.impl` | Business logic and transactions; interfaces keep controllers thin |
| `repository` | Spring Data JPA interfaces, including pessimistic-lock queries |
| `entity` | JPA mappings and enums (`ERole`, `OrderType`, `OrderStatus`, `TransactionType`) |
| `dto.request` / `dto.response` | API contracts with Jakarta Bean Validation annotations |
| `exception` | Typed `ApiException` subclasses and the global handler producing the error contract |

**Controllers**

| Controller | Base path | Purpose |
|------------|-----------|---------|
| `AuthController` | `/api/auth` | Registration and login (returns JWT) |
| `MarketDataController` | `/api/market` | Public symbol data; admin mutations |
| `TradingController` | `/api/trading` | Place/cancel/query orders; admin order views |
| `PortfolioController` | `/api/portfolio` | Holdings and aggregated summary; admin views |
| `WalletController` | `/api/wallet` | Balance; admin deposit/withdraw |
| `WatchlistController` | `/api/watchlist` | CRUD for watchlists and items; admin views |
| `TransactionController` | `/api/transactions` | Ledger history and lookup |
| `UserController` | `/api/users` | Profile endpoints; admin user listing/deletion and global stats |

**Services**

| Service | Key behaviour |
|---------|---------------|
| `AuthServiceImpl` | Uniqueness checks, BCrypt hashing, `ROLE_USER` assignment, wallet seeding, token issue |
| `TradingServiceImpl` | Server-side pricing, fee calculation, BUY wallet debit, position upsert, order + ledger writes |
| `PortfolioServiceImpl` | Position mapping and aggregation (value, cost, P/L, P/L %) |
| `WalletServiceImpl` | Balance retrieval; locked deposit/withdraw; lazy wallet creation |
| `MarketDataServiceImpl` | Lookup, case-insensitive search, admin create/update/delete |
| `WatchlistServiceImpl` | Watchlist ownership checks, item add/remove with uniqueness |
| `TransactionServiceImpl` | Per-user ledger queries with ownership enforcement |
| `UserServiceImpl` | `UserDetailsService` implementation, profile update, cascade delete |
| `DashboardStatsServiceImpl` | Counts plus summed wallet balance and trading volume |

### Configuration

Settings live in `backend/src/main/resources/application.properties`. Every
environment-specific value is a `${ENV_VAR:default}` placeholder, so the same
file is deployed to every environment:

```properties
spring.application.name=trading-platform
server.port=${SERVER_PORT:8080}

# PostgreSQL
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/trading_platform}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:mysecretpassword}
spring.datasource.driver-class-name=org.postgresql.Driver

# Hikari connection pool
spring.datasource.hikari.maximum-pool-size=${DB_POOL_SIZE:10}
spring.datasource.hikari.minimum-idle=${DB_POOL_MIN_IDLE:2}
spring.datasource.hikari.connection-timeout=30000

# JPA / Hibernate
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
spring.sql.init.mode=never

# JWT (base64 encoded HMAC key, >= 32 bytes once decoded)
jwt.secret=${JWT_SECRET:<committed-dev-default>}
jwt.expiration=${JWT_EXPIRATION_MS:86400000}

# Trading
trading.welcome-balance=${TRADING_WELCOME_BALANCE:10000.00}
trading.fee-rate=${TRADING_FEE_RATE:0.001}

# CORS - comma separated list of allowed origins
app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:3000}

# Logging
logging.level.org.springframework.security=INFO
logging.level.com.ftp.backend=INFO
```

| Property | Meaning |
|----------|---------|
| `spring.jpa.hibernate.ddl-auto=update` | Hibernate evolves the schema on startup. Convenient for development; for production prefer `validate` with migrations, applying `schema.sql` once. |
| `spring.jpa.open-in-view=false` | Lazy loading outside a transaction is disabled — services build DTOs inside their transactional boundary. |
| `hibernate.jdbc.time_zone=UTC` | Timestamps are stored in UTC; the browser renders them in local time. |
| `spring.sql.init.mode=never` | `schema.sql` is a reference/manual migration script, not executed automatically. |
| `jwt.secret` | **Override in every real environment.** `JwtTokenProvider` throws on startup if the value is blank, not base64, or decodes to fewer than 32 bytes. |
| `jwt.expiration` | Token lifetime in milliseconds. Default `86400000` = 24 h. |
| `trading.fee-rate` | Fractional fee applied as `totalAmount × rate` and rounded `HALF_UP` to 2 dp. Default `0.001` = 0.1 %, which matches `FEE_RATE` in the frontend ticket preview. |
| `trading.welcome-balance` | Opening `balance` **and** `availableBalance` for each new wallet. |
| `app.cors.allowed-origins` | Exact origins (scheme + host + port). Credentials are enabled, so `*` is not valid. |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | Hikari pool sizing; tune for multi-instance deployments. |

Generate a strong secret and run the packaged jar with production values:

```bash
openssl rand -base64 48

DB_URL=jdbc:postgresql://db:5432/trading_platform \
DB_USERNAME=trading \
DB_PASSWORD='<secret>' \
JWT_SECRET='<base64-secret>' \
CORS_ALLOWED_ORIGINS=https://app.example.com \
java -jar target/trading-platform-0.0.1-SNAPSHOT.jar
```

> **Security:** the committed `jwt.secret` fallback and the `mysecretpassword`
> database default exist so the project runs locally out of the box. Treat both as
> development-only and always override them through environment variables.

### Database

**Entity model**

| Entity | Table | Key columns | Relationships |
|--------|-------|-------------|---------------|
| `User` | `users` | `username` (unique), `email` (unique), `password` (BCrypt), `enabled`, timestamps | `@ManyToMany` → `Role` via `user_roles` (EAGER) |
| `Role` | `roles` | `name` (unique, `ERole`) | seeded at startup by `RoleInitializer` |
| `MarketData` | `market_data` | `symbol` (unique), `companyName`, OHLC prices, `previousClose`, `volume`, `change`, `changePercent`, `lastUpdated` | — |
| `Order` | `orders` | `orderType`, `orderStatus`, `symbol`, `quantity(19,8)`, `price(19,2)`, `totalAmount`, `filledQuantity`, `filledPrice`, `fee`, timestamps | `@ManyToOne` → `User` |
| `Transaction` | `transactions` | `transactionType`, `symbol`, `quantity`, `price`, `totalAmount`, `fee`, `createdAt` | `@ManyToOne` → `User` and `Order` |
| `Portfolio` | `portfolios` | `symbol`, `quantity`, `avgBuyPrice`, `currentValue`, `profitLoss`, `profitLossPercent`, timestamps | `@ManyToOne` → `User`; unique `(user_id, symbol)` |
| `Wallet` | `wallets` | `userId` (unique), `balance`, `availableBalance`, `currency` (default `USD`), timestamps | logical 1:1 with `User` (stored as `userId`) |
| `Watchlist` | `watchlists` | `name`, `createdAt` | `@ManyToOne` → `User`; `@OneToMany` → `WatchlistItem` (cascade + orphan removal) |
| `WatchlistItem` | `watchlist_items` | `symbol`, `companyName`, `addedAt` | unique `(watchlist_id, symbol)` |

**Enumerations**

| Enum | Values |
|------|--------|
| `ERole` | `ROLE_USER`, `ROLE_ADMIN` |
| `OrderType` | `BUY`, `SELL` |
| `OrderStatus` | `PENDING`, `FILLED`, `CANCELLED`, `REJECTED`, `PARTIALLY_FILLED` — instant orders are stored as `FILLED` |
| `TransactionType` | `BUY`, `SELL` |

Quantities use `NUMERIC(19,8)` (fractional shares), money uses `NUMERIC(19,2)`, and
all rounding is `HALF_UP` to 2 decimals.

**Applying the reference schema**

```bash
psql -h localhost -U postgres -d trading_platform \
  -f backend/src/main/resources/schema.sql
```

`schema.sql` creates every table with `IF NOT EXISTS`, all foreign keys and the
indexes `idx_orders_user_id`, `idx_transactions_user_id`, `idx_portfolios_user_id`,
`idx_watchlists_user_id`, `idx_user_roles_user_id`.

**Seeding market data**

```sql
INSERT INTO market_data
  (symbol, company_name, current_price, open_price, high_price, low_price,
   previous_close, volume, change, change_percent, last_updated)
VALUES
  ('AAPL', 'Apple Inc.',      200.00, 198.00, 202.50, 197.20, 197.50, 55000000, 2.50, 1.27, NOW()),
  ('MSFT', 'Microsoft Corp.', 420.00, 418.00, 423.10, 416.40, 417.00, 21000000, 3.00, 0.72, NOW());
```

### Security Model

**Filter chain (applied in this order)**

1. `AuthRateLimitFilter` — fixed-window limiter, **10 requests / 60 s per client IP**
   on `/api/auth/**`; rejects with `429` and a `Retry-After` header. The client key is
   the first hop of `X-Forwarded-For`, falling back to the socket address.
2. `JwtAuthFilter` — reads `Authorization: Bearer <token>`, verifies signature and
   expiry, loads fresh `UserDetails` (role changes apply immediately) and populates
   the `SecurityContext`. Invalid tokens or deleted users continue as anonymous.
3. Spring Security authorization using the rules below.

Sessions are `STATELESS` and CSRF is disabled because no cookies are used.

**Public vs protected**

| Path | Access |
|------|--------|
| `/api/auth/**` | Public (rate limited) |
| `GET /api/market/**` | Public (read-only) |
| `/actuator/health`, `/actuator/info` | Public |
| `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/webjars/**` | Public |
| Any other `/api/**` endpoint | Authenticated |
| Endpoints annotated `@PreAuthorize("hasRole('ADMIN')")` | `ROLE_ADMIN` only |

**JWT details**

| Item | Value |
|------|-------|
| Library | JJWT 0.12.6 |
| Algorithm | HMAC-SHA256 (`Keys.hmacShaKeyFor`, decoded key ≥ 32 bytes) |
| Claims | `sub` = username, `roles` = authorities list, `iat`, `exp` |
| Lifetime | `jwt.expiration` (default 24 h); tokens cannot be revoked before expiry |
| Transport | `Authorization: Bearer <token>` |

**Passwords** — `BCryptPasswordEncoder` through `DaoAuthenticationProvider`. Login
accepts username *or* email (`UserServiceImpl.loadUserByUsername`); unknown users and
wrong passwords both return `401 Invalid username or password`.

**Ownership** — controllers resolve the caller with `CurrentUserUtils` and pass the id
to services, which compare it with the resource owner and throw `403` on mismatch.
Admin-only `?userId=` parameters are rejected with `403` for non-admin callers.

**Concurrency** — `WalletRepository.findByUserIdForUpdate` and
`PortfolioRepository.findByUserIdAndSymbolForUpdate` use `@Lock(PESSIMISTIC_WRITE)`,
serialising debits/credits and position updates. Unique constraints back the
application checks on usernames, emails, `(user_id, symbol)` and `(watchlist_id, symbol)`.

**Becoming an admin** — registration always assigns `ROLE_USER`. `RoleInitializer`
seeds both roles on first start, so promote a user directly in the database (the
change takes effect on the next request, because authorities are re-read each time):

```sql
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.username = 'jane' AND r.name = 'ROLE_ADMIN'
ON CONFLICT DO NOTHING;
```

### REST API Reference

All endpoints are rooted at `/api`. Except for the public routes listed above, every
request must carry `Authorization: Bearer <jwt>`. Dates are ISO-8601 local
date-times (UTC), decimals are JSON numbers with 2 (money) or 8 (quantity) dp.

**Swagger UI:** `http://localhost:8080/swagger-ui.html` · **OpenAPI JSON:** `/v3/api-docs`

#### Auth — `/api/auth` (public)

| Method | Path | Body | Response | Notes |
|--------|------|------|----------|-------|
| POST | `/register` | `RegisterRequest` | `201` `AuthResponse` | Assigns `ROLE_USER`, creates a funded wallet, returns a token |
| POST | `/login` | `LoginRequest` | `200` `AuthResponse` | Accepts username **or** email |

```jsonc
// POST /api/auth/register
{
  "username": "jane",            // 3–50 chars, required, unique
  "email": "jane@example.com",   // valid email, ≤ 100 chars, unique
  "password": "secret123",       // 6–100 chars
  "firstName": "Jane",           // 1–50 chars
  "lastName": "Doe"              // 1–50 chars
}
```

```jsonc
// 200 / 201 AuthResponse
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "userId": 1,
  "username": "jane",
  "email": "jane@example.com",
  "firstName": "Jane",
  "lastName": "Doe",
  "roles": ["ROLE_USER"]
}
```

| Failure | Status |
|---------|--------|
| Missing/invalid fields | `400` with `fieldErrors` |
| Username or email already taken | `409` |
| Bad credentials | `401` |
| More than 10 attempts/minute | `429` + `Retry-After` |

#### Market data — `/api/market`

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| GET | `/data` | Public | All symbols, unsorted |
| GET | `/data/{symbol}` | Public | Single symbol (case-sensitive lookup) → `404` if unknown |
| GET | `/search?query=apple` | Public | Case-insensitive match on symbol **or** company name |
| POST | `/data` | Admin | Create a symbol from a `MarketData` body; `lastUpdated` is set server-side |
| PUT | `/data/{symbol}` | Admin | Replace prices/metadata of an existing symbol |
| DELETE | `/data/{symbol}` | Admin | Delete a symbol → `204` |

```jsonc
// MarketDataResponse
{
  "symbol": "AAPL",
  "companyName": "Apple Inc.",
  "currentPrice": 200.00,
  "openPrice": 198.00,
  "highPrice": 202.50,
  "lowPrice": 197.20,
  "previousClose": 197.50,
  "volume": 55000000,
  "change": 2.50,
  "changePercent": 1.27,
  "lastUpdated": "2026-03-04T14:30:00"
}
```

#### Trading — `/api/trading`

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| POST | `/orders` | Authenticated | Place an instant-fill BUY/SELL order for the current user |
| GET | `/orders` | Authenticated | Current user's orders |
| GET | `/orders/{orderId}` | Authenticated | One order; `403` if it belongs to someone else |
| DELETE | `/orders/{orderId}` | Authenticated | Cancel own order — only `PENDING` orders can be cancelled (`422` otherwise) |
| GET | `/admin/orders` | Admin | Every order in the system |
| GET | `/admin/orders/user/{userId}` | Admin | Orders of a specific user |
| DELETE | `/admin/orders/{orderId}/force` | Admin | Cancel any user's order (still `PENDING`-only, `422` otherwise) |

```jsonc
// POST /api/trading/orders
{
  "orderType": "BUY",       // BUY | SELL, required
  "symbol": "AAPL",         // required, ≤ 20 chars, trimmed + upper-cased server-side
  "quantity": 1.5,          // required, > 0 (min 0.00000001)
  "price": 0.01             // OPTIONAL and IGNORED: execution uses market_data.currentPrice
}
```

```jsonc
// 200 OrderResponse (the order is filled immediately)
{
  "id": 42,
  "userId": 1,
  "orderType": "BUY",
  "orderStatus": "FILLED",
  "symbol": "AAPL",
  "quantity": 1.5,
  "price": 200.00,
  "totalAmount": 300.00,
  "filledQuantity": 1.5,
  "filledPrice": 200.00,
  "fee": 0.30,
  "createdAt": "2026-03-04T14:30:00",
  "updatedAt": "2026-03-04T14:30:00"
}
```

| Failure | Status | Message |
|---------|--------|---------|
| Unknown symbol | `404` | `Unknown symbol: XYZ` |
| Symbol without a valid price | `422` | `No valid market price available for symbol: …` |
| BUY exceeding available balance | `422` | `Insufficient funds` |
| SELL exceeding holdings | `422` | `Insufficient holdings` |
| Cancelling a non-pending order | `422` | `Cannot cancel order with status: FILLED` |
| Cancelling/viewing another user's order | `403` | — |

#### Portfolio — `/api/portfolio`

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| GET | `/` | Authenticated | Current user's holdings (`List<PortfolioResponse>`) |
| GET | `/summary` | Authenticated | Aggregated totals **and** the holdings list |
| GET | `/admin/portfolios` | Admin | Every holding across all users |
| GET | `/admin/portfolios/user/{userId}` | Admin | Holdings of a specific user |
| GET | `/admin/portfolios/summary/user/{userId}` | Admin | Summary for a specific user |

```jsonc
// PortfolioResponse (one row per symbol held)
{
  "id": 7,
  "userId": 1,
  "symbol": "AAPL",
  "quantity": 3.5,          // total shares held
  "avgBuyPrice": 195.71428571,
  "currentValue": 700.00,   // quantity × latest execution price
  "profitLoss": 15.00,      // currentValue − (avgBuyPrice × quantity)
  "profitLossPercent": 2.19,
  "updatedAt": "2026-03-04T14:30:00"
}
```

```jsonc
// PortfolioSummary
{
  "totalValue": 700.00,
  "totalCost": 685.00,
  "totalProfitLoss": 15.00,
  "totalProfitLossPercent": 2.19,
  "holdings": [ /* PortfolioResponse[] */ ]
}
```

A position is deleted automatically once its quantity reaches zero.

#### Watchlists — `/api/watchlist`

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| POST | `/` | Authenticated | Create a watchlist `{ "name": "Tech" }` (1–100 chars) |
| GET | `/` | Authenticated | Current user's watchlists with items |
| POST | `/{watchlistId}/items?symbol=AAPL&companyName=Apple%20Inc.` | Authenticated | Add a symbol (`companyName` optional) |
| DELETE | `/{watchlistId}/items/{symbol}` | Authenticated | Remove a symbol |
| DELETE | `/{watchlistId}` | Authenticated | Delete a watchlist → `204` |
| GET | `/admin/watchlists` | Admin | All watchlists |
| GET | `/admin/watchlists/user/{userId}` | Admin | Watchlists of a specific user |

```jsonc
// WatchlistResponse
{
  "id": 3,
  "userId": 1,
  "name": "Tech",
  "items": [
    { "id": 11, "symbol": "AAPL", "companyName": "Apple Inc.", "addedAt": "2026-03-04T14:30:00" }
  ],
  "createdAt": "2026-03-04T14:00:00"
}
```

Adding a symbol that already exists returns `409` (unique `(watchlist_id, symbol)`);
accessing another user's watchlist returns `403`.

#### Wallet — `/api/wallet`

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/balance` | Authenticated | Own wallet (`balance`, `availableBalance`) |
| GET | `/balance?userId={id}` | Admin | Another user's wallet (`403` for non-admins) |
| POST | `/deposit` | Admin | Credit a wallet; body `{ "amount": 500.00, "currency": "USD" }` |
| POST | `/withdraw` | Admin | Debit a wallet; same body; `422 Insufficient funds` if too large |

`?userId=` may be added to deposit/withdraw to target another user; without it the
caller's own wallet is used. A wallet is created lazily with zero balance if missing.

```jsonc
// WalletResponse
{
  "id": 1, "userId": 1,
  "balance": 10000.00,
  "availableBalance": 10000.00,
  "currency": "USD",
  "createdAt": "2026-03-04T14:00:00",
  "updatedAt": "2026-03-04T14:30:00"
}
```

#### Users — `/api/users`

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| GET | `/profile` | Authenticated | Current user's profile |
| PUT | `/profile` | Authenticated | Update `firstName`, `lastName`, `email` (`409` if the email is taken) |
| GET | `/stats` | Admin | Global dashboard statistics |
| GET | `/` | Admin | All users |
| GET | `/{userId}` | Admin | One user → `404` if missing |
| DELETE | `/{userId}` | Admin | Delete the user and dependent data → `204` |

```jsonc
// UserResponse
{
  "id": 1, "username": "jane", "email": "jane@example.com",
  "firstName": "Jane", "lastName": "Doe",
  "roles": ["ROLE_USER"], "enabled": true,
  "createdAt": "2026-03-04T14:00:00"
}
```

```jsonc
// DashboardStatsResponse  (GET /api/users/stats)
{
  "totalUsers": 12, "totalOrders": 340, "totalTransactions": 340,
  "totalMarketData": 25, "totalWatchlists": 9, "totalPortfolios": 41,
  "totalWalletBalance": 128450.75,
  "totalTradingVolume": 987345.20
}
```

`UserServiceImpl.deleteUser` removes dependent rows first (wallet → orders →
transactions → portfolios → watchlist items → watchlists → user) so FK constraints
can never block the operation.

#### Transactions — `/api/transactions`

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| GET | `/history` | Authenticated | Caller's ledger |
| GET | `/history?userId={id}` | Admin | Another user's ledger (`403` for non-admins) |
| GET | `/{transactionId}` | Authenticated | One entry; `403` if it belongs to someone else |

```jsonc
// TransactionResponse
{
  "id": 88, "userId": 1, "orderId": 42,
  "transactionType": "BUY", "symbol": "AAPL",
  "quantity": 1.5, "price": 200.00,
  "totalAmount": 300.00, "fee": 0.30,
  "createdAt": "2026-03-04T14:30:00"
}
```

#### cURL walkthrough

```bash
BASE=http://localhost:8080/api

# 1. Register (returns a token)
TOKEN=$(curl -s -X POST $BASE/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"jane","email":"jane@example.com","password":"secret123",
       "firstName":"Jane","lastName":"Doe"}' | jq -r .token)

# 2. Inspect the market and the seeded wallet
curl -s $BASE/market/data -H "Authorization: Bearer $TOKEN"
curl -s $BASE/wallet/balance -H "Authorization: Bearer $TOKEN"

# 3. Buy 1.5 AAPL (the server-side price is used)
curl -s -X POST $BASE/trading/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"orderType":"BUY","symbol":"AAPL","quantity":1.5}'

# 4. Review portfolio, ledger and wallet
curl -s $BASE/portfolio/summary -H "Authorization: Bearer $TOKEN"
curl -s $BASE/transactions/history -H "Authorization: Bearer $TOKEN"
```

### Error Contract

Every failure — raised in a controller, a service or the security filters — returns
the same JSON envelope (`GlobalExceptionHandler`, `JwtAuthEntryPoint`,
`JwtAccessDeniedHandler`):

```jsonc
{
  "timestamp": "2026-03-04T14:30:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Insufficient funds",
  "path": "/api/trading/orders",
  "fieldErrors": { "quantity": "Quantity must be greater than 0" }  // 400 only
}
```

| Status | Raised by | Example message |
|--------|-----------|-----------------|
| `400` | Bean Validation (`@Valid`), malformed JSON, missing/typed params | field-level errors in `fieldErrors` |
| `401` | Bad credentials, missing/expired token (`JwtAuthEntryPoint`) | `Full authentication is required` |
| `403` | `AccessDeniedException`, `ForbiddenActionException`, disabled account | `Access is denied` |
| `404` | `NotFoundException`, unknown route | `Market data not found for symbol: XYZ` |
| `409` | `ConflictException`, `DataIntegrityViolationException` | `Username is already taken` |
| `422` | `UnprocessableEntityException` | `Insufficient funds`, `Insufficient holdings` |
| `429` | `AuthRateLimitFilter` | `Too many authentication attempts…` |
| `500` | Anything unhandled | `An unexpected error occurred` (details logged only) |

`fieldErrors` is present only for validation failures and is keyed by field name.
Stack traces, SQL and exception class names are logged server-side and never sent
to clients.

### Trading Engine

`TradingServiceImpl.placeOrder(userId, request)` executes inside one transaction:

1. Load the user and normalise the symbol (`trim().toUpperCase()`).
2. Load `MarketData` (`404` if unknown) and use `currentPrice` as the execution
   price — **the client-supplied price is ignored**.
3. `totalAmount = price × quantity`; `fee = totalAmount × trading.fee-rate`; both
   rounded `HALF_UP` to 2 dp.
4. Lock the wallet row (`PESSIMISTIC_WRITE`).
   - **BUY:** require `availableBalance ≥ totalAmount + fee`, then debit both balances.
   - **SELL:** load the position with a write lock and require `quantity ≥ requested`
     (`422 Insufficient holdings` otherwise). Sale proceeds are **not** credited to
     the wallet in this version — only the position changes.
5. Persist an `Order` with status `FILLED`, `filledQuantity` and `filledPrice`.
6. Upsert the position:
   - **BUY** into an existing position recalculates
     `avgBuyPrice = (oldAvg × oldQty + price × qty) / (oldQty + qty)`.
   - **SELL** reduces quantity and recomputes value/P&L; a zero quantity deletes the row.
7. Append a `Transaction` linked to the order (the immutable ledger).

Because everything shares one transaction, a failure at any step rolls back the
wallet, order, position and ledger together — balances are never half-applied.

**Worked example** — BUY 1.5 AAPL at `currentPrice = 200.00`, default fee rate `0.001`:

| Step | Calculation | Value |
|------|-------------|-------|
| Notional | `200.00 × 1.5` | `300.00` |
| Fee | `300.00 × 0.001` | `0.30` |
| Wallet debit | `300.00 + 0.30` | `304.30` |
| Position | `quantity 1.5`, `avgBuyPrice 200.00` | `currentValue 300.00` |

This matches `TradingServiceIntegrationTest.buyUsesServerSideMarketPriceAndIgnoresClientPrice`,
where a 1000.00 wallet ends at `799.80` after buying 1 AAPL at 200.00.

> **Scope of the current engine:** orders fill instantly. There is no order book,
> matching engine, partial fill or scheduled execution — `PENDING`,
> `PARTIALLY_FILLED` and `REJECTED` exist in the enum for future use. Because every
> order is immediately `FILLED`, cancel endpoints currently always answer
> `422 Cannot cancel order with status: FILLED`; they are retained for the future
> open-order workflow.
> `market_data` prices change only when an admin updates them; the API does not
> subscribe to an external market feed.

### Backend Tests

The suite has two layers: fast, isolated **unit tests** (JUnit 5 + Mockito, no Spring
context) and **integration tests** (`@SpringBootTest`) that wire the real beans
against an in-memory H2 database.

**Unit tests** — `backend/src/test/java/com/ftp/backend/**`, every collaborator is a
Mockito mock, so each rule is verified in isolation:

| Test class | Scope |
|------------|-------|
| `service/impl/TradingServiceImplTest` | Server-side pricing (client price ignored), symbol normalisation, fee maths, insufficient funds/holdings, position averaging & full liquidation, order ownership checks, admin force-cancel |
| `service/impl/WalletServiceImplTest` | Balance reads, create-on-demand wallets, deposits, withdrawals and the insufficient-funds / non-positive-amount guards |
| `service/impl/AuthServiceImplTest` | Registration (hashed password, `ROLE_USER` assignment, funded wallet, duplicate guards, missing default role) and login token issuance |
| `service/impl/PortfolioServiceImplTest` | Holding mapping and summary aggregation (value, cost, P/L, P/L %) |
| `service/impl/MarketDataServiceImplTest` | Read mapping, search delegation, create/update/delete and unknown-symbol handling |
| `service/impl/UserServiceImplTest` | `UserDetails` resolution (username + email), profile reads/updates, email-conflict guard, cascade-aware delete |
| `service/impl/WatchlistServiceImplTest` | Watchlist CRUD, duplicate-symbol conflict, ownership-scoped lookups |
| `service/impl/TransactionServiceImplTest` | Ledger mapping and cross-user read protection |
| `service/impl/DashboardStatsServiceImplTest` | Admin stats aggregation (counts, wallet balance, trading volume, null safety) |
| `security/JwtTokenProviderTest` | Token round-trip, tamper/expiry rejection, misconfiguration guards |
| `security/CurrentUserUtilsTest` | Caller resolution from the `SecurityContext`, admin check |
| `exception/GlobalExceptionHandlerTest` | The JSON error contract for every mapped exception |

**Integration tests** — real beans on H2:

| Test class | Scope |
|------------|-------|
| `BackendApplicationTests` | `@SpringBootTest` smoke test — the application context loads |
| `AuthServiceIntegrationTest` | Registration creates a funded wallet (`10000.00`) and a valid token; duplicate usernames raise `ConflictException` |
| `TradingServiceIntegrationTest` | Server-side pricing (client price ignored), unknown symbol rejected, insufficient funds/holdings rejected, sell reduces the position |

Tests run against an in-memory H2 database (test scope) and do not need PostgreSQL:

```bash
cd backend
JWT_SECRET="$(openssl rand -base64 48)" ./mvnw test
```

Run only the isolated unit tests:

```bash
./mvnw test -Dtest='*ServiceImplTest,JwtTokenProviderTest,CurrentUserUtilsTest,GlobalExceptionHandlerTest'
```

### Backend Build & Run

```bash
cd backend

# Development (hot restart handled by Spring Boot devtools if enabled)
export JWT_SECRET="$(openssl rand -base64 48)"
export DB_URL="jdbc:postgresql://localhost:5432/trading_platform"
export DB_USERNAME=postgres
export DB_PASSWORD="<your-db-password>"
./mvnw spring-boot:run

# Production artifact
./mvnw clean package                 # add -DskipTests to skip the test phase
java -jar target/trading-platform-0.0.1-SNAPSHOT.jar
```

The API listens on `http://localhost:8080` (override with `SERVER_PORT`).

**Operational endpoints** (Spring Boot Actuator)

| Endpoint | Notes |
|----------|-------|
| `/actuator/health` | Public liveness/readiness probe |
| `/actuator/info` | Public info document |
| `/swagger-ui.html` | Swagger UI for manual exploration |
| `/v3/api-docs` | OpenAPI 3 JSON description of every endpoint |

### Backend Docker

Multi-stage build in `backend/Dockerfile` — Maven build in a JDK image, runtime on a
slim JRE:

```dockerfile
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY src src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-slim
WORKDIR /app
COPY --from=builder /app/target/trading-platform-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t trading-platform-backend ./backend

docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/trading_platform \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD='<db-password>' \
  -e JWT_SECRET='<base64-secret>' \
  -e CORS_ALLOWED_ORIGINS=http://localhost:3000 \
  trading-platform-backend
```

> Use `host.docker.internal` (Docker Desktop) or the database container/service name
> on Linux, and keep `JWT_SECRET` identical across API instances.

---

## Frontend Documentation

### Frontend Module Overview

| Folder / file | Responsibility |
|---------------|----------------|
| `main.jsx` | Mounts `<App />` into `#root` inside `StrictMode` |
| `App.jsx` | Providers (`ThemeModeProvider` → `SnackbarProvider` → `AuthProvider` → `Router`), route table, document title sync |
| `api/api.js` | Shared Axios instance: base URL, request/response interceptors |
| `api/*.js` | One module per resource: `auth`, `market`, `trading`, `portfolio`, `wallet`, `watchlist`, `user`, `transactions` |
| `api/index.js` | Barrel file — pages import from `'../api'` |
| `context/AuthContext.jsx` | Session state (`user`, `login`, `register`, `logout`, `hasRole`) |
| `components/Layout.jsx` | App shell: responsive drawer, top bar, account menu, theme toggle |
| `components/AuthLayout.jsx` | Two-column branded shell for login/register |
| `components/navigation.js` | Single source of truth for navigation groups and route metadata |
| `components/ProtectedRoute.jsx` / `AdminRoute.jsx` | Authentication and role guards (+ `SessionLoader`) |
| `components/PortfolioChart.jsx` / `PortfolioAllocation.jsx` | Recharts area chart and allocation donut built from real API data |
| `components/ui/` | Reusable primitives (see [Design System](#frontend-design-system)) |
| `pages/` | One component per route |
| `theme/` | `tokens.js`, `components.js`, `index.js` (`buildTheme`), `ThemeModeContext.jsx` |
| `utils/format.js` | Currency, number, compact, percent, quantity and date helpers |
| `utils/errors.js` | `getErrorMessage`, `isNetworkError`, `getStatus` for the API error contract |

### Configuration

| Setting | Value |
|---------|-------|
| Dev server port | `3000` |
| Dev proxy | `/api` → `http://localhost:8080` (`changeOrigin: true`) |
| API base URL | `import.meta.env.VITE_API_URL`, falling back to the relative `/api` |
| Production server | nginx on port `80`: SPA fallback + `/api` proxy to `http://backend:8080` |

The relative `/api` default means no CORS is required in development (Vite proxies)
or production (nginx proxies). Set `VITE_API_URL` only when the API lives on a
different origin.

```js
// vite.config.js
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } },
  },
})
```

> `VITE_*` variables are inlined at **build** time. To ship a different API origin,
> pass it to the build: `VITE_API_URL=https://api.example.com/api npm run build`.

### Routing

| Route | Component | Guard | Description |
|-------|-----------|-------|-------------|
| `/login` | `Login` | — (redirects to `/dashboard` if signed in) | Username/email + password form |
| `/register` | `Register` | — (redirects to `/dashboard` if signed in) | Account creation form |
| `/` | `Layout` | `ProtectedRoute` | App shell; index redirects to `/dashboard` |
| `/dashboard` | `Dashboard` | Authenticated | Portfolio/market/orders snapshot for users; system stats for admins |
| `/market` | `Market` | Authenticated | Symbol table, search, watchlist shortcuts; admin create/edit/delete |
| `/trade` | `Trade` | Authenticated | BUY/SELL ticket with quote preview and fee estimate; order list and cancel |
| `/portfolio` | `Portfolio` | Authenticated | Holdings, P/L, allocation donut and value chart |
| `/watchlist` | `Watchlist` | Authenticated | Create/delete watchlists, add/remove symbols |
| `/wallet` | `Wallet` | Authenticated | Balances and recent ledger; admin deposit/withdraw dialog |
| `/history` | `History` | Authenticated | Full transaction ledger (admins can filter by user) |
| `/users` | `Users` | `AdminRoute` | User table, delete with confirmation, system stat cards |
| `*` | — | — | Redirects to `/dashboard` (signed in) or `/login` |

Navigation is grouped (`Overview`, `Trading`, `Account`, `Administration`);
`buildNavGroups(isAdmin)` hides admin-only items, and `routeMeta` drives breadcrumbs
and the browser tab title.

### State & Data Flow

**Session (`AuthContext`)**

- `login(usernameOrEmail, password)` and `register(...)` call the API, persist
  `token` and `user` in `localStorage`, and update state synchronously.
- `logout()` clears both keys; `Layout` then navigates to `/login`.
- `hasRole('ROLE_ADMIN')` is the single role check used by pages and guards.
- The session is restored from `localStorage` on boot, so startup needs no network
  round-trip; a corrupt `user` payload is discarded safely.

**HTTP layer (`api/api.js`)**

- The request interceptor adds `Authorization: Bearer <token>` when a token exists.
- The response interceptor clears storage and redirects to `/login` on `401`, **except**
  for `/auth/*` calls, so a failed login keeps the user on the form.
- Errors reach pages, which render them via `getErrorMessage()` — the backend's
  `fieldErrors` take priority over `message`.

**Page data pattern** — each page holds local `useState` for data/loading/error and a
`useCallback` loader invoked from `useEffect`. Independent requests run in parallel
(`Promise.all`), admins can select a target user for the `?userId=` admin endpoints,
and mutations re-run the loader so the UI never drifts from the server.

### Design System

| Layer | File | Purpose |
|-------|------|---------|
| Tokens | `theme/tokens.js` | Font stacks (Inter, JetBrains Mono), radii, semantic colours (`up` green, `down` red, `neutral`) and the chart palette |
| Theme | `theme/index.js` | `buildTheme(mode)` creates a complete light/dark MUI theme (palette, typography scale, shape, transitions) |
| Overrides | `theme/components.js` | Global component styling so every screen feels like one product |
| Mode | `theme/ThemeModeContext.jsx` | Toggles light/dark, persists the choice in `localStorage` (`ui-color-mode`) and follows the OS preference on first visit |

Reusable primitives exported by `components/ui/index.js`:

| Primitive | Use it for |
|-----------|-----------|
| `PageHeader` | Page title, subtitle, breadcrumb and primary actions |
| `StatCard` | KPI tiles on dashboards and summaries |
| `DataTable` | Any list — loading skeleton, empty state, pagination, custom cells |
| `EmptyState` | "Nothing here yet" with an optional call to action |
| `ErrorState` | Failed request with a retry button |
| `ConfirmDialog` | Destructive confirmations (never `window.confirm`) |
| `SymbolChip`, `StatusChip`, `TrendValue` | Consistent tickers, order/transaction statuses and signed P/L |
| `PageSkeleton`, `CardGridSkeleton`, `TableSkeleton`, `ChartSkeleton` | Loading placeholders |

### Conventions

- Every page starts with `<PageHeader>` so titles and actions align.
- Lists use `<DataTable>`; loading uses the skeletons from `LoadingState.jsx`, never a bare spinner.
- Destructive actions use `<ConfirmDialog>`; outcomes surface as `notistack` toasts.
- All money, quantity, percent and date output goes through `utils/format.js`
  (USD, `en-US`), so formatting is identical everywhere.
- Tickers are normalised with `normalizeSymbol()` before being sent.
- API error text comes from `getErrorMessage()`; never render raw error objects.
- `npm run lint` (ESLint 10 + react-hooks/react-refresh) must pass before merging.

### Frontend Build & Run

```bash
cd frontend
npm install
npm run dev       # http://localhost:3000
npm run build     # production bundle in dist/
npm run preview   # serve the built bundle locally
npm run lint      # ESLint
```

### Frontend Docker

Multi-stage build in `frontend/Dockerfile` — Vite build on `node:20-alpine`, static
output served by `nginx:alpine`:

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

`frontend/nginx.conf`:

- Serves the SPA with `try_files $uri $uri/ /index.html` so deep links survive a refresh.
- Proxies `/api` to `http://backend:8080`, forwarding `Host`, `X-Real-IP`,
  `X-Forwarded-For` and `X-Forwarded-Proto`.
- Adds hardening headers: `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`.

```bash
docker build -t trading-platform-frontend ./frontend
docker run --rm -p 80:80 trading-platform-frontend
```

The container expects the API to be reachable as `backend` on the same Docker
network; override or template `nginx.conf` when the API runs elsewhere.

---

## Environment Variables

### Backend (read by `application.properties`)

| Variable | Description | Default in repo | Required in production |
|----------|-------------|-----------------|------------------------|
| `JWT_SECRET` | Base64 HMAC key, ≥ 32 bytes once decoded | committed dev key | **Yes** |
| `JWT_EXPIRATION_MS` | Token lifetime in milliseconds | `86400000` (24 h) | No |
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/trading_platform` | Yes |
| `DB_USERNAME` | Database user | `postgres` | Yes |
| `DB_PASSWORD` | Database password | `mysecretpassword` | **Yes** |
| `DB_POOL_SIZE` | Hikari maximum pool size | `10` | No |
| `DB_POOL_MIN_IDLE` | Hikari minimum idle connections | `2` | No |
| `CORS_ALLOWED_ORIGINS` | Comma-separated exact origins | `http://localhost:3000` | Yes |
| `TRADING_WELCOME_BALANCE` | Opening balance for new wallets | `10000.00` | No |
| `TRADING_FEE_RATE` | Fractional fee per trade (`0.001` = 0.1 %) | `0.001` | No |
| `SERVER_PORT` | HTTP port | `8080` | No |

### Frontend (read at build time)

| Variable | Description | Default |
|----------|-------------|---------|
| `VITE_API_URL` | API base URL, e.g. `https://api.example.com/api`. Empty means the same-origin `/api` (Vite/nginx proxy) | (empty) |

> **Fee configuration tip:** the fee is `totalAmount × TRADING_FEE_RATE`, so use a
> fraction (`0.001` = 0.1 %). The frontend ticket preview hardcodes the same `0.001`
> in `Trade.jsx`; if you change the backend rate, update that constant so the
> estimated fee stays accurate. Tiny rates on small orders may round to `0.00`.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Startup fails: `JWT secret is not configured` / `must decode to at least 32 bytes` | `JWT_SECRET` missing, not base64, or too short | `export JWT_SECRET="$(openssl rand -base64 48)"` |
| `Connection refused` / `FATAL: database "trading_platform" does not exist` | PostgreSQL not running or DB not created | Start PostgreSQL, run `CREATE DATABASE trading_platform;` |
| `password authentication failed for user "postgres"` | Wrong `DB_USERNAME` / `DB_PASSWORD` | Export the correct credentials before starting |
| `Unknown symbol: AAPL` when placing an order | `market_data` table is empty | Seed rows or `POST /api/market/data` as an admin |
| `403 Access is denied` on `/admin/**` | Account lacks `ROLE_ADMIN` | Promote the user (see [Becoming an admin](#backend-security-model)): `INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r WHERE u.username = 'jane' AND r.name = 'ROLE_ADMIN' ON CONFLICT DO NOTHING;` |
| `429 Too Many Requests` on login | More than 10 auth calls per minute from one IP | Wait for `Retry-After` seconds |
| Browser console shows CORS errors | Frontend origin not in `CORS_ALLOWED_ORIGINS` | Add the exact origin, e.g. `http://localhost:3000` |
| Frontend gets `401` immediately after login | Backend `JWT_SECRET` changed between restarts (tokens invalidated) | Log in again; keep the secret stable |
| `npm ci` fails in Docker (no `package-lock.json`) | Lockfile not committed | Run `npm install` once and commit the lockfile |
| Deep links 404 in production | Static server lacks SPA fallback | Use the provided `nginx.conf` (`try_files … /index.html`) |

---

## Roadmap Notes

The current implementation is a complete, working paper-trading stack. If the
project grows, these are the natural next steps (each is a deliberate non-goal today):

- **Live prices** — the backend includes `spring-boot-starter-websocket` but no
  WebSocket endpoints yet; market data is updated by admins. A publisher (exchange
  feed or scheduled job) and a `@MessageMapping`/STOMP channel would enable live ticks.
- **Order lifecycle** — introduce an order book or matching service to support
  `PENDING`, `PARTIALLY_FILLED` and `REJECTED`, and reserve funds on open orders.
- **Distributed rate limiting** — `AuthRateLimitFilter` is in-memory; a Redis-backed
  counter would enforce limits across multiple API instances.
- **Schema migrations** — move from `ddl-auto=update` to Flyway/Liquibase and run
  with `ddl-auto=validate`.
- **Notifications** — email/audit trail for deposits, withdrawals and large fills.
- **Shared types** — generate a TypeScript client from `/v3/api-docs` to keep the
  frontend DTOs in lockstep with the API.

