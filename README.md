# trade-imports-plants-backend

The API and business-logic service behind the high-risk plants import
notification journey. It is a Java Spring Boot service on the CDP platform,
storing notifications in MongoDB.

Its one domain package today is
`uk.gov.defra.trade.imports.plants.notification`: the notification aggregate,
its fulfilments, the repository, the controller and the reference-number
generator, plus an audit trail and, outside prod, expiry through a Mongo TTL
index. The plants alpha persists obligations and fulfilments only — there is no outbox, no GBN-AG event
publishing and no PIMS routing.

The frontend for this service is
[DEFRA/trade-imports-plants-frontend](https://github.com/DEFRA/trade-imports-plants-frontend),
which documents the shared notification-journey platform under
`src/server/app/docs/`. Deployed end-to-end tests live in the shared tests
repository `trade-imports-ins-tests` (its `plants` domain, `tests/plants/`), run against the workspace stack in
[DEFRA/trade-imports-workspace](https://github.com/DEFRA/trade-imports-workspace).

Integration tests need Failsafe, so run `mvn verify` — `mvn test` skips them.

* [Requirements](#requirements)
* [Docker Compose](#docker-compose)
* [MongoDB](#mongodb)
* [Inspect MongoDB](#inspect-mongodb)
* [Testing](#testing)
* [Running](#running)
* [Dependabot](#dependabot)

### Requirements

Java 25 (`.java-version`) and Maven.

### Docker Compose

A Docker Compose file is in [compose.yml](compose.yml). It starts:

- Floci, for AWS services (its start-up script creates no resources yet)
- MongoDB
- this service, on port 8091, with the `local` Spring profile

```bash
docker compose up --build -d
```

The full local environment, with the plants frontend and every other
trade-imports service, is the workspace stack in
[DEFRA/trade-imports-workspace](https://github.com/DEFRA/trade-imports-workspace).

### MongoDB

#### MongoDB via Docker

Run MongoDB and Floci without this service:

```bash
docker compose up -d mongodb floci
```

#### MongoDB locally

Alternatively install MongoDB locally:

- Install [MongoDB](https://www.mongodb.com/docs/manual/tutorial/#installation) on your local
  machine
- Start MongoDB:

```bash
sudo mongod --dbpath ~/mongodb-cdp
```

#### MongoDB in CDP environments

In CDP environments a MongoDB instance is already set up
and the credentials exposed as environment variables.

### Inspect MongoDB

To inspect the Database and Collections locally:

```bash
mongosh
```

You can use the CDP Terminal to access the environments' MongoDB.

### Testing

Run the unit tests with:

```bash
mvn test
```

There are also application-level tests that run a full Spring Boot application backed
by [Testcontainers](https://testcontainers.com/).
These tests do not use mocking of any sort and read and write from the containerized database.
They need Docker running.

```bash
mvn clean verify
```

`mvn verify` also runs Checkstyle and the JaCoCo coverage check.

### Running

Run the application against a local MongoDB, with the `local` profile, which turns off
the MongoDB TLS connection that the default profile needs:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

It serves on port 8091.

### SonarCloud

SonarCloud analysis runs in
[.github/workflows/sonarcloud.yml](.github/workflows/sonarcloud.yml), with project
settings in [sonar-project.properties](sonar-project.properties).

### Dependabot

We have added a dependabot configuration file to the repository at
[.github/dependabot.yml](.github/dependabot.yml).

### About the licence

The Open Government Licence (OGL) was developed by the Controller of Her Majesty's Stationery
Office (HMSO) to enable
information providers in the public sector to license the use and re-use of their information under
a common open
licence.

It is designed to encourage use and re-use of information freely and flexibly, with only a few
conditions.
