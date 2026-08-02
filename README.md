# Warcana

Warcana archives public Mastodon and Bluesky accounts to WARC, captures linked
media, extracts a common social-post model, and provides full-text search.

It requires Java 21 or newer.

## Build

```sh
mvn package
java -jar target/warcana-0.1.0.jar --help
```

## Collections

A collection is the recommended way to run recurring captures:

```sh
java -jar target/warcana-0.1.0.jar init collection
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

Add one account per line to `accounts.txt`. Blank lines and lines beginning
with `#` are ignored. Use a platform prefix for handles that cannot be
identified unambiguously:

```text
mastodon @alice@example.social
bluesky alice.bsky.social
```

Then archive the collection:

```sh
java -jar target/warcana-0.1.0.jar archive \
  --collection collection \
  --include-media
```

All accounts processed by one collection run share three independently
rolling WARC streams:

- `warcana-posts-*.warc.gz` contains gzip-compressed API requests and responses.
- `warcana-media-*.warc` contains images, previews, avatars, and other
  non-video media.
- `warcana-video-*.warc` contains video resources, HLS playlists, and their
  dependent segments.

Each stream closes at the next complete HTTP exchange boundary after reaching
1 GiB. Media and video WARCs are created lazily only when the run discovers
resources of that type. Completed WARCs are never modified. On later runs,
Warcana fetches the newest pages until it reaches the last identity recorded
for each account.

While a job is running, its active WARCs have a `.open` suffix and their state is
checkpointed in `state/jobs/`. After an interruption, rerunning the same
command truncates each open WARC to its last complete record boundary,
resumes the API cursor, and drains any persisted media queue. HLS child
resources are checkpointed independently, so completed segments do not need
to be downloaded again.

Only one archive process may write a collection at a time. Accounts are
processed sequentially. If an account fails, the shared WARC remains open and
later accounts wait until the failed account is successfully resumed. This
ensures recovery can truncate to the last checkpoint without discarding
records belonging to another account.

Bounds are stored with a job and therefore remain stable across restarts:

```sh
java -jar target/warcana-0.1.0.jar archive \
  --collection collection \
  --since 2026-01-01 \
  --max-posts 5000 \
  --max-pages 100
```

API responses are preserved verbatim, so the final fetched page can contain
records just outside a requested boundary. Bounds determine pagination and
media selection rather than rewriting the captured response.

Search all completed WARCs in a collection:

```sh
java -jar target/warcana-0.1.0.jar search collection "climate archive"
```

The index is rebuilt automatically whenever the completed WARC set changes.

Browse and manage a collection in the local interface:

```sh
java -jar target/warcana-0.1.0.jar serve collection
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
to a network-accessible address.

Inspect a collection:

```sh
java -jar target/warcana-0.1.0.jar inspect collection
```

This reports configured and tracked accounts, completed posts/media/video
WARCs, open streams, active resumable jobs, total sizes, and search-index
state. Use `--format json` for machine-readable output.

Individual completed or open WARC files can be inspected in the same way:

```sh
java -jar target/warcana-0.1.0.jar inspect \
  collection/warcs/warcana-posts-example.warc
```

WARC inspection reports record types, HTTP statuses, record dates, extracted
posts by platform, block-digest mismatches, and parser warnings.

## Single-account capture

Single-file capture remains available and now writes through a temporary file
before atomically publishing the requested destination:

```sh
java -jar target/warcana-0.1.0.jar archive \
  --include-media \
  --since 2026-01-01 \
  --max-posts 1000 \
  -o alice.warc \
  @alice@example.social
```

Use `archive --help`, `search --help`, or `extract --help` for all options.
