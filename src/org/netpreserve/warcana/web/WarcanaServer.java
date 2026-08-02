package org.netpreserve.warcana.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.warcana.CliSupport;
import org.netpreserve.warcana.common.ProgressReporter;
import org.netpreserve.warcana.common.SocialJson;
import org.netpreserve.warcana.common.collection.CollectionStore;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.resource.ArchivedResource;
import org.netpreserve.warcana.common.resource.WarcResourceIndex;
import org.netpreserve.warcana.common.search.SearchHit;
import org.netpreserve.warcana.common.search.SearchOptions;
import org.netpreserve.warcana.common.search.SearchResults;
import org.netpreserve.warcana.common.search.SearchSort;
import org.netpreserve.warcana.common.search.WarcSearchIndex;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only HTTP interface for searching and viewing a collection.
 */
public final class WarcanaServer implements AutoCloseable {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;
    private static final Pattern PLAYLIST_URI = Pattern.compile(
            "URI=(?:\"([^\"]*)\"|([^,\\s]*))");
    private static final String CSP = "default-src 'self'; " +
            "img-src 'self' data:; media-src 'self'; " +
            "style-src 'self'; script-src 'self'; " +
            "base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    private final CollectionStore store;
    private final WarcSearchIndex searchIndex;
    private final WarcResourceIndex resourceIndex;
    private final HttpServer server;
    private final ExecutorService executor;

    public WarcanaServer(
            Path collection, InetSocketAddress address,
            boolean rebuild, ProgressReporter progress) throws IOException {
        store = new CollectionStore(collection);
        if (!store.isCollection()) {
            throw new IOException("Not a Warcana collection: " + collection);
        }
        List<Path> postWarcs = store.completedPostWarcs();
        if (postWarcs.isEmpty()) {
            throw new IOException(
                    "Collection contains no completed post WARC files: " +
                            collection);
        }

        searchIndex = new WarcSearchIndex(CliSupport.responseExtractors());
        searchIndex.ensureCurrent(
                postWarcs, store.indexDirectory(), rebuild, progress);
        resourceIndex = new WarcResourceIndex();
        resourceIndex.ensureCurrent(
                store.completedResourceWarcs(),
                store.resourceIndexDirectory(),
                rebuild,
                progress);

        server = HttpServer.create(address, 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public URI baseUri() {
        InetSocketAddress address = server.getAddress();
        String host = address.getAddress().isAnyLocalAddress()
                ? "127.0.0.1"
                : address.getHostString();
        return URI.create("http://" + host + ":" + address.getPort() + "/");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            addSecurityHeaders(exchange.getResponseHeaders());
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                searchPage(exchange);
            } else if ("/app.css".equals(path)) {
                staticResource(exchange, "app.css", "text/css; charset=utf-8");
            } else if ("/history.js".equals(path)) {
                staticResource(
                        exchange, "history.js",
                        "text/javascript; charset=utf-8");
            } else if ("/post".equals(path)) {
                postPage(exchange);
            } else if ("/post.json".equals(path)) {
                postJson(exchange);
            } else if (path.startsWith("/media/")) {
                media(exchange, path.substring("/media/".length()));
            } else {
                error(exchange, 404, "Not found");
            }
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (IOException e) {
            if (exchange.getResponseCode() == -1) {
                error(exchange, 500, "Unable to read the collection");
            }
        } catch (RuntimeException e) {
            if (exchange.getResponseCode() == -1) {
                error(exchange, 500, "Unexpected server error");
            }
        } finally {
            exchange.close();
        }
    }

    private void searchPage(HttpExchange exchange) throws IOException {
        requireGet(exchange);
        Map<String, String> query = queryParameters(exchange.getRequestURI());
        String text = query.getOrDefault("q", "");
        String platform = blankToNull(query.get("platform"));
        if (platform != null &&
                !"mastodon".equals(platform) &&
                !"bluesky".equals(platform)) {
            throw new IllegalArgumentException("Unknown platform");
        }
        String author = blankToNull(query.get("author"));
        Instant since = parseDate(query.get("since"), false);
        Instant until = parseDate(query.get("until"), true);
        boolean hasMedia = parseBoolean(query.get("hasMedia"));
        SearchSort sort = SearchSort.parse(query.get("sort"));
        int offset = parseInt(query.get("offset"), 0);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw new IllegalArgumentException("since must not be after until");
        }

        SearchResults results = searchIndex.search(
                store.indexDirectory(),
                new SearchOptions(
                        text, platform, author, since, until,
                        hasMedia, sort, DEFAULT_PAGE_SIZE, offset));
        Map<String, String> resources = resourcesFor(results.hits());
        html(exchange, 200, CollectionHtmlRenderer.index(
                query, results.totalHits(), results.hits(), resources,
                threadTargetsFor(results.hits())));
    }

    private void postPage(HttpExchange exchange) throws IOException {
        requireGet(exchange);
        Map<String, String> query = queryParameters(exchange.getRequestURI());
        ResolvedPost resolved = resolvePost(exchange, query);
        List<SearchHit> thread = searchIndex.thread(
                store.indexDirectory(), resolved.display());
        html(exchange, 200, CollectionHtmlRenderer.post(
                resolved.display(),
                thread,
                resourcesFor(thread),
                threadTargetsFor(thread),
                resolved.quotedUrl()));
    }

    private void postJson(HttpExchange exchange) throws IOException {
        requireGet(exchange);
        Map<String, String> query = queryParameters(exchange.getRequestURI());
        ResolvedPost resolved = resolvePost(exchange, query);
        String view = required(query, "view");
        if ("common".equals(view)) {
            json(exchange, resolved.display().post());
        } else if ("original".equals(view)) {
            String targetUrl = resolved.quotedUrl() == null
                    ? resolved.source().post().url()
                    : resolved.quotedUrl();
            JsonNode original = originalJson(
                    resolved.source(),
                    targetUrl,
                    resolved.quotedUrl() != null);
            if (original == null) {
                error(exchange, 404, "Original post JSON not found");
                return;
            }
            json(exchange, original);
        } else {
            throw new IllegalArgumentException(
                    "Unknown JSON view (expected common or original)");
        }
    }

    private ResolvedPost resolvePost(
            HttpExchange exchange, Map<String, String> query)
            throws IOException {
        String platform = required(query, "platform");
        String identity = required(query, "identity");
        SearchHit hit = searchIndex.find(
                store.indexDirectory(), platform, identity);
        if (hit == null) {
            error(exchange, 404, "Post not found");
            throw new ResponseSentException();
        }
        SearchHit source = hit;
        String quotedUrl = blankToNull(query.get("quoted"));
        if (quotedUrl != null) {
            Post quoted = findPostByUrl(hit.post(), quotedUrl);
            if (quoted == null) {
                error(exchange, 404, "Quoted post not found");
                throw new ResponseSentException();
            }
            hit = new SearchHit(
                    null, "", hit.platform(), hit.identity(),
                    hit.sourceTarget(), hit.warcRecordId(),
                    hit.sourceWarc(), hit.warcOffset(), hit.warcDate(), quoted);
        }
        return new ResolvedPost(hit, source, quotedUrl);
    }

    private JsonNode originalJson(
            SearchHit hit, String targetUrl, boolean quoted)
            throws IOException {
        Path warc = store.warcsDirectory().resolve(hit.sourceWarc()).normalize();
        if (!warc.startsWith(store.warcsDirectory()) ||
                !Files.isRegularFile(warc)) {
            throw new IOException("Source WARC not found");
        }
        try (var reader = new WarcReader(warc)) {
            reader.position(hit.warcOffset());
            var next = reader.next();
            if (next.isEmpty() ||
                    !(next.get() instanceof WarcResponse response) ||
                    !hit.warcRecordId().equals(response.id().toString())) {
                throw new IOException("Post search index is stale");
            }
            JsonNode root = SocialJson.mapper.readTree(
                    response.http().bodyDecoded().stream());
            return findOriginalJson(
                    root,
                    hit.platform(),
                    quoted ? null : hit.identity(),
                    targetUrl);
        }
    }

    static JsonNode findOriginalJson(
            JsonNode node, String platform, String identity, String targetUrl) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if ("mastodon".equals(platform) &&
                    (targetUrl.equals(text(node, "url")) ||
                            identity != null &&
                                    identity.equals(text(node, "uri")))) {
                return node;
            }
            if ("bluesky".equals(platform)) {
                JsonNode post = node.get("post");
                if (post != null && post.isObject() &&
                        matchesBluesky(post, identity, targetUrl)) {
                    return node;
                }
                if (matchesBluesky(node, identity, targetUrl)) {
                    return node;
                }
            }
            for (var property : node.properties()) {
                JsonNode found = findOriginalJson(
                        property.getValue(), platform, identity, targetUrl);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findOriginalJson(
                        child, platform, identity, targetUrl);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean matchesBluesky(
            JsonNode node, String identity, String targetUrl) {
        String uri = text(node, "uri");
        if (uri == null) {
            return false;
        }
        if (identity != null &&
                (identity.equals(uri) || identity.endsWith(":" + uri))) {
            return true;
        }
        JsonNode author = node.get("author");
        String handle = author == null ? null : text(author, "handle");
        int slash = uri.lastIndexOf('/');
        return handle != null && slash >= 0 && targetUrl.equals(
                "https://bsky.app/profile/" + handle + "/post/" +
                        uri.substring(slash + 1));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || !value.isString() ? null : value.stringValue();
    }

    private Map<String, String> resourcesFor(List<SearchHit> hits)
            throws IOException {
        var urls = new LinkedHashSet<String>();
        for (SearchHit hit : hits) {
            collectUrls(hit.post(), urls);
        }
        return resourceIndex.idsForUrls(
                store.resourceIndexDirectory(), urls);
    }

    private Map<String, SearchHit> threadTargetsFor(List<SearchHit> hits)
            throws IOException {
        var references = new LinkedHashMap<String, ThreadLookup>();
        for (SearchHit hit : hits) {
            collectThreadLookups(hit.post(), hit.platform(), references);
        }
        var targets = new LinkedHashMap<String, SearchHit>();
        for (var entry : references.entrySet()) {
            ThreadLookup lookup = entry.getValue();
            SearchHit target = searchIndex.findByNativeId(
                    store.indexDirectory(),
                    lookup.platform(),
                    lookup.site(),
                    lookup.id());
            if (target != null) {
                targets.put(entry.getKey(), target);
            }
        }
        return Map.copyOf(targets);
    }

    private void media(HttpExchange exchange, String id) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            methodNotAllowed(exchange, "GET, HEAD");
            return;
        }
        ArchivedResource resource = resourceIndex.find(
                store.resourceIndexDirectory(), id);
        if (resource == null) {
            error(exchange, 404, "Archived resource not found");
            return;
        }
        Path warc = store.warcsDirectory()
                .resolve(resource.warcFilename())
                .normalize();
        if (!warc.startsWith(store.warcsDirectory()) ||
                !Files.isRegularFile(warc)) {
            error(exchange, 404, "Archived resource WARC not found");
            return;
        }

        try (var reader = new WarcReader(warc)) {
            reader.position(resource.warcOffset());
            var next = reader.next();
            if (next.isEmpty() ||
                    !(next.get() instanceof WarcResponse response) ||
                    !resource.warcRecordId().equals(response.id().toString()) ||
                    !resource.url().equals(response.target().toString())) {
                error(exchange, 409, "Resource index is stale");
                return;
            }
            var body = response.http().bodyDecoded();
            if (isPlaylist(resource.contentType())) {
                servePlaylist(exchange, method, resource, body.stream());
                return;
            }
            long size = body.size();
            ByteRange range = parseRange(
                    exchange.getRequestHeaders().getFirst("Range"), size);
            long start = range == null ? 0 : range.start();
            long end = range == null ? size - 1 : range.end();
            long length = size == 0 ? 0 : end - start + 1;

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", safeContentType(resource.contentType()));
            headers.set("Accept-Ranges", "bytes");
            headers.set("Cache-Control", "private, max-age=31536000, immutable");
            if (range != null) {
                headers.set(
                        "Content-Range",
                        "bytes " + start + "-" + end + "/" + size);
            }
            if (!isInlineMedia(resource.contentType())) {
                headers.set("Content-Disposition", "attachment");
            }

            int status = range == null ? 200 : 206;
            if ("HEAD".equals(method)) {
                headers.set("Content-Length", Long.toString(length));
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.sendResponseHeaders(status, length);
            if (length == 0) {
                return;
            }
            try (InputStream input = body.stream();
                 OutputStream output = exchange.getResponseBody()) {
                input.skipNBytes(start);
                copy(input, output, length);
            }
        }
    }

    private void servePlaylist(
            HttpExchange exchange, String method,
            ArchivedResource resource, InputStream input) throws IOException {
        byte[] raw = input.readNBytes(MAX_PLAYLIST_BYTES + 1);
        if (raw.length > MAX_PLAYLIST_BYTES) {
            error(exchange, 413, "Archived playlist is too large");
            return;
        }
        String playlist = new String(raw, StandardCharsets.UTF_8);
        URI base = URI.create(resource.url());
        var references = new LinkedHashSet<String>();
        for (String line : playlist.split("\\R", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                resolvePlaylistReference(base, trimmed, references);
            }
            Matcher matcher = PLAYLIST_URI.matcher(line);
            while (matcher.find()) {
                String value = matcher.group(1) == null
                        ? matcher.group(2)
                        : matcher.group(1);
                resolvePlaylistReference(base, value, references);
            }
        }
        Map<String, String> ids = resourceIndex.idsForUrls(
                store.resourceIndexDirectory(), references);
        var rewritten = new StringBuilder();
        String[] lines = playlist.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                line = localPlaylistReference(base, trimmed, ids);
            } else {
                Matcher matcher = PLAYLIST_URI.matcher(line);
                var replacement = new StringBuffer();
                while (matcher.find()) {
                    String value = matcher.group(1) == null
                            ? matcher.group(2)
                            : matcher.group(1);
                    matcher.appendReplacement(
                            replacement,
                            Matcher.quoteReplacement(
                                    "URI=\"" +
                                            localPlaylistReference(
                                                    base, value, ids) +
                                            "\""));
                }
                matcher.appendTail(replacement);
                line = replacement.toString();
            }
            rewritten.append(line);
            if (i < lines.length - 1) {
                rewritten.append('\n');
            }
        }
        byte[] bytes = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set(
                "Content-Type",
                "application/vnd.apple.mpegurl; charset=utf-8");
        headers.set("Cache-Control", "private, max-age=31536000, immutable");
        if ("HEAD".equals(method)) {
            headers.set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void staticResource(
            HttpExchange exchange, String name, String contentType)
            throws IOException {
        requireGet(exchange);
        String resource = "/org/netpreserve/warcana/web/" + name;
        try (InputStream input = WarcanaServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                error(exchange, 404, "Interface resource not found");
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set(
                    "Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void html(HttpExchange exchange, int status, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void json(HttpExchange exchange, Object value)
            throws IOException {
        byte[] bytes = SocialJson.mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(value);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void error(
            HttpExchange exchange, int status, String message)
            throws IOException {
        String safe = org.jsoup.nodes.Entities.escape(message);
        html(exchange, status,
                "<!doctype html><html><head><meta charset=utf-8>" +
                        "<title>Error</title><link rel=stylesheet href=/app.css>" +
                        "</head><body><main><h1>Error</h1><p>" + safe +
                        "</p><p><a href=/>Home</a></p></main></body></html>");
    }

    private static void requireGet(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
        }
    }

    private static void methodNotAllowed(
            HttpExchange exchange, String allow) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        error(exchange, 405, "Method not allowed");
        throw new ResponseSentException();
    }

    private static void addSecurityHeaders(Headers headers) {
        headers.set("Content-Security-Policy", CSP);
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private static Map<String, String> queryParameters(URI uri) {
        var values = new LinkedHashMap<String, String>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> values, String key) {
        String value = blankToNull(values.get(key));
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number: " + value);
        }
    }

    private static boolean parseBoolean(String value) {
        if (value == null || value.isBlank() || "false".equals(value)) {
            return false;
        }
        if ("true".equals(value)) {
            return true;
        }
        throw new IllegalArgumentException("Invalid boolean: " + value);
    }

    private static Instant parseDate(String value, boolean endOfDay) {
        return value == null || value.isBlank()
                ? null
                : CliSupport.parseDate(value, endOfDay);
    }

    private static void collectUrls(Post post, LinkedHashSet<String> urls) {
        if (post == null) {
            return;
        }
        if (post.author() != null) {
            urls.add(post.author().avatarUrl());
            urls.add(post.author().bannerUrl());
        }
        if (post.attachments() != null) {
            for (Attachment attachment : post.attachments()) {
                urls.add(attachment.url());
                urls.add(attachment.thumbnailUrl());
                if (attachment.sources() != null) {
                    attachment.sources().stream()
                            .map(Attachment.Source::url)
                            .forEach(urls::add);
                }
            }
        }
        collectUrls(post.repost(), urls);
        collectUrls(post.quotedPost(), urls);
        urls.remove(null);
        urls.remove("");
    }

    private static void collectThreadLookups(
            Post post,
            String platform,
            Map<String, ThreadLookup> references) {
        if (post == null) {
            return;
        }
        String site = post.site() == null ? null : post.site().name();
        if (post.thread() != null) {
            addThreadLookup(
                    post.thread().parent(), platform, site, references);
            addThreadLookup(
                    post.thread().root(), platform, site, references);
        }
        collectThreadLookups(post.repost(), platform, references);
        collectThreadLookups(post.quotedPost(), platform, references);
    }

    private static void addThreadLookup(
            Post.PostReference reference,
            String platform,
            String site,
            Map<String, ThreadLookup> references) {
        if (reference == null || reference.id() == null || site == null) {
            return;
        }
        String key = CollectionHtmlRenderer.threadKey(
                platform, site, reference.id());
        references.putIfAbsent(
                key,
                new ThreadLookup(platform, site, reference.id()));
    }

    private static Post findPostByUrl(Post post, String url) {
        if (post == null) {
            return null;
        }
        if (url.equals(post.url())) {
            return post;
        }
        Post found = findPostByUrl(post.quotedPost(), url);
        return found != null ? found : findPostByUrl(post.repost(), url);
    }

    private static String safeContentType(String value) {
        if (value == null ||
                value.length() > 200 ||
                value.chars().anyMatch(character ->
                        character == '\r' || character == '\n')) {
            return "application/octet-stream";
        }
        return value;
    }

    private static boolean isInlineMedia(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.startsWith("image/") ||
                normalized.startsWith("audio/") ||
                normalized.startsWith("video/") ||
                normalized.startsWith("application/vnd.apple.mpegurl") ||
                normalized.startsWith("application/x-mpegurl");
    }

    private static boolean isPlaylist(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase();
        return normalized.startsWith("application/vnd.apple.mpegurl") ||
                normalized.startsWith("application/x-mpegurl") ||
                normalized.startsWith("audio/mpegurl");
    }

    private static void resolvePlaylistReference(
            URI base, String value, LinkedHashSet<String> references) {
        try {
            URI resolved = base.resolve(value);
            if ("http".equalsIgnoreCase(resolved.getScheme()) ||
                    "https".equalsIgnoreCase(resolved.getScheme())) {
                references.add(resolved.toString());
            }
        } catch (IllegalArgumentException ignored) {
            // It will be rewritten to a local missing-resource route.
        }
    }

    private static String localPlaylistReference(
            URI base, String value, Map<String, String> ids) {
        try {
            String resolved = base.resolve(value).toString();
            String id = ids.get(resolved);
            return id == null ? "/media/missing" : "/media/" + id;
        } catch (IllegalArgumentException e) {
            return "/media/missing";
        }
    }

    private static ByteRange parseRange(String value, long size) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0 || size < 1) {
            throw new IllegalArgumentException("Unsupported byte range");
        }
        String range = value.substring("bytes=".length());
        int dash = range.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("Invalid byte range");
        }
        try {
            long start;
            long end;
            if (dash == 0) {
                long suffix = Long.parseLong(range.substring(1));
                if (suffix < 1) {
                    throw new IllegalArgumentException("Invalid byte range");
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(range.substring(0, dash));
                end = dash == range.length() - 1
                        ? size - 1
                        : Long.parseLong(range.substring(dash + 1));
            }
            if (start < 0 || start >= size || end < start) {
                throw new IllegalArgumentException("Byte range outside resource");
            }
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid byte range");
        }
    }

    private static void copy(
            InputStream input, OutputStream output, long length)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(
                    buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Archived resource ended unexpectedly");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private record ByteRange(long start, long end) {
    }

    private record ResolvedPost(
            SearchHit display, SearchHit source, String quotedUrl) {
    }

    private record ThreadLookup(String platform, String site, String id) {
    }

    private static final class ResponseSentException extends RuntimeException {
    }
}
