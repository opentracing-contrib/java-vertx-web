[![Build Status][ci-img]][ci] [![Released Version][maven-img]][maven]

# OpenTracing Vert.x Web Instrumentation
OpenTracing instrumentation for Vert.x Web project. This repository currently contains
handler which traces server requests.

## Maven

```xml
<dependency>
        <groupId>io.opentracing.contrib</groupId>
        <artifactId>opentracing-vertx-web</artifactId>
        <version>${version}</version>
</dependency>

## Jaeger

<dependency>
        <groupId>io.jaegertracing</groupId>
        <artifactId>jaeger-client</artifactId>
        <version>${version}</version>
</dependency>
```

## Configuration
```java
Router router = Router.router(vertx);

TracingHandler handler = new TracingHandler(tracer);
router.route()
        .order(-1).handler(handler)
        .failureHandler(handler);

```

## Accessing server span context
Because Vert.x is event loop based, thread local implementations of span source do not work.
The current solution is to get span context from `RoutingContext` and then pass it manually around.
```java
SpanContext serverContext = TracingHandler.serverSpanContext(routingContext);
```

## Development
```shell
./mvnw clean install
```

## Release
Follow instructions in [RELEASE](RELEASE.md)

   [ci-img]: https://travis-ci.org/opentracing-contrib/java-vertx-web.svg?branch=master
   [ci]: https://travis-ci.org/opentracing-contrib/java-vertx-web
   [maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/opentracing-vertx-web.svg?maxAge=2592000
   [maven]: http://search.maven.org/#search%7Cga%7C1%7Copentracing-vertx-web
