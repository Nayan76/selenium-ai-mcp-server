package dev.seleniummcp.reporting;

import java.util.List;
import java.util.Map;

final class HtmlReport {
    private static final String HEAD = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'">
            <title>Selenium execution report</title><style>
            *{box-sizing:border-box}body{margin:0;background:#f5f7fa;color:#192838;font:16px/1.5 system-ui,sans-serif}
            main{max-width:1100px;margin:auto;padding:3rem 1.5rem}h1{margin:.25rem 0;font-size:2.2rem}
            header,section{background:white;border:1px solid #d5dee6;border-radius:8px;padding:1.25rem;margin-bottom:1rem}
            header{border-top:5px solid #305879}h2{font-size:1.2rem;margin:0 0 .75rem}.meta{color:#415569;overflow-wrap:anywhere}
            .stats{display:flex;gap:1rem;flex-wrap:wrap}.status{display:inline-block;font-weight:700;padding:.15rem .55rem;border-radius:4px}
            .PASS{background:#d8f2e1;color:#145832}.FAIL{background:#fce2d5;color:#873900}.ERROR{background:#f9dce2;color:#842237}
            table{width:100%;border-collapse:collapse;text-align:left}th,td{vertical-align:top;padding:.55rem;border-bottom:1px solid #dbe2e8}
            th{font-size:.85rem;color:#475e71}.table{overflow:auto}code{font-size:.86em}a{color:#154f89}
            </style></head><body><main>
            """;

    static String render(Map<String, Object> report) {
        if ("api_collection".equals(report.get("type"))) return renderCollection(report);
        if ("data_driven".equals(report.get("type"))) {
            return renderDataDriven(report);
        }
        var html = new StringBuilder(HEAD);
        var steps = (List<?>) report.get("steps");
        html.append("<header>Execution report<h1>").append(escape(report.get("id"))).append("</h1>")
                .append(badge(report.get("status"))).append("<p class=meta>Execution ID <code>")
                .append(escape(report.get("executionId"))).append("</code><br>Browser ").append(escape(report.get("browser")))
                .append("<br>Started ").append(escape(report.get("startedAt"))).append("<br>Ended ")
                .append(escape(report.get("endedAt"))).append("</p></header><section><h2>Summary</h2><div class=stats>");
        for (String status : List.of("PASS","FAIL","ERROR")) {
            long count = steps.stream().filter(row -> status.equals(((Map<?, ?>) row).get("status"))).count();
            html.append("<span>").append(status).append(" <b>").append(count).append("</b></span>");
        }
        html.append("<span>Executed ").append(steps.size()).append(" of ").append(escape(report.get("plannedSteps")))
                .append("</span><span>").append(escape(report.get("durationMs"))).append(" ms</span></div></section>")
                .append("<section class=table><h2>Steps</h2>");
        appendStepTable(html, steps);
        html.append("</section>");
        appendConsole(html, report);
        return html.append("</main></body></html>").toString();
    }

    private static String renderCollection(Map<String, Object> report) {
        var html = new StringBuilder(HEAD);
        html.append("<header><h1>").append(escape(report.get("collectionName"))).append("</h1>")
            .append(badge(report.get("status")));
        for (String key : List.of("executionId", "startedAt", "endedAt", "durationMs",
                "requestCount", "passed", "failed", "errors", "skipped")) {
            html.append("<p>").append(key).append(" <code>").append(escape(report.get(key))).append("</code></p>");
        }
        html.append("</header><section class=\"table\"><table><thead><tr><th>Request</th><th>Result</th>")
            .append("<th>HTTP status</th><th>Duration (ms)</th><th>Assertions</th></tr></thead><tbody>");
        for (Object item : (List<?>) report.get("requests")) {
            var row = (Map<?, ?>) item;
            html.append("<tr><td>").append(escape(row.get("method"))).append(" ").append(escape(row.get("name")))
                .append(" <code>").append(escape(row.get("id"))).append("</code></td><td>")
                .append(badge(row.get("status"))).append(" ").append(escape(row.get("code"))).append("</td><td>")
                .append(escape(row.get("statusCode"))).append("</td><td>").append(escape(row.get("durationMs")))
                .append("</td><td>");
            for (Object itemResult : (List<?>) row.get("assertions")) {
                var result = (Map<?, ?>) itemResult;
                html.append("<div>").append(escape(result.get("type"))).append(" ").append(badge(result.get("status")))
                    .append(" ").append(escape(result.get("code"))).append("</div>");
            }
            html.append("</td></tr>");
        }
        return html.append("</tbody></table></section></body></html>").toString();
    }

    private static String renderDataDriven(Map<String, Object> report) {
        var html = new StringBuilder(HEAD);
        var datasets = (List<?>) report.get("datasets");
        var summary = report.get("summary") instanceof Map<?, ?> map ? map : Map.of();
        html.append("<header>Data-driven execution report<h1>").append(escape(report.get("scenarioName"))).append("</h1>")
                .append(badge(report.get("status"))).append("<p class=meta>Execution ID <code>")
                .append(escape(report.get("executionId"))).append("</code><br>Browser ").append(escape(report.get("browser")))
                .append("<br>Stop on failure ").append(escape(report.get("stopOnFailure")))
                .append("<br>Started ").append(escape(report.get("startedAt"))).append("<br>Ended ")
                .append(escape(report.get("endedAt"))).append("</p></header><section><h2>Aggregate Summary</h2><div class=stats>")
                .append("<span>PASS <b>").append(escape(summary.get("passedDatasets"))).append("</b></span>")
                .append("<span>FAIL <b>").append(escape(summary.get("failedDatasets"))).append("</b></span>")
                .append("<span>ERROR <b>").append(escape(summary.get("errorDatasets"))).append("</b></span>")
                .append("<span>Skipped <b>").append(escape(summary.get("skippedDatasets"))).append("</b></span>")
                .append("<span>Datasets ").append(escape(report.get("executedDatasets"))).append(" of ")
                .append(escape(report.get("plannedDatasets"))).append("</span>")
                .append("<span>Steps ").append(escape(report.get("executedSteps"))).append(" of ")
                .append(escape(report.get("plannedTotalSteps"))).append("</span>")
                .append("<span>").append(escape(report.get("durationMs"))).append(" ms</span></div></section>")
                .append("<section class=table><h2>Datasets</h2><table><thead><tr><th>#<th>Dataset ID<th>Status<th>Category<th>Steps<th>Duration</tr></thead><tbody>");
        for (Object item : datasets) {
            var ds = (Map<?, ?>) item;
            html.append("<tr><td>").append(escape(ds.get("datasetIndex")))
                    .append("<td><code>").append(escape(ds.get("datasetId"))).append("</code>")
                    .append("<td>").append(badge(ds.get("status")))
                    .append("<td>").append(escape(ds.get("failureCategory")))
                    .append("<td>").append(escape(ds.get("executedSteps"))).append(" / ").append(escape(ds.get("plannedSteps")))
                    .append(" (P:").append(escape(ds.get("passedSteps")))
                    .append(" F:").append(escape(ds.get("failedSteps")))
                    .append(" E:").append(escape(ds.get("errorSteps"))).append(")")
                    .append("<td>").append(escape(ds.get("durationMs"))).append(" ms</tr>");
        }
        html.append("</tbody></table></section>");
        for (Object item : datasets) {
            var ds = (Map<?, ?>) item;
            html.append("<section class=table><h2>Dataset ").append(escape(ds.get("datasetIndex")))
                    .append(": <code>").append(escape(ds.get("datasetId"))).append("</code> ")
                    .append(badge(ds.get("status"))).append("</h2><p class=meta>Duration ")
                    .append(escape(ds.get("durationMs"))).append(" ms");
            if (ds.get("failureCategory") != null) {
                html.append(" · Category ").append(escape(ds.get("failureCategory")));
            }
            if (ds.get("message") != null) {
                html.append(" · ").append(escape(ds.get("code"))).append(" ").append(escape(ds.get("message")));
            }
            html.append("</p>");
            if (ds.get("steps") instanceof List<?> dsSteps && !dsSteps.isEmpty()) {
                appendStepTable(html, dsSteps);
            }
            html.append("</section>");
        }
        appendConsole(html, report);
        return html.append("</main></body></html>").toString();
    }

    private static void appendStepTable(StringBuilder html, List<?> steps) {
        html.append("<table><thead><tr><th>Step<th>Tool<th>Status<th>Duration<th>Diagnostics<th>Evidence</tr></thead><tbody>");
        for (Object item : steps) {
            var row = (Map<?, ?>) item;
            html.append("<tr><td>").append(escape(row.get("step"))).append("<td><code>").append(escape(row.get("tool")))
                    .append("</code><td>").append(badge(row.get("status"))).append("<td>").append(escape(row.get("durationMs")))
                    .append(" ms<td>").append(escape(row.get("code"))).append(" ").append(escape(row.get("message")));
            if (row.containsKey("expected")) html.append("<br>Expected ").append(escape(row.get("expected")))
                    .append(" · Actual ").append(escape(row.get("actual")));
            if (row.containsKey("actualFormat")) {
                html.append("<br>Format ").append(escape(row.get("actualFormat")));
                if (row.containsKey("expectedFormat")) {
                    html.append(" (expected ").append(escape(row.get("expectedFormat"))).append(")");
                }
            }
            html.append("<td>");
            if (row.get("evidence") instanceof Map<?, ?> evidence) {
                String path = (String) evidence.get("path");
                String basename = path.substring(path.lastIndexOf('/') + 1);
                if (basename.matches("[0-9a-f-]{36}-(?:d(?:[1-9]|1[0-9]|20)-)?(?:[1-9]|[12][0-9]|3[0-2])\\.png")) {
                    html.append("<a href=\"").append(escape(basename)).append("\">Screenshot</a><br>")
                        .append(escape(evidence.get("timestamp")));
                }
            } else html.append(escape(row.get("screenshotStatus")));
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    private static void appendConsole(StringBuilder html, Map<String, Object> report) {
        if (report.get("console") instanceof Map<?, ?> console) {
            html.append("<section><h2>Browser console</h2>").append(escape(console.get("status")));
            if (console.get("entries") instanceof List<?> entries) {
                for (Object entry : entries) {
                    var log = (Map<?, ?>) entry;
                    html.append("<div>").append(escape(log.get("timestamp"))).append(" · ")
                            .append(escape(log.get("level"))).append(" · ").append(escape(log.get("message"))).append("</div>");
                }
            }
            html.append("</section>");
        }
    }

    private static String badge(Object value) {
        String status = String.valueOf(value);
        String style = List.of("PASS","FAIL","ERROR").contains(status) ? status : "";
        return "<span class=\"status " + style + "\">" + escape(status) + "</span>";
    }

    private static String escape(Object value) {
        if (value == null) return "";
        return value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }
}
