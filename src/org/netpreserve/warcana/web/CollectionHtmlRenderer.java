package org.netpreserve.warcana.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.netpreserve.warcana.common.model.Account;
import org.netpreserve.warcana.common.model.Attachment;
import org.netpreserve.warcana.common.model.Post;
import org.netpreserve.warcana.common.search.SearchHit;
import org.netpreserve.warcana.common.search.SearchTimeline;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CollectionHtmlRenderer {
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Safelist POST_HTML = Safelist.basic()
            .addTags("span")
            .addAttributes("a", "class")
            .addProtocols("a", "href", "http", "https");
    private CollectionHtmlRenderer() {
    }

    static String index(
            Map<String, String> query,
            long totalHits,
            List<SearchHit> hits,
            SearchTimeline timeline,
            Map<String, String> resources,
            Map<String, SearchHit> threadTargets) {
        StringBuilder html = start("Warcana", false);
        html.append(header())
                .append("<main><form class=search method=get action=/>")
                .append("<label for=q>Search posts</label>")
                .append("<div class=search-row><input id=q name=q type=search value=\"")
                .append(escape(query.getOrDefault("q", "")))
                .append("\" autofocus><button>Search</button></div>");

        boolean filtered = present(query, "platform") || present(query, "author") ||
                present(query, "since") || present(query, "until") ||
                present(query, "sort") || "true".equals(query.get("hasMedia"));
        String selectedSort = query.get("sort");
        if (selectedSort == null || selectedSort.isBlank()) {
            selectedSort = query.getOrDefault("q", "").isBlank()
                    ? "newest"
                    : "relevance";
        }
        html.append("<details class=filters><summary>Filters")
                .append(filtered
                        ? " <span class=filter-summary>" +
                                escape(appliedFilterSummary(query)) + "</span>"
                        : "")
                .append("</summary><div class=filter-grid>")
                .append("<label>Platform <select name=platform>")
                .append(option("", "All", query.get("platform")))
                .append(option("mastodon", "Mastodon", query.get("platform")))
                .append(option("bluesky", "Bluesky", query.get("platform")))
                .append("</select></label>")
                .append("<label>Sort <select name=sort>")
                .append(option("relevance", "Relevance", selectedSort))
                .append(option("newest", "Newest first", selectedSort))
                .append(option("oldest", "Oldest first", selectedSort))
                .append("</select></label>")
                .append("<label class=author-filter>Author <input name=author value=\"")
                .append(escape(query.getOrDefault("author", "")))
                .append("\"></label>")
                .append("<div class=date-filter role=group aria-label=\"Date range\">")
                .append("<label>From <input id=since name=since type=date value=\"")
                .append(escape(query.getOrDefault("since", "")))
                .append("\"></label>")
                .append("<label>Until <input id=until name=until type=date value=\"")
                .append(escape(query.getOrDefault("until", "")))
                .append("\"></label></div>")
                .append("<div class=filter-footer><label class=checkbox-filter>")
                .append("<input name=hasMedia type=checkbox value=true")
                .append("true".equals(query.get("hasMedia")) ? " checked" : "")
                .append("> <span>Has media</span></label>")
                .append("<div class=filter-actions><button>Apply</button>")
                .append("<a href=/>Clear</a></div></div>")
                .append("</div></details></form>");

        String text = query.getOrDefault("q", "");
        String author = query.getOrDefault("author", "");
        html.append(timeline(timeline, query))
                .append("<section class=results><header><h1>")
                .append(!text.isBlank()
                        ? "Results for “" + escape(text) + "”"
                        : !author.isBlank()
                        ? "Posts by @" + escape(author.replaceFirst("^@", ""))
                        : "Recent posts")
                .append("</h1><span>")
                .append(totalHits)
                .append(totalHits == 1 ? " post" : " posts")
                .append("</span></header>");
        if (hits.isEmpty()) {
            html.append("<p>No results.</p>");
        } else {
            html.append("<ol class=posts>");
            for (SearchHit hit : hits) {
                html.append("<li>")
                        .append(post(
                                hit, resources, threadTargets,
                                false, !text.isBlank(), null))
                        .append("</li>");
            }
            html.append("</ol>");
        }
        html.append(pagination(query, totalHits, hits.size()))
                .append("</section></main>")
                .append("<script src=/timeline.js defer></script>")
                .append("</body></html>");
        return html.toString();
    }

    private static String timeline(
            SearchTimeline timeline, Map<String, String> query) {
        if (timeline == null || timeline.buckets().isEmpty()) {
            return "";
        }
        int count = timeline.buckets().size();
        long maximum = timeline.buckets().stream()
                .mapToLong(SearchTimeline.Bucket::count)
                .max().orElse(1);
        double bucketWidth = 600.0 / count;
        StringBuilder html = new StringBuilder("<section class=timeline data-start=\"")
                .append(timeline.start())
                .append("\" data-end=\"")
                .append(timeline.end())
                .append("\"><h2>Posts over time</h2>")
                .append("<svg viewBox=\"0 0 600 80\" preserveAspectRatio=none ")
                .append("role=img aria-label=\"Post dates. Drag to select a range.\">")
                .append("<g class=timeline-bars>");
        for (int i = 0; i < count; i++) {
            SearchTimeline.Bucket bucket = timeline.buckets().get(i);
            double height = bucket.count() == 0
                    ? 0
                    : Math.max(1, 70 * Math.sqrt((double) bucket.count() / maximum));
            html.append("<rect class=timeline-bar x=\"")
                    .append(decimal(i * bucketWidth))
                    .append("\" y=\"")
                    .append(decimal(70 - height))
                    .append("\" width=\"")
                    .append(decimal(Math.max(.5, bucketWidth - .5)))
                    .append("\" height=\"")
                    .append(decimal(height))
                    .append("\" data-start=\"")
                    .append(bucket.start())
                    .append("\" data-end=\"")
                    .append(bucket.end())
                    .append("\"><title>")
                    .append(bucket.start())
                    .append(" – ")
                    .append(bucket.end())
                    .append(": ")
                    .append(bucket.count())
                    .append(bucket.count() == 1 ? " post" : " posts")
                    .append("</title></rect>");
        }
        String since = query.getOrDefault("since", "");
        String until = query.getOrDefault("until", "");
        return html.append("</g><rect class=timeline-selection hidden y=0 height=70>")
                .append("</rect><rect class=timeline-target x=0 y=0 width=600 height=70>")
                .append("</rect></svg><div class=timeline-caption><span>")
                .append(timeline.start())
                .append(" – ")
                .append(timeline.end())
                .append("</span><output for=\"since until\">")
                .append(since.isBlank() && until.isBlank()
                        ? "All dates"
                        : escape((since.isBlank() ? timeline.start().toString() : since) +
                                " – " +
                                (until.isBlank() ? timeline.end().toString() : until)))
                .append("</output><button type=button class=timeline-clear")
                .append(since.isBlank() && until.isBlank() ? " hidden" : "")
                .append(">Clear dates</button></div></section>")
                .toString();
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String post(
            SearchHit hit,
            List<SearchHit> thread,
            Map<String, String> resources,
            Map<String, SearchHit> threadTargets,
            String quotedUrl) {
        StringBuilder html = start("Post — Warcana", true);
        html.append(header())
                .append("<main><p><a id=back-link href=/>← Back</a></p>")
                .append("<section class=thread aria-label=Thread>");
        for (SearchHit threadHit : thread) {
            boolean selected = threadHit == hit;
            html.append(post(
                    threadHit, resources, threadTargets,
                    selected, false, selected ? quotedUrl : null));
        }
        html.append("</section></main></body></html>");
        return html.toString();
    }

    private static String post(
            SearchHit hit,
            Map<String, String> resources,
            Map<String, SearchHit> threadTargets,
            boolean detail,
            boolean showSnippet,
            String quotedUrl) {
        Post envelope = hit.post();
        Post displayed = envelope.repost() == null ? envelope : envelope.repost();
        StringBuilder html = new StringBuilder(detail
                ? "<article id=current-post class=\"post current-post\" aria-current=true>"
                : "<article class=post>");
        if (envelope.repost() != null) {
            html.append("<p class=repost-note>")
                    .append(miniAvatar(envelope.author(), resources))
                    .append(escape(authorName(envelope.author())))
                    .append(" reposted</p>");
        }
        html.append("<div class=post-grid>")
                .append(avatar(displayed.author(), resources))
                .append("<div class=post-main><header class=post-header>")
                .append("<strong>")
                .append(escape(authorName(displayed.author())))
                .append("</strong>");
        if (displayed.author() != null && displayed.author().username() != null) {
            String handle = displayed.author().username().replaceFirst("^@", "");
            html.append(" <a class=username href=\"")
                    .append(authorUrl(hit.platform(), handle))
                    .append("\">@")
                    .append(escape(handle))
                    .append("</a>");
        }
        html.append(postDates(
                        displayed.createdAt(), displayed.editedAt(), postUrl(hit)))
                .append("</header>")
                .append(replyContext(
                        displayed, hit.platform(), threadTargets))
                .append("<div class=content>")
                .append(highlightedPostHtml(
                        displayed.content(),
                        showSnippet ? hit.snippet() : null))
                .append("</div>");
        html.append(attachments(displayed.attachments(), resources));
        if (displayed.quotedPost() != null) {
            html.append(quotedPost(
                    displayed.quotedPost(), hit, resources, threadTargets,
                    showSnippet ? hit.snippet() : null));
        }
        html.append(statistics(displayed));
        if (detail) {
            html.append("<p class=post-links>");
            if (displayed.url() != null && isHttpUrl(displayed.url())) {
                html.append("<a href=\"")
                        .append(escape(displayed.url()))
                        .append("\" rel=noreferrer>Original URL</a> · ");
            }
            html.append("<a href=\"")
                    .append(postJsonUrl(hit, quotedUrl, "common"))
                    .append("\">Common JSON</a> · <a href=\"")
                    .append(postJsonUrl(hit, quotedUrl, "original"))
                    .append("\">Original JSON</a></p>");
            html.append("<details class=provenance><summary>Archive details</summary><dl>")
                    .append(term("Source response", hit.sourceTarget()))
                    .append(term("WARC-Date", hit.warcDate() == null
                            ? null
                            : hit.warcDate().toString()))
                    .append(term("WARC file", hit.sourceWarc()))
                    .append(term("WARC record", hit.warcRecordId()))
                    .append(term("ID", hit.platform() + ":" + hit.identity()))
                    .append("</dl></details>");
        }
        html.append("</div></div></article>");
        return html.toString();
    }

    private static String avatar(Account author, Map<String, String> resources) {
        String url = author == null ? null : author.avatarUrl();
        String id = url == null ? null : resources.get(url);
        if (id != null) {
            return "<figure class=avatar><img src=/media/" + id +
                    " alt=\"\" loading=lazy></figure>";
        }
        String name = authorName(author);
        String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
        return "<figure class=avatar aria-hidden=true>" + escape(initial) + "</figure>";
    }

    private static String miniAvatar(
            Account author, Map<String, String> resources) {
        String url = author == null ? null : author.avatarUrl();
        String id = url == null ? null : resources.get(url);
        if (id != null) {
            return "<img class=mini-avatar src=/media/" + id + " alt=\"\">";
        }
        String name = authorName(author);
        String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
        return "<span class=mini-avatar aria-hidden=true>" +
                escape(initial) + "</span>";
    }

    private static String attachments(
            List<Attachment> attachments, Map<String, String> resources) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        long imageCount = attachments.stream()
                .filter(attachment -> isRenderableImage(attachment, resources))
                .count();
        StringBuilder html = new StringBuilder(imageCount > 1
                ? "<div class=\"media image-grid\">"
                : "<div class=media>");
        for (Attachment attachment : attachments) {
            String type = attachment.type() == null ? "" : attachment.type().toLowerCase();
            if ("link".equals(type) && isHttpUrl(attachment.url())) {
                html.append(linkCard(attachment, resources));
                continue;
            }
            MediaCandidate candidate = mediaCandidate(attachment, resources);
            if (candidate != null && isVideo(type, candidate.url())) {
                html.append("<video controls preload=metadata src=/media/")
                        .append(candidate.id())
                        .append("></video>");
            } else if (candidate != null) {
                html.append("<a class=media-image href=/media/")
                        .append(candidate.id())
                        .append("><img src=/media/")
                        .append(candidate.id())
                        .append(" alt=\"")
                        .append(escape(attachment.altText() == null ? "" : attachment.altText()))
                        .append("\" loading=lazy></a>");
            } else {
                html.append("<p class=missing-media>Media not captured</p>");
            }
        }
        return html.append("</div>").toString();
    }

    private static boolean isRenderableImage(
            Attachment attachment, Map<String, String> resources) {
        if (attachment == null ||
                "link".equalsIgnoreCase(attachment.type())) {
            return false;
        }
        MediaCandidate candidate = mediaCandidate(attachment, resources);
        String type = attachment.type() == null
                ? ""
                : attachment.type().toLowerCase();
        return candidate != null && !isVideo(type, candidate.url());
    }

    private static String linkCard(
            Attachment attachment, Map<String, String> resources) {
        String thumbnailId = attachment.thumbnailUrl() == null
                ? null
                : resources.get(attachment.thumbnailUrl());
        String title = attachment.title() == null || attachment.title().isBlank()
                ? attachment.url()
                : attachment.title();
        StringBuilder html = new StringBuilder("<a class=\"link-card")
                .append(thumbnailId == null ? "" : " has-thumbnail")
                .append("\" href=\"")
                .append(escape(attachment.url()))
                .append("\" rel=noreferrer>");
        if (thumbnailId != null) {
            html.append("<img src=/media/")
                    .append(thumbnailId)
                    .append(" alt=\"")
                    .append(escape(attachment.altText() == null
                            ? ""
                            : attachment.altText()))
                    .append("\" loading=lazy>");
        }
        html.append("<span class=link-card-body><strong>")
                .append(escape(title))
                .append("</strong>");
        if (attachment.description() != null &&
                !attachment.description().isBlank()) {
            html.append("<span>")
                    .append(escape(attachment.description()))
                    .append("</span>");
        }
        html.append("<small>")
                .append(escape(attachment.url()))
                .append("</small></span></a>");
        return html.toString();
    }

    private static String postDates(
            java.time.Instant createdAt,
            java.time.Instant editedAt,
            String postUrl) {
        if (createdAt == null && editedAt == null) {
            return "";
        }
        StringBuilder html = new StringBuilder("<span class=post-dates>");
        if (createdAt != null) {
            if (postUrl != null) {
                html.append("<a class=date-link href=\"")
                        .append(postUrl)
                        .append("\">");
            }
            html.append("<time datetime=\"")
                    .append(createdAt)
                    .append("\">")
                    .append(DATE_FORMAT.format(createdAt))
                    .append("</time>");
            if (postUrl != null) {
                html.append("</a>");
            }
        }
        if (editedAt != null) {
            html.append("<span class=edited>edited <time datetime=\"")
                    .append(editedAt)
                    .append("\">")
                    .append(DATE_FORMAT.format(editedAt))
                    .append("</time></span>");
        }
        return html.append("</span>").toString();
    }

    private static String replyContext(
            Post post,
            String platform,
            Map<String, SearchHit> threadTargets) {
        if (post.thread() == null || post.thread().parent() == null) {
            return "";
        }
        Post.PostReference parent = post.thread().parent();
        StringBuilder html = new StringBuilder("<p class=reply-context>Replying");
        if (parent.author() != null &&
                parent.author().username() != null &&
                !parent.author().username().isBlank()) {
            String handle = parent.author().username().replaceFirst("^@", "");
            html.append(" to <a class=username href=\"")
                    .append(authorUrl(platform, handle))
                    .append("\">@")
                    .append(escape(handle))
                    .append("</a>");
        }
        appendThreadLink(
                html, post, platform, parent,
                "Parent post", "Original parent", threadTargets);

        Post.PostReference root = post.thread().root();
        if (root != null && !root.id().equals(parent.id())) {
            appendThreadLink(
                    html, post, platform, root,
                    "Thread root", "Original root", threadTargets);
        }
        return html.append("</p>").toString();
    }

    private static void appendThreadLink(
            StringBuilder html,
            Post post,
            String platform,
            Post.PostReference reference,
            String archivedLabel,
            String originalLabel,
            Map<String, SearchHit> threadTargets) {
        String site = post.site() == null ? null : post.site().name();
        SearchHit target = threadTargets.get(
                threadKey(platform, site, reference.id()));
        if (target != null) {
            html.append(" · <a href=\"")
                    .append(postUrl(target))
                    .append("\">")
                    .append(archivedLabel)
                    .append("</a>");
        } else if (isHttpUrl(reference.url())) {
            html.append(" · <a href=\"")
                    .append(escape(reference.url()))
                    .append("\" rel=noreferrer>")
                    .append(originalLabel)
                    .append("</a>");
        }
    }

    static String threadKey(String platform, String site, String id) {
        return String.join("\n",
                platform == null ? "" : platform.toLowerCase(),
                site == null ? "" : site.toLowerCase(),
                id == null ? "" : id);
    }

    private static String quotedPost(
            Post post,
            SearchHit parent,
            Map<String, String> resources,
            Map<String, SearchHit> threadTargets,
            String snippet) {
        StringBuilder html = new StringBuilder("<blockquote class=quote><header>")
                .append(miniAvatar(post.author(), resources))
                .append("<strong>")
                .append(escape(authorName(post.author())))
                .append("</strong>");
        if (post.author() != null && post.author().username() != null) {
            String handle = post.author().username().replaceFirst("^@", "");
            html.append(" <a class=username href=\"")
                    .append(authorUrl(parent.platform(), handle))
                    .append("\">@")
                    .append(escape(handle))
                    .append("</a>");
        }
        String dateUrl = post.url() == null
                ? null
                : quotedPostUrl(parent, post.url());
        html.append(postDates(post.createdAt(), post.editedAt(), dateUrl))
                .append("</header>")
                .append(replyContext(
                        post, parent.platform(), threadTargets))
                .append("<div class=content>")
                .append(highlightedPostHtml(post.content(), snippet))
                .append("</div>")
                .append(attachments(post.attachments(), resources))
                .append("</blockquote>");
        return html.toString();
    }

    private static String statistics(Post post) {
        List<String> values = new ArrayList<>();
        addStatistic(values, post.replyCount(), "replies");
        addStatistic(values, post.repostCount(), "reposts");
        addStatistic(values, post.quoteCount(), "quotes");
        addStatistic(values, post.likeCount(), "likes");
        return values.isEmpty()
                ? ""
                : "<p class=statistics>" + String.join(" · ", values) + "</p>";
    }

    private static void addStatistic(List<String> values, Long count, String name) {
        if (count != null) {
            values.add(count + " " + name);
        }
    }

    private static MediaCandidate mediaCandidate(
            Attachment attachment, Map<String, String> resources) {
        if (attachment.url() != null && resources.containsKey(attachment.url())) {
            return new MediaCandidate(attachment.url(), resources.get(attachment.url()));
        }
        if (attachment.sources() != null) {
            for (Attachment.Source source : attachment.sources()) {
                if (source.url() != null && resources.containsKey(source.url())) {
                    return new MediaCandidate(source.url(), resources.get(source.url()));
                }
            }
        }
        return null;
    }

    private static String pagination(
            Map<String, String> query, long totalHits, int resultCount) {
        int offset = parseInt(query.get("offset"), 0);
        if (offset == 0 && offset + resultCount >= totalHits) {
            return "";
        }
        StringBuilder html = new StringBuilder("<nav class=pagination aria-label=Pagination>");
        if (offset > 0) {
            html.append("<a href=\"")
                    .append(urlWithOffset(query, Math.max(0, offset - PAGE_SIZE)))
                    .append("\">Previous</a>");
        }
        html.append("<span>Page ")
                .append(offset / PAGE_SIZE + 1)
                .append(" of ")
                .append(Math.max(1, (totalHits + PAGE_SIZE - 1) / PAGE_SIZE))
                .append("</span>");
        if (offset + resultCount < totalHits) {
            html.append("<a href=\"")
                    .append(urlWithOffset(query, offset + PAGE_SIZE))
                    .append("\">Next</a>");
        }
        return html.append("</nav>").toString();
    }

    private static String urlWithOffset(Map<String, String> query, int offset) {
        Map<String, String> values = new LinkedHashMap<>(query);
        values.remove("limit");
        if (offset == 0) {
            values.remove("offset");
        } else {
            values.put("offset", Integer.toString(offset));
        }
        return "/?" + queryString(values);
    }

    private static String authorUrl(String platform, String handle) {
        return "/?platform=" + encode(platform) + "&amp;author=" + encode(handle);
    }

    private static String postUrl(SearchHit hit) {
        return "/post?platform=" + encode(hit.platform()) +
                "&amp;identity=" + encode(hit.identity());
    }

    private static String postJsonUrl(
            SearchHit hit, String quotedUrl, String view) {
        String url = "/post.json?platform=" + encode(hit.platform()) +
                "&amp;identity=" + encode(hit.identity());
        if (quotedUrl != null && !quotedUrl.isBlank()) {
            url += "&amp;quoted=" + encode(quotedUrl);
        }
        return url + "&amp;view=" + encode(view);
    }

    private static String quotedPostUrl(SearchHit parent, String url) {
        return postUrl(parent) + "&amp;quoted=" + encode(url);
    }

    private static String queryString(Map<String, String> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                pairs.add(encode(key) + "=" + encode(value));
            }
        });
        return String.join("&amp;", pairs);
    }

    private static String option(String value, String label, String selected) {
        return "<option value=\"" + escape(value) + "\"" +
                (value.equals(selected) ? " selected" : "") + ">" +
                escape(label) + "</option>";
    }

    private static String term(String name, String value) {
        return "<dt>" + escape(name) + "</dt><dd>" +
                escape(value == null ? "unknown" : value) + "</dd>";
    }

    private static String cleanPostHtml(String value) {
        return value == null || value.isBlank()
                ? ""
                : Jsoup.clean(value, "", POST_HTML, new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
    }

    private static String highlightedPostHtml(String value, String snippet) {
        String cleaned = cleanPostHtml(value);
        if (cleaned.isEmpty() || snippet == null || snippet.isBlank()) {
            return cleaned;
        }
        List<String> matches = Jsoup.parseBodyFragment(snippet)
                .select("b")
                .eachText()
                .stream()
                .filter(text -> !text.isBlank())
                .distinct()
                .sorted((left, right) -> Integer.compare(
                        right.length(), left.length()))
                .toList();
        if (matches.isEmpty()) {
            return cleaned;
        }
        Pattern pattern = Pattern.compile(
                matches.stream()
                        .map(Pattern::quote)
                        .reduce((left, right) -> left + "|" + right)
                        .orElseThrow(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        var document = Jsoup.parseBodyFragment(cleaned);
        document.outputSettings().prettyPrint(false);
        for (TextNode textNode : allTextNodes(document.body())) {
            highlightTextNode(textNode, pattern);
        }
        return document.body().html();
    }

    private static List<TextNode> allTextNodes(Element root) {
        var textNodes = new ArrayList<TextNode>();
        for (Node node : root.childNodes()) {
            if (node instanceof TextNode textNode) {
                textNodes.add(textNode);
            } else if (node instanceof Element element) {
                textNodes.addAll(allTextNodes(element));
            }
        }
        return textNodes;
    }

    private static void highlightTextNode(TextNode textNode, Pattern pattern) {
        Matcher matcher = pattern.matcher(textNode.getWholeText());
        if (!matcher.find()) {
            return;
        }
        int end = 0;
        do {
            if (matcher.start() > end) {
                textNode.before(new TextNode(
                        textNode.getWholeText().substring(end, matcher.start())));
            }
            textNode.before(new Element("mark").text(matcher.group()));
            end = matcher.end();
        } while (matcher.find());
        if (end < textNode.getWholeText().length()) {
            textNode.before(new TextNode(textNode.getWholeText().substring(end)));
        }
        textNode.remove();
    }

    private static String authorName(Account author) {
        if (author == null) {
            return "Unknown author";
        }
        if (author.displayName() != null && !author.displayName().isBlank()) {
            return author.displayName();
        }
        return author.username() == null || author.username().isBlank()
                ? "Unknown author"
                : author.username().replaceFirst("^@", "");
    }

    private static boolean isVideo(String type, String url) {
        String lower = url.toLowerCase();
        return type.contains("video") || lower.matches(".*\\.(mp4|webm|mov)(?:\\?.*)?$");
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            String scheme = java.net.URI.create(value).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean present(Map<String, String> values, String key) {
        return values.get(key) != null && !values.get(key).isBlank();
    }

    private static String appliedFilterSummary(Map<String, String> query) {
        List<String> filters = new ArrayList<>();
        String platform = query.get("platform");
        if ("mastodon".equals(platform)) {
            filters.add("Mastodon");
        } else if ("bluesky".equals(platform)) {
            filters.add("Bluesky");
        }
        if (present(query, "author")) {
            filters.add("@" + query.get("author").replaceFirst("^@", ""));
        }
        String since = query.get("since");
        String until = query.get("until");
        if (present(query, "since") && present(query, "until")) {
            filters.add(since + "–" + until);
        } else if (present(query, "since")) {
            filters.add("from " + since);
        } else if (present(query, "until")) {
            filters.add("until " + until);
        }
        if ("true".equals(query.get("hasMedia"))) {
            filters.add("has media");
        }
        String sort = query.get("sort");
        if (present(query, "sort")) {
            filters.add(switch (sort) {
                case "newest" -> "newest first";
                case "oldest" -> "oldest first";
                default -> "relevance";
            });
        }
        return String.join(" · ", filters);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return Entities.escape(value == null ? "" : value);
    }

    private static StringBuilder start(String title, boolean historyScript) {
        StringBuilder html = new StringBuilder(
                "<!doctype html><html lang=en><head><meta charset=utf-8>")
                .append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(title)).append("</title>")
                .append("<link rel=stylesheet href=/app.css>");
        if (historyScript) {
            html.append("<script src=/history.js defer></script>");
        }
        return html.append("</head><body>");
    }

    static String empty() {
        return start("Warcana", false).append(header())
                .append("<main><section class=empty-state><h1>No archived posts yet</h1>")
                .append("<p>Add accounts and start the first capture from the management page.</p>")
                .append("<p><a class=button href=/manage>Manage collection</a></p>")
                .append("</section></main></body></html>").toString();
    }

    private static String header() {
        return "<header class=site-header><a href=/><strong>Warcana</strong></a>" +
                "<nav><a href=/>Browse</a><a href=/manage>Manage</a></nav></header>";
    }

    private record MediaCandidate(String url, String id) {
    }
}
