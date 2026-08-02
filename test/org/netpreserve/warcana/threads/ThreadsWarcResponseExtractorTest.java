package org.netpreserve.warcana.threads;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadsWarcResponseExtractorTest {
    private static final String OLD_FORMAT_BROTLI =
            "G6sCABwHbixnylsxXg9CmivlR5eyUNDnWAHngOC/Wbr2+qYOS99P9/MHsN27" +
            "H93UvgKZ8AQKBzazmeZtfEyGem2vvTOI4Mw3BS+Y0Dtn7cEWF5ukGY7o/whsY" +
            "PlhB6zxaZbmSeaNM4VkYgoQ6TsoJZ9OM39d86QMkD3TyR2SJcb5UWZZACjO40" +
            "wyb3MZdsFRBPUJmN0219PqakgIAP/oeTPTx3UPfii1mc2PEV3lmNIgm2Q0qcS" +
            "PemxAYz+/fwKYwlEmrAUda5QHlEvUOk0wASVXldSicci1GlfnypSwYGkr+r5F6" +
            "pIA9n6CiE3/OxrERrrJNZywjQ5nB3I9SiL/QRGS4rAgXMAvajqjTs5nJSQCgnU" +
            "k6WWIrcFGJGBzsyvoW2ZWfD8p4VjPQEPOo3Vd4JXmsdhpoMITs/C/eyRs5jS6/" +
            "z9JAA==";

    @Test
    void extractsEmbeddedProfilePostsAndThreadContext() throws Exception {
        String html = """
                <!doctype html><html><body>
                <script type="application/json" data-sjs>
                {"payload":{"thread_items":[
                  {"post":{
                    "id":"100_user-1","pk":"100","code":"ROOT",
                    "taken_at":1784089418,
                    "user":{"id":"user-1","username":"alice","full_name":"Alice Example","profile_pic_url":"https://cdn.example/avatar.jpg"},
                    "caption":{"text":"Hello world"},
                    "like_count":7,
                    "image_versions2":{"candidates":[{"url":"https://cdn.example/image.jpg","width":1200,"height":800}]},
                    "text_post_app_info":{"is_reply":false,"direct_reply_count":1,"repost_count":2,"quote_count":3,
                      "text_fragments":{"fragments":[{"fragment_type":"plaintext","plaintext":"Hello world"}]},
                      "share_info":{"quoted_post":null,"reposted_post":null}}
                  }},
                  {"post":{
                    "id":"101_user-1","pk":"101","code":"REPLY",
                    "taken_at":1784089478,
                    "user":{"id":"user-1","username":"alice","full_name":"Alice Example"},
                    "caption":{"text":"Hi @bob"},
                    "like_count":1,
                    "text_post_app_info":{"is_reply":true,"direct_reply_count":0,"repost_count":0,"quote_count":0,
                      "reply_to_author":{"id":"user-1","username":"alice"},
                      "text_fragments":{"fragments":[
                        {"fragment_type":"plaintext","plaintext":"Hi "},
                        {"fragment_type":"mention","plaintext":"@bob","mention_fragment":{"mentioned_user":{"id":"user-2","username":"bob"}}}
                      ]},"share_info":{"quoted_post":null,"reposted_post":null}}
                  }}
                ],"id":"100"}}
                </script></body></html>
                """;
        var response = response(
                "https://www.threads.com/@alice",
                html,
                200);
        var extractor = new ThreadsWarcResponseExtractor();

        assertTrue(extractor.supports(response));
        var extracted = extractor.extract(response);

        assertEquals(2, extracted.size());
        var root = extracted.get(0).post();
        assertEquals("https://www.threads.com/@alice/post/ROOT", root.url());
        assertEquals("alice", root.author().username());
        assertEquals("Alice Example", root.author().displayName());
        assertEquals("Threads", root.site().name());
        assertEquals("Hello world", root.content());
        assertEquals(7, root.likeCount());
        assertEquals(1, root.attachments().size());
        assertEquals("image", root.attachments().getFirst().type());
        assertNull(root.thread());

        var reply = extracted.get(1).post();
        assertEquals("alice", reply.to());
        assertEquals(java.util.List.of("bob"), reply.mentions());
        assertEquals(
                "Hi <a href=\"https://www.threads.com/@bob\">@bob</a>",
                reply.content());
        assertEquals("100", reply.thread().root().id());
        assertEquals("101", reply.id());
        assertEquals("100", reply.thread().parent().id());
    }

    @Test
    void ignoresRedirectsAndNonProfileThreadsResources() throws Exception {
        var extractor = new ThreadsWarcResponseExtractor();
        var redirect = response(
                "https://www.threads.net/@alice", "", 301);
        var asset = response(
                "https://www.threads.com/data/manifest.json", "{}", 200);

        assertTrue(extractor.supports(redirect));
        assertTrue(extractor.extract(redirect).isEmpty());
        assertFalse(extractor.supports(asset));
    }

    @Test
    void extractsBrotliEncodedOlderProfileFormat() throws Exception {
        var http = new HttpResponse.Builder(200, "OK")
                .addHeader("Content-Encoding", "br")
                .body(MediaType.HTML, Base64.getDecoder().decode(
                        OLD_FORMAT_BROTLI))
                .build();
        var response = new WarcResponse.Builder(
                "https://www.threads.net/@csirogram")
                .body(http)
                .build();

        var extracted = new ThreadsWarcResponseExtractor().extract(response);

        assertEquals(1, extracted.size());
        var post = extracted.getFirst().post();
        assertEquals(
                "https://www.threads.com/@csirogram/post/C0yXU8wr80J",
                post.url());
        assertEquals("Old format post", post.content());
        assertEquals(688, post.likeCount());
        assertEquals(11, post.replyCount());
        assertNull(post.repostCount());
        assertEquals("video", post.attachments().getFirst().type());
        assertEquals(
                "https://cdn.example/poster.jpg",
                post.attachments().getFirst().thumbnailUrl());
    }

    private static WarcResponse response(
            String target, String body, int status) throws java.io.IOException {
        var http = new HttpResponse.Builder(status, "status")
                .body(MediaType.HTML, body.getBytes(StandardCharsets.UTF_8))
                .build();
        return new WarcResponse.Builder(target).body(http).build();
    }
}
