package org.netpreserve.warcana.web;

import org.jsoup.nodes.Entities;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ManagementHtmlRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private ManagementHtmlRenderer() {
    }

    static String page(
            String accounts, ArchiveController.Status status, String csrf,
            int warcs, int activeJobs, String message) {
        var html = new StringBuilder("<!doctype html><html lang=en><head>")
                .append("<meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>Manage collection · Warcana</title>")
                .append("<link rel=stylesheet href=/app.css>");
        if (status.running()) {
            html.append("<meta http-equiv=refresh content=3>");
        }
        html.append("</head><body><header class=site-header>")
                .append("<a href=/><strong>Warcana</strong></a>")
                .append("<nav><a href=/>Browse</a><a href=/manage aria-current=page>Manage</a></nav>")
                .append("</header><main class=manage><div class=status-row>")
                .append(statusBadge(status)).append("</div>");
        if (message != null && !message.isBlank()) {
            html.append("<p class=notice>").append(escape(message)).append("</p>");
        }
        html.append("<section class=summary aria-label=\"Collection summary\">")
                .append(metric("Configured accounts", configuredCount(accounts)))
                .append(metric("Completed WARCs", warcs))
                .append(metric("Resumable jobs", activeJobs))
                .append("</section><div class=manage-grid><section class=panel><h2>Accounts</h2>")
                .append("<p class=hint>One target per line. Prefix ambiguous handles with <code>mastodon</code> or <code>bluesky</code>.</p>")
                .append("<form method=post action=/manage/accounts>")
                .append(token(csrf)).append("<label class=sr-only for=accounts>accounts.txt</label>")
                .append("<textarea id=accounts name=accounts spellcheck=false>")
                .append(escape(accounts)).append("</textarea><button class=primary>Save accounts</button></form></section>")
                .append("<section class=panel><h2>Capture</h2>")
                .append(runDetails(status));
        if (status.running()) {
            html.append("<form method=post action=/manage/stop>").append(token(csrf))
                    .append("<button class=danger>Stop capture</button></form>");
        } else {
            html.append("<form method=post action=/manage/run>").append(token(csrf))
                    .append("<button class=primary>Start capture</button></form>");
        }
        html.append("</section><section class=panel><h2>Schedule captures</h2><form method=post action=/manage/schedule class=schedule-form>")
                .append(token(csrf)).append(check("enabled", "Capture automatically", status.settings().enabled()))
                .append("<label>Repeat every <span><input name=intervalHours type=number min=1 max=8760 value=")
                .append(status.settings().intervalHours()).append("> hours</span></label>")
                .append(check("includeMedia", "Capture linked media", status.settings().includeMedia()))
                .append(check("includeVideo", "Include video", status.settings().includeVideo()))
                .append("<button>Save schedule</button></form>");
        if (status.nextRun() != null) {
            html.append("<p class=hint>Next run: ").append(TIME.format(status.nextRun())).append("</p>");
        }
        html.append("</section><section class=panel-log><header><h2>Capture log</h2><span>Refreshes while capturing</span></header><pre>");
        if (status.output().isEmpty()) {
            html.append("No captures started in this server session.");
        } else {
            for (String line : status.output()) {
                html.append(escape(line)).append('\n');
            }
        }
        return html.append("</pre></section></div></main></body></html>").toString();
    }

    private static String runDetails(ArchiveController.Status status) {
        if (status.running()) {
            return "<p>Started " + TIME.format(status.startedAt()) +
                    ". This page refreshes every three seconds.</p>";
        }
        if (status.finishedAt() != null) {
            return "<p>Last finished " + TIME.format(status.finishedAt()) +
                    " with exit code " + status.exitCode() + ".</p>";
        }
        return "<p>Capture all configured accounts into the collection now.</p>";
    }

    private static String statusBadge(ArchiveController.Status status) {
        String state = status.running() ? "Capturing" :
                status.exitCode() == null ? "Idle" :
                        status.exitCode() == 0 ? "Complete" : "Needs attention";
        return "<span class=run-status data-state=\"" +
                state.toLowerCase().replace(' ', '-') + "\"><i></i>" + state + "</span>";
    }

    private static String check(String name, String label, boolean checked) {
        return "<label class=check><input type=checkbox name=" + name +
                " value=true" + (checked ? " checked" : "") + "> " + label + "</label>";
    }

    private static String token(String csrf) {
        return "<input type=hidden name=csrf value=\"" + escape(csrf) + "\">";
    }

    private static String metric(String label, int value) {
        return "<div><strong>" + value + "</strong><span>" + label + "</span></div>";
    }

    private static int configuredCount(String accounts) {
        return (int) accounts.lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).count();
    }

    private static String escape(String value) {
        return Entities.escape(value == null ? "" : value);
    }
}
