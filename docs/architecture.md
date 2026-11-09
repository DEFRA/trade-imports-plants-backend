# trade-imports-plants-backend: design notes

## Build

- `spring-boot-devtools` gives hot reload in the Dockerfile's dev-run stage. It is `optional`, so it never ships to consumers.
- The Surefire and Failsafe `argLine` starts with `@{argLine}`, which picks up JaCoCo's `-javaagent` through late binding. `-XX:+EnableDynamicAgentLoading` suppresses the JDK's warnings about Mockito and ByteBuddy attaching themselves as dynamic agents.

## MongoDB connection

- AWS IAM authentication comes from the connection string: `mongodb://host:port/database?authMechanism=MONGODB-AWS&authSource=$external`.

## Dates and times in MongoDB

- MongoDB has no date-only type. Its `Date` is a moment, so storing a calendar date as one means choosing a zone. Spring Data's built-in converter chooses the JVM default zone, so under `Europe/London` in summer `2026-07-21` is stored as `2026-07-20T23:00:00Z`, a day early.
- `LocalDateStringConverters` stores every `LocalDate` as its ISO-8601 string instead. No zone takes part, and `YYYY-MM-DD` strings sort in date order, so sorts and range queries work as string comparisons.
- Moments are `Instant` fields, stored as BSON dates. They need no custom converter.
- The application pins the JVM default zone to UTC, and the images set `TZ=UTC`.
- BSON dates hold milliseconds, so an `Instant` loses any finer precision when stored.

## Testing dates and times

- `PersistedTimestampZoneIT` sets the JVM zone to `Europe/London` and uses summer dates. On a UTC host any zone drift would be zero, so a test there would pass without proving anything.
- Its storage tests read the raw BSON. A round trip through the repository decodes with the zone that encoded it, so it would hide any drift.
- It reads the original zone in `setUp`, after the Spring context has pinned it to UTC, so the restore puts back UTC and not the host zone.
- No document has a typed date-only property yet, so the `LocalDate` converters are proved against a test-only `DateProbe` document.

## JSON serialisation

- `spring.jackson.serialization.fail-on-empty-beans` is `false` because the `Notification` content sub-object has no fields until the plants data model is agreed, and Jackson refuses to serialise a bean with no properties by default. Remove the setting once `Notification` carries fields.
- `spring.jackson.serialization.write-dates-as-timestamps` is pinned to `false`, the Spring Boot default, so it cannot change by accident. Turned on, it would serialise an `Instant` as a decimal epoch and a `LocalDate` as a `[year, month, day]` array instead of ISO-8601 strings, which would break the API contract without any error.

## Error responses

- `GlobalExceptionHandler` handles only application exceptions. Spring's own exceptions, such as a 404 for an unknown path, keep Spring's responses.
- A request body the parser cannot read answers 400, not 500. The parser message is logged but not returned, because it quotes the submitted value and names internal types.
- A write against a stale concurrency token answers 409 with code `STALE_CONCURRENCY_TOKEN`. The frontend re-reads and retries on that code.

## Outbound proxy

- CDP sends all egress except database traffic through its proxy. `ProxyConfig` sets the JVM proxy properties and the default `ProxySelector` from `HTTP_PROXY`, so every Java HTTP client uses it.
- A malformed proxy URL is logged with its user info replaced by `***`, and without the parse exception, because that exception's message quotes the whole URL, credentials included.

## Notification content and amendments

- `NotificationAggregate` holds identity and metadata at the top, the typed content in its `Notification` sub-object, and the obligation fulfilments as an opaque payload. The backend stores fulfilments as given and never interprets them.
- `submittedAt` is the most recent submission. Submitting from `DRAFT` sets it and submitting from `AMEND` refreshes it. Starting or cancelling an amendment leaves it alone, so cancelling keeps the time of the submission it returns to.
- `submittedNotificationBaseline` and `submittedFulfilmentsBaseline` snapshot the two sub-objects independently. Starting an amendment takes them, cancelling restores from them and clears them, and submitting from `AMEND` clears them. A soft delete from `AMEND` leaves them in place, so a `DELETED` notification can still hold them.
- Soft delete follows the REST `DELETE` convention. Deleting a notification that is already `DELETED` changes nothing and returns it, so a repeat call after a lost response does no harm.

## Automatic expiry outside prod

Two independent safeguards keep expiry out of prod; both must fail before prod loses a notification:

- Stamping: `expireAt` is set at creation only when `NOTIFICATION_TTL_DAYS` is configured and `ENVIRONMENT` is not `prod`, in any letter case.
- Removal: `NotificationExpiryIndex` creates the Mongo TTL index on `expireAt` only when `NOTIFICATION_TTL_EXPIRY_ENABLED=true`, with no `matchIfMissing`, and refuses in prod.

Both default to off: `NOTIFICATION_TTL_DAYS` unset and `NOTIFICATION_TTL_EXPIRY_ENABLED=false`. An environment opts in by setting both.

`expireAt` counts from `created`, so later edits do not extend it. The window is elapsed time, not wall-clock days: `NOTIFICATION_TTL_DAYS` days are always that many 24-hour periods, even across a clock change. A notification can be removed once its `expireAt` has passed and the TTL index exists. Mongo's TTL monitor runs about once a minute and does not promise when it deletes, so removal starts at `expireAt` and can lag behind it. Without the TTL index, a notification stays even after its `expireAt`. A notification with no `expireAt` is never removed. Removal writes no audit record, because it is not a user action.

- The index is created at startup rather than with `@Indexed(expireAfter)`, because `auto-index-creation` would build an annotated index in every environment, prod included.
- Mongo refuses a TTL index on a key that already carries a plain index, so `NotificationExpiryIndex` drops any plain `expireAt` index first.
- Switching the flag back off does not drop the TTL index.
