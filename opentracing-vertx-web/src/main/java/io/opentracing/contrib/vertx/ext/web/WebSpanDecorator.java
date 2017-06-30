package io.opentracing.contrib.vertx.ext.web;

import io.vertx.core.Handler;
import java.util.HashMap;
import java.util.Map;

import io.opentracing.BaseSpan;
import io.opentracing.tag.Tags;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Decorate server span at different stages. Do not call blocking code inside decorators!
 *
 * @author Pavol Loffay
 */
public interface WebSpanDecorator {
    /**
     * Decorate span when span is started.
     *
     * @param request server request
     * @param span server span
     */
    void onRequest(HttpServerRequest request, BaseSpan<?> span);

    /**
     * Decorate span when span is rerouted.
     *
     * @param request server request
     * @param span server span
     */
    void onReroute(HttpServerRequest request, BaseSpan<?> span);

    /**
     * Decorate span when the response is known. This is effectively invoked in BodyEndHandler which is added to
     * - {@link io.vertx.ext.web.RoutingContext#addBodyEndHandler(Handler)}
     *
     * @param request server request
     * @param span server span
     */
    void onResponse(HttpServerRequest request, BaseSpan<?> span);

    /**
     * Decorate request when an exception is thrown during request processing.
     *
     * @param throwable an exception thrown when processing the request
     * @param response server response
     * @param span server span
     */
    void onFailure(Throwable throwable, HttpServerResponse response, BaseSpan<?> span);

    /**
     * Decorator which adds standard set of tags e.g. HTTP/PEER/ERROR tags.
     */
    class StandardTags implements WebSpanDecorator {
        @Override
        public void onRequest(HttpServerRequest request, BaseSpan<?> span) {
            Tags.COMPONENT.set(span, "java-vertx");
            Tags.HTTP_METHOD.set(span, request.method().toString());
            Tags.HTTP_URL.set(span, request.absoluteURI());
        }

        @Override
        public void onReroute(HttpServerRequest request, BaseSpan<?> span) {
            Map<String, String> logs = new HashMap<>(2);
            logs.put("event", "reroute");
            logs.put(Tags.HTTP_URL.getKey(), request.absoluteURI());
            span.log(logs);
        }

        @Override
        public void onResponse(HttpServerRequest request, BaseSpan<?> span) {
            Tags.HTTP_STATUS.set(span, request.response().getStatusCode());
        }

        @Override
        public void onFailure(Throwable throwable, HttpServerResponse response, BaseSpan<?> span) {
            Tags.ERROR.set(span, Boolean.TRUE);

            if (throwable != null) {
                span.log(exceptionLogs(throwable));
            }
        }

        public static Map<String, Object> exceptionLogs(Throwable throwable) {
            Map<String, Object> errorLog = new HashMap<>(2);
            errorLog.put("event", Tags.ERROR.getKey());
            errorLog.put("error.object", throwable);

            return errorLog;
        }
    }
}
