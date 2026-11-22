package dev.seleniummcp.api;

import dev.seleniummcp.support.ToolFailure;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class ApiClient implements AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_RESPONSE_HEADERS = 16 * 1024;
    private final Semaphore inFlight = new Semaphore(4);
    private HttpClient client;
    private boolean closed;

    private synchronized HttpClient httpClient() {
        if (closed) throw new ToolFailure("API_INTERRUPTED", "The API client is shutting down.");
        ApiRuntime.requireSafeParser();
        if (client != null) return client;
        String insecureTls = System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
        if ("".equals(insecureTls) || Boolean.parseBoolean(insecureTls)) {
            throw new ToolFailure("API_CONFIGURATION_INVALID", "API requests require TLS hostname verification.");
        }
        if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()) {
            throw new ToolFailure("API_CONFIGURATION_INVALID", "Disable JDK HTTP client logging for API requests.");
        }
        return client = HttpClient.newBuilder().proxy(ProxySelector.of(null))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    record Response(Integer statusCode, String media, byte[] body, ToolFailure failure) {}

    Response send(ApiRequestValidator.Request request) {
        return send(request, Long.MAX_VALUE);
    }

    Response send(ApiRequestValidator.Request request, long deadline) {
        if (!inFlight.tryAcquire()) return failed(null, "API_BUSY", "Too many concurrent API requests.");
        var receivedStatus = new AtomicInteger();
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            var http = httpClient();
            boolean collection = deadline != Long.MAX_VALUE;
            if (collection && deadline - System.nanoTime() <= 0) {
                return failed(null, "API_TIMEOUT", "The API request exceeded its deadline.");
            }
            exchange = http.sendAsync(request.httpRequest(), info -> {
                receivedStatus.set(info.statusCode());
                checkHeaders(info);
                return new LimitedBody();
            });
            long budget = request.httpRequest().timeout().orElseThrow().toNanos();
            if (collection) budget = Math.min(budget, Math.max(0, deadline - System.nanoTime()));
            var response = exchange.get(budget, TimeUnit.NANOSECONDS);
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            String media = contentType.equals("application/json") || contentType.matches("application/[a-z0-9._+-]+\\+json")
                    ? "JSON" : contentType.equals("text/plain") ? "TEXT" : "OTHER";
            return new Response(response.statusCode(), media, response.body(), null);
        } catch (TimeoutException ignored) {
            return failed(receivedStatus.get(), "API_TIMEOUT", "The API request exceeded its deadline.");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return failed(receivedStatus.get(), "API_INTERRUPTED", "The API request was interrupted.");
        } catch (ExecutionException failure) {
            return classify(receivedStatus.get(), failure);
        } catch (ToolFailure failure) {
            return new Response(null, null, null, failure);
        } catch (UncheckedIOException ignored) {
            return failed(null, "API_TRANSPORT_ERROR", "The HTTP client could not start.");
        } finally {
            if (exchange != null && !exchange.isDone()) exchange.cancel(true);
            inFlight.release();
        }
    }

    private static Response classify(int status, ExecutionException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolFailure safe) return new Response(statusOrNull(status), null, null, safe);
            if (cause instanceof HttpTimeoutException) {
                return failed(status, "API_TIMEOUT", "The API request exceeded a connection or request deadline.");
            }
        }
        return failed(status, "API_TRANSPORT_ERROR", "The API connection or HTTP exchange failed.");
    }

    private static Integer statusOrNull(Integer status) {
        return status != null && status == 0 ? null : status;
    }

    private static Response failed(Integer status, String code, String message) {
        return new Response(statusOrNull(status), null, null, new ToolFailure(code, message));
    }

    private static void checkHeaders(HttpResponse.ResponseInfo info) {
        long size = 0;
        for (var entry : info.headers().map().entrySet()) {
            for (String value : entry.getValue()) size += entry.getKey().length() + value.length() + 32L;
        }
        if (size > MAX_RESPONSE_HEADERS) {
            throw new ToolFailure("API_HEADERS_TOO_LARGE", "Response headers exceed 16384 bytes.");
        }
        var encodings = info.headers().allValues("Content-Encoding");
        if (!encodings.isEmpty() && !(encodings.size() == 1 && encodings.getFirst().equalsIgnoreCase("identity"))) {
            throw new ToolFailure("API_ENCODING_UNSUPPORTED", "Only identity response encoding is supported.");
        }
        if (info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_RESPONSE_BYTES) {
            throw oversized();
        }
    }

    private static ToolFailure oversized() {
        return new ToolFailure("API_RESPONSE_TOO_LARGE", "Response body exceeds 131072 bytes.");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (client != null) client.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    result.completeExceptionally(oversized());
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
