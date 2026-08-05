# SocialWARC

SocialWARC is a tool for working with social media web archive data. It can search
Mastodon, Bluesky, Threads and Twitter posts in WARC files (that were collected 
separately using a browser-based crawler). It can also capture Mastodon and Bluesky
accounts and attached media to WARC using their public APIs.

It requires Java 21 or newer.

## Build

```sh
mvn package
java -jar target/socialwarc-0.1.0.jar --help
```

## Collections

A collection is the recommended way to run recurring captures:

```sh
java -jar target/socialwarc-0.1.0.jar init collection
```

This creates:

```text
collection/
├── accounts.txt
├── warcs/                 completed immutable posts/media/video WARCs
├── index/                 collection-wide Lucene index
├── logs/archive.log
└── state/
    ├── collection.json    per-account high-water marks
    ├── collection.lock
    └── jobs/              resumable in-progress jobs
```

Add one target per line to `accounts.txt`. Blank lines and lines beginning
with `#` are ignored. Use a platform prefix for handles that cannot be
identified unambiguously:

```text
mastodon @alice@example.social
mastodon-local example.social
bluesky alice.bsky.social
```

Then archive the collection:

```sh
java -jar target/socialwarc-0.1.0.jar archive \
  --collection collection \
  --include-media
```

All accounts processed by one collection run share three independently
rolling WARC streams:

- `socialwarc-posts-*.warc.gz` contains gzip-compressed API requests and responses.
- `socialwarc-media-*.warc` contains images, previews, avatars, and other
  non-video media.
- `socialwarc-video-*.warc` contains video resources, HLS playlists, and their
  dependent segments.

Each stream closes at the next complete HTTP exchange boundary after reaching
1 GiB. Media and video WARCs are created lazily only when the run discovers
resources of that type. Completed WARCs are never modified. On later runs,
SocialWARC fetches the newest pages until it reaches the last identity recorded
for each target.

While a job is running, its active WARCs have a `.open` suffix and their state is
checkpointed in `state/jobs/`. After an interruption, rerunning the same
command truncates each open WARC to its last complete record boundary,
resumes the API cursor, and drains any persisted media queue. HLS child
resources are checkpointed independently, so completed segments do not need
to be downloaded again.

Only one archive process may write a collection at a time. Targets on
different servers are captured concurrently, while all requests to an
individual server are serialized. Each HTTP exchange is staged before it is
atomically appended to a shared run WARC, so concurrent targets cannot leave
partial interleaved records after an interruption. A failed target is recorded
in collection state and does not stop the other targets. Every run closes its
WARCs, including small ones, so they are ready for permanent ingest.

Bounds are stored with a job and therefore remain stable across restarts:

```sh
java -jar target/socialwarc-0.1.0.jar archive \
  --collection collection \
  --since 2026-01-01 \
  --max-posts 5000 \
  --max-pages 100
```

API responses are preserved verbatim, so the final fetched page can contain
records just outside a requested boundary.

Search all completed WARCs in a collection:

```sh
java -jar target/socialwarc-0.1.0.jar search collection "climate archive"
```

The index is rebuilt automatically whenever the completed WARC set changes.
Threads profile pages and legacy Twitter API responses in existing WARCs are
also recognized and can be filtered with `--platform threads` or
`--platform twitter`.

Browse and manage a collection in the local interface:

```sh
java -jar target/socialwarc-0.1.0.jar serve collection
```

Then open `http://127.0.0.1:8080/`. The Browse area supports full-text search,
platform, author, date and media filters, recorded post details, and playback
of media stored in completed collection WARCs. It never fetches missing
resources from the live web. The Manage area edits `accounts.txt`, starts and
monitors captures, and persists a recurring interval schedule in
`state/archive-schedule.properties`. Scheduled runs occur while the `serve`
process is running. Use `--host` and `--port` to change the listening address;
the default is accessible only from the local machine. For safety, the Manage
area accepts connections only from the local machine even when Browse is bound
to a network-accessible address. Failed targets and their last error appear on
the Manage page, where they can be disabled, re-enabled, or cleared.

`GET /health` is a liveness probe. `GET /ready` is a readiness probe that
checks the collection structure, configuration, state, and WARC source list;
it returns HTTP 503 when they cannot be read. Both endpoints return JSON and
do not require management authentication.

### Optional OpenID Connect

The Manage area can use Keycloak OpenID Connect instead of the loopback-only
check. Users must have the `panadmin` realm role (configurable). Configure a
confidential client with standard flow enabled and this callback URL:

```text
https://socialwarc.example/oidc/callback
```

Set the following environment variables when starting `serve`:

```sh
SOCIALWARC_COLLECTION=/collections/social \
SOCIALWARC_HOST=0.0.0.0 \
SOCIALWARC_PORT=8080 \
SOCIALWARC_PUBLIC_URL=https://socialwarc.example/ \
SOCIALWARC_OIDC_ISSUER=https://keycloak.example/realms/archive \
SOCIALWARC_OIDC_CLIENT_ID=socialwarc \
SOCIALWARC_OIDC_CLIENT_SECRET_FILE=/run/secrets/oidc-client-secret \
java -jar target/socialwarc-0.1.0.jar serve
```

`SOCIALWARC_OIDC_CLIENT_SECRET` may be used instead of the mounted secret file.
OIDC is disabled when the OIDC variables are absent. The public URL currently
must be an origin without a path component.

### Bamboo archival storage

To ingest each completed scheduled run into Bamboo, set the Bamboo URL and
destination crawl-series ID:

```sh
SOCIALWARC_BAMBOO_URL=https://pandas.example/bamboo \
SOCIALWARC_BAMBOO_SERIES_ID=1234
```

Completed run WARCs are placed in a durable outbox before upload. Temporary
Bamboo or network failures are retried with backoff, and crawl creation and
WARC uploads are idempotent. When OIDC is configured, Bamboo ingest uses the
same issuer, client ID, and secret with the Keycloak client-credentials flow.

Existing Bamboo crawl series can be included in Browse and Search by listing
their IDs:

```sh
SOCIALWARC_BAMBOO_INDEX_SERIES_IDS=1234,5678
```

SocialWARC indexes those WARCs directly from Bamboo using HTTP range requests.
The list is read when `serve` starts, so restart the process after adding 
crawls externally.

Inspect a collection:

```sh
java -jar target/socialwarc-0.1.0.jar inspect collection
```

This reports configured and tracked accounts, completed posts/media/video
WARCs, open streams, active resumable jobs, total sizes, and search-index
state. Use `--format json` for machine-readable output.

Individual completed or open WARC files can be inspected in the same way:

```sh
java -jar target/socialwarc-0.1.0.jar inspect \
  collection/warcs/socialwarc-posts-example.warc
```

WARC inspection reports record types, HTTP statuses, record dates, extracted
posts by platform, block-digest mismatches, and parser warnings.

## Single-account capture

Single-file capture remains available and now writes through a temporary file
before atomically publishing the requested destination:

```sh
java -jar target/socialwarc-0.1.0.jar archive \
  --include-media \
  --since 2026-01-01 \
  --max-posts 1000 \
  -o alice.warc \
  @alice@example.social
```

Archive the public posts originating on one Mastodon server with the local
timeline endpoint:

```sh
java -jar target/socialwarc-0.1.0.jar archive \
  --local-timeline \
  --include-media \
  -o example-social-local.warc \
  example.social
```

This captures the local public timeline exposed by the server. It does not
include followers-only, direct, or other non-public posts.

Use `archive --help`, `search --help`, or `extract --help` for all options.
