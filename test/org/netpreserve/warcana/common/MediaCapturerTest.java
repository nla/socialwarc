package org.netpreserve.warcana.common;

import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.WarcWriter;

import java.nio.file.Files;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaCapturerTest {
    @Test
    void selectsOnlyHighestBandwidthHlsVariantByDefault() {
        URI master = URI.create("https://video.example/path/master.m3u8");
        String playlist = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Other",DEFAULT=NO,URI="audio/other.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="audio"
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,AUDIO="audio"
                medium.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=6200000,RESOLUTION=1920x1080,AUDIO="audio"
                high.m3u8
                #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=200000,URI="iframe.m3u8"
                #EXT-X-SESSION-KEY:METHOD=AES-128,URI="keys/session.key"
                """;

        var plan = MediaCapturer.planPlaylist(master, playlist, false);

        assertEquals(List.of(
                        URI.create("https://video.example/path/high.m3u8"),
                        URI.create("https://video.example/path/audio/en.m3u8"),
                        URI.create("https://video.example/path/keys/session.key")),
                plan.references());
        assertEquals(6_200_000, plan.selectedVariant().bandwidth());
        assertEquals("1920x1080", plan.selectedVariant().resolution());
    }

    @Test
    void capturesAllHlsVariantsWhenRequested() {
        URI master = URI.create("https://video.example/master.m3u8");
        String playlist = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4000000
                high.m3u8
                #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=200000,URI="iframe.m3u8"
                """;

        var plan = MediaCapturer.planPlaylist(master, playlist, true);

        assertEquals(List.of(
                        URI.create("https://video.example/low.m3u8"),
                        URI.create("https://video.example/high.m3u8"),
                        URI.create("https://video.example/iframe.m3u8")),
                plan.references());
    }

    @Test
    void capturesEverySegmentAndDependencyFromSelectedMediaPlaylist() {
        URI media = URI.create("https://video.example/high/index.m3u8");
        String playlist = """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="../keys/video.key"
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.0,
                segment-1.m4s
                #EXTINF:4.0,
                segment-2.m4s
                """;

        var plan = MediaCapturer.planPlaylist(media, playlist, false);

        assertEquals(List.of(
                        URI.create("https://video.example/keys/video.key"),
                        URI.create("https://video.example/high/init.mp4"),
                        URI.create("https://video.example/high/segment-1.m4s"),
                        URI.create("https://video.example/high/segment-2.m4s")),
                plan.references());
    }

    @Test
    void skipsNonHttpAndPrivateNetworkUrls() throws Exception {
        var warc = Files.createTempFile("warcana-media-", ".warc");
        try (var writer = new WarcWriter(warc)) {
            var capturer = new MediaCapturer(
                    writer, "warcana-test", 1024, 4096);

            capturer.captureAll(List.of(
                    "file:///etc/passwd",
                    "http://127.0.0.1/private",
                    "http://[::1]/private"));

            assertEquals(new MediaCaptureStats(0, 0, 3, 0), capturer.stats());
        } finally {
            Files.deleteIfExists(warc);
        }
    }
}
