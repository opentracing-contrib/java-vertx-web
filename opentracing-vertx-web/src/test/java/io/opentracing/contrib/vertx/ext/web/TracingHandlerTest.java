package io.opentracing.contrib.vertx.ext.web;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.contrib.vertx.ext.web.WebSpanDecorator.StandardTags;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.ThreadLocalScopeManager;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.WebTestBase;
import io.vertx.ext.web.handler.TimeoutHandler;
import org.awaitility.Awaitility;
import org.hamcrest.core.IsEqual;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * @author Pavol Loffay
 */
public class TracingHandlerTest extends WebTestBase {

    protected MockTracer mockTracer = new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

    @Override
    public void setUp() throws Exception {
        super.setUp();
        TracingHandler withStandardTags = new TracingHandler(mockTracer, Collections.singletonList(new StandardTags()));
        router.route()
                .order(-1).handler(withStandardTags)
                .failureHandler(withStandardTags);
    }

    @Override
    protected VertxOptions getOptions() {
        //force one event loop to make testing active-span bugs easier
        return new VertxOptions().setEventLoopPoolSize(1);
    }

    @Before
    public void beforeTest() throws Exception {
        mockTracer.reset();
    }

    @Test
    public void testNoURLMapping() throws Exception {
        {
            request("/noUrlMapping", HttpMethod.GET, 404);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertEquals(404, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/noUrlMapping", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    @Test
    public void testStandardTags() throws Exception {
        {
            router.route("/hello").handler(routingContext -> {
                routingContext.response()
                        .setChunked(true)
                        .write("hello\n")
                        .end();
            });

            request("/hello", HttpMethod.GET, 200);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertEquals(Tags.SPAN_KIND_SERVER, mockSpan.tags().get(Tags.SPAN_KIND.getKey()));
        Assert.assertEquals("vertx", mockSpan.tags().get(Tags.COMPONENT.getKey()));
        Assert.assertEquals(200, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/hello", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    @Test
    public void testReroute() throws Exception {
        {
            router.route("/route1").handler(routingContext -> {
                routingContext.reroute("/route2");
            });

            router.route("/route2").handler(routingContext -> {
                routingContext.response()
                        .setStatusCode(205)
                        .setChunked(true)
                        .write("dsds")
                        .end();
            });

            request("/route1", HttpMethod.GET, 205);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertEquals(205, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/route1", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(1, mockSpan.logEntries().size());
        Assert.assertEquals(3, mockSpan.logEntries().get(0).fields().size());
        Assert.assertEquals("reroute", mockSpan.logEntries().get(0).fields().get("event"));
        Assert.assertEquals("http://localhost:8080/route2",
                mockSpan.logEntries().get(0).fields().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals("GET",
            mockSpan.logEntries().get(0).fields().get(Tags.HTTP_METHOD.getKey()));
    }

    @Test
    public void testRerouteFailures() throws Exception {
        {
            router.route("/route1").handler(routingContext -> {
                routingContext.reroute("/route2");
            }).failureHandler(event -> {
                event.response().setStatusCode(400);
            });

            router.route("/route2").handler(routingContext -> {
                throw new IllegalArgumentException("e");
            }).failureHandler(event -> {
                event.response().setStatusCode(401).end();
            });

            request("/route1", HttpMethod.GET, 401);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(6, mockSpan.tags().size());
        Assert.assertEquals(Boolean.TRUE, mockSpan.tags().get(Tags.ERROR.getKey()));
        Assert.assertEquals(401, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/route1", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(2, mockSpan.logEntries().size());
    }

    @Test
    public void testMultipleRoutes() throws Exception {
        {
            router.route("/route").handler(routingContext -> {
                routingContext.response()
                        .setChunked(true)
                        .setStatusCode(205)
                        .write("route1");

                routingContext.next();
            });

            router.route("/route").handler(routingContext -> {
                routingContext.response()
                        .write("route2")
                        .end();
            });

            request("/route", HttpMethod.GET, 205);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertEquals(205, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/route", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    @Test
    public void testLocalSpan() throws Exception {
        {
            router.route("/localSpan").handler(routingContext -> {
                SpanContext serverSpanContext = TracingHandler.serverSpanContext(routingContext);
                io.opentracing.Tracer.SpanBuilder spanBuilder = mockTracer.buildSpan("localSpan");

                spanBuilder.asChildOf(serverSpanContext)
                        .start()
                        .finish();

                routingContext.response()
                        .setStatusCode(202)
                        .end();
            });

            request("/localSpan", HttpMethod.GET, 202);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(2));
        }
        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(2, mockSpans.size());

        Assert.assertEquals(mockSpans.get(0).parentId(), mockSpans.get(1).context().spanId());
        Assert.assertEquals(mockSpans.get(0).context().traceId(), mockSpans.get(1).context().traceId());
    }

    @Test
    public void testFailRoutingContext() throws Exception {
        {
            router.route("/fail").handler(routingContext -> {
                routingContext.fail(501);
            });

            request("/fail", HttpMethod.GET, 501);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }
        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(6, mockSpan.tags().size());
        Assert.assertEquals(Boolean.TRUE, mockSpan.tags().get(Tags.ERROR.getKey()));
        Assert.assertEquals(501, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/fail", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    @Test
    public void testExceptionInHandler() throws Exception {
        {
            router.route("/exception").handler(routingContext -> {
                throw new IllegalArgumentException("msg");
            });

            request("/exception", HttpMethod.GET,500);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }
        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(6, mockSpan.tags().size());
        Assert.assertEquals(Boolean.TRUE, mockSpan.tags().get(Tags.ERROR.getKey()));
        Assert.assertEquals(500, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/exception", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(1, mockSpan.logEntries().size());
        Assert.assertEquals(2, mockSpan.logEntries().get(0).fields().size());
        Assert.assertEquals(Tags.ERROR.getKey(), mockSpan.logEntries().get(0).fields().get("event"));
        Assert.assertTrue(mockSpan.logEntries().get(0).fields().get("error.object") instanceof Throwable);
    }

    @Test
    public void testExceptionInHandlerWithFailureHandler() throws Exception {
        {
            router.route("/exceptionWithHandler").handler(routingContext -> {
                throw new IllegalArgumentException("msg");
            }).failureHandler(event -> {
                event.response()
                        .setStatusCode(404)
                        .end();
            });

            request("/exceptionWithHandler", HttpMethod.GET, 404);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }
        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(6, mockSpan.tags().size());
        Assert.assertEquals(Boolean.TRUE, mockSpan.tags().get(Tags.ERROR.getKey()));
        Assert.assertEquals(404, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/exceptionWithHandler", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(1, mockSpan.logEntries().size());
        Assert.assertEquals(2, mockSpan.logEntries().get(0).fields().size());
        Assert.assertEquals(Tags.ERROR.getKey(), mockSpan.logEntries().get(0).fields().get("event"));
        Assert.assertTrue(mockSpan.logEntries().get(0).fields().get("error.object") instanceof Throwable);
    }

    @Test
    public void testTimeoutHandler() throws Exception {
        {
            router.route().handler(TimeoutHandler.create(300, 501));

            router.route("/timeout")
                    .blockingHandler(routingContext -> {
                        try {
                            Thread.sleep(10000);
                            routingContext.response()
                                    .setStatusCode(202)
                                    .end();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            routingContext.response().end();
                        }
                    });

            request("/timeout", HttpMethod.GET, 501);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }
        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(6, mockSpan.tags().size());
        Assert.assertEquals(Boolean.TRUE, mockSpan.tags().get(Tags.ERROR.getKey()));
        Assert.assertEquals(501, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/timeout", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    @Test
    public void testBodyEndHandler() throws Exception {
        {
            router.route("/bodyEnd").handler(routingContext -> {
                    routingContext.addBodyEndHandler(event -> {
                        // noop
                    });

                    routingContext.response().end();
                });

            request("/bodyEnd", HttpMethod.GET, 200);
            Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(1));
        }
        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals("GET", mockSpan.operationName());
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertEquals(200, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:8080/bodyEnd", mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    /**
     * If someone incorrectly starts a span on an event loop, the TracingHandler was previously using it as the current
     * active span to be a child of. Such functionality is correct in a thread-per-request environment but not
     * in an event loop model. The tracinghandler now `ignoreActiveSpans` which is a better safeguard against the
     * problem.
     * 
     */
    @Test
    public void testIgnoringActiveSpan() throws Exception {
        final CountDownLatch firstLatch = new CountDownLatch(1);
        final CountDownLatch secondLatch = new CountDownLatch(1);

        router.route("/wait").handler(context -> {
            Span span = mockTracer.buildSpan("internal")
                    .start();
            Scope scope = mockTracer.scopeManager().activate(span);
            vertx().executeBlocking((result) -> {
                firstLatch.countDown();
                try {
                    awaitLatch(secondLatch);
                } catch (InterruptedException e) {
                    result.fail(e);
                }
                result.complete();
            }, result -> {
                scope.close();
                context.response().end();
            });
        });

        //perform two requests -- we want to block
        //inside the handler and make an active span.
        request("/wait", HttpMethod.GET, 200);
        awaitLatch(firstLatch);

        request("/wait", HttpMethod.GET, 200);
        //they should both me in the router now -- resume the latch
        secondLatch.countDown();

        Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(2));
        for (MockSpan span : mockTracer.finishedSpans()) {
            Assert.assertEquals(span.parentId(), 0);
        }

    }

    protected void request(String path, HttpMethod method, int statusCode) throws InterruptedException {
        HttpClientRequest req = client.request(method, 8080, "localhost", path, resp -> {
            assertEquals(statusCode, resp.statusCode());
        });
        req.end();
    }

    protected Callable<Integer> reportedSpansSize() {
        return () -> mockTracer.finishedSpans().size();
    }
}
