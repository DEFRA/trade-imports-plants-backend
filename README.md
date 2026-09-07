# trade-imports-plants-backend

The API and business-logic service behind the high-risk plants import
notification journey. It is a Java Spring Boot service on the CDP platform,
storing notifications in MongoDB.

Its one domain package today is
`uk.gov.defra.trade.imports.plants.notification`: the notification aggregate,
its fulfilments, the repository, the controller and the reference-number
generator, plus an expiry sweeper and an audit trail. The plants alpha persists
obligations and fulfilments only — there is no outbox, no GBN-AG event
publishing and no PIMS routing.

The frontend for this service is
[DEFRA/trade-imports-plants-frontend](https://github.com/DEFRA/trade-imports-plants-frontend),
which documents the shared notification-journey platform under
`src/server/app/docs/`. Deployed end-to-end tests live in the shared tests
repository `trade-imports-animals-tests`, run against the workspace stack in
[DEFRA/trade-imports-workspace](https://github.com/DEFRA/trade-imports-workspace).

Integration tests need Failsafe, so run `mvn verify` — `mvn test` skips them.

* [Install MongoDB](#install-mongodb)
* [Inspect MongoDB](#inspect-mongodb)
* [Testing](#testing)
* [Running](#running)
* [Dependabot](#dependabot)

### Docker Compose

A Docker Compose template is in [compose.yml](compose.yml).

A local environment with:

- Floci for AWS services (S3, SQS)
- Redis
- MongoDB
- This service.
- A commented out frontend example.

```bash
docker compose --profile services up --build -d
```

A more extensive setup is available
in [github.com/DEFRA/cdp-local-environment](https://github.com/DEFRA/cdp-local-environment)

### MongoDB

#### MongoDB via Docker

Run infrastructure services (MongoDB, Floci, Redis):

```bash
docker compose --profile infra up -d
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
and the credentials exposed as enviromment variables.

### Inspect MongoDB

To inspect the Database and Collections locally:

```bash
mongosh
```

You can use the CDP Terminal to access the environments' MongoDB.

### Testing

Run the tests with:

```bash
mvn test
```

There are also application level ests run by running a full Spring Boot application backed
by [Testcontainers](https://testcontainers.com/).
These tests do not use mocking of any sort and read and write from the containerized database.

```bash
mvn clean verify
```

### Running

Run the application:

```bash
mvn spring-boot:run
```

### SonarCloud

Example SonarCloud configuration are available in the GitHub Action workflows.

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
