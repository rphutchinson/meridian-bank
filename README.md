# Meridian Bank

A fictional Spring Boot + Oracle banking application serving as a test harness for the Oracle application-level migration framework. It intentionally exercises every category of Oracle dependency across the application tier so the migration tooling can be validated against a known baseline.

## Prerequisites

- **Docker Desktop** (macOS) — allocate at least 4 CPU cores and 8 GB RAM
- **JDK 21** (Temurin or Oracle)
- **Maven 3.9+**

## Running Locally

### 1. Start Oracle Database

```bash
docker compose -f docker/docker-compose.yml up -d
```

The first run pulls the `container-registry.oracle.com/database/free:latest` image and initializes the schema. This takes **5–10 minutes**. Watch for the ready message:

```bash
docker logs -f meridian-oracle 2>&1 | grep -m1 "DATABASE IS READY TO USE"
```

### 2. Build the Application

```bash
mvn clean package -DskipTests
```

### 3. Start the Application

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4. Verify

```bash
curl http://localhost:8080/actuator/health
```

### 5. Generate the Oracle Dependency Report

```bash
curl http://localhost:8080/api/admin/dependency-report > dependency-report.json
```

This produces the ground-truth baseline listing all annotated Oracle dependencies in the codebase.

## Connection Details

| Property | Value |
|---|---|
| Host | localhost |
| Port | 1521 |
| PDB Service | FREEPDB1 |
| Application User | MERIDIAN / MeridianBank2026 |
| JDBC URL | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |

## Spring Profiles

| Profile | Purpose |
|---|---|
| `dev` (default) | Local Docker Oracle, password auth, verbose logging |
| `prod` | Service-name URL, tuned connection pool, minimal logging |
| `oci` | OCI (thick) driver with TNS names — reference only |
| `secure` | Oracle Wallet auth — reference only |
| `rac` | Oracle RAC with ONS — reference only |

## Stopping the Database

```bash
docker compose -f docker/docker-compose.yml down
```

To also remove the persisted data volume:

```bash
docker compose -f docker/docker-compose.yml down -v
```
