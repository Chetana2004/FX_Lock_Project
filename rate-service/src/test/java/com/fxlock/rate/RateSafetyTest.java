package com.fxlock.rate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fxlock.common.*;
import com.sun.net.httpserver.HttpServer;
import java.math.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RateSafetyTest {
  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-15T10:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  MutableClock clock;
  RateService service;

  RateService make(String mode, String url, int timeout) {
    return new RateService(
        mock(JdbcTemplate.class),
        new TransactionTemplate(mock(PlatformTransactionManager.class)),
        mock(Outbox.class),
        clock,
        url,
        "test-key",
        mode,
        timeout,
        List.of("USD/INR"),
        new BigDecimal("0.05"),
        90);
  }

  @BeforeEach
  void setup() {
    clock = new MutableClock();
    service = make("mock", "http://localhost", 100);
  }

  @Test
  void emptyRejects() {
    assertThrows(ApiError.class, () -> service.trusted("USD/INR"));
  }

  @Test
  void nonpositiveRejects() {
    for (String value : List.of("0", "-1")) {
      service.ingest("USD/INR", "USD/INR", new BigDecimal(value), clock.now);
      assertThrows(ApiError.class, () -> service.trusted("USD/INR"));
    }
  }

  @Test
  void repeatedObservationDoesNotRefreshAge() {
    Instant first = clock.now;
    service.ingest("USD/INR", "USD/INR", new BigDecimal("83"), first);
    clock.now = first.plusSeconds(80);
    service.ingest("USD/INR", "USD/INR", new BigDecimal("83"), first);
    assertEquals(first, service.trusted("USD/INR").observedAt());
    clock.now = first.plusSeconds(90);
    assertFalse(service.trusted("USD/INR").fresh());
  }

  @Test
  void anomalyPreservesTrustedSnapshot() {
    service.poll();
    var first = service.trusted("USD/INR");
    clock.now = clock.now.plusSeconds(15);
    service.ingest("USD/INR", "USD/INR", new BigDecimal("100"), clock.now);
    var after = service.trusted("USD/INR");
    assertEquals(first.snapshotId(), after.snapshotId());
    assertTrue(after.anomaly());
    assertTrue(after.fresh());
  }

  @Test
  void staleFutureWrongPairOutOfOrderReject() {
    service.poll();
    var first = service.trusted("USD/INR");
    clock.now = clock.now.plusSeconds(15);
    for (Instant t :
        List.of(
            clock.now.minusSeconds(91),
            clock.now.plusSeconds(1),
            first.observedAt().minusSeconds(1)))
      service.ingest("USD/INR", "USD/INR", new BigDecimal("83"), t);
    service.ingest("USD/INR", "EUR/USD", new BigDecimal("83"), clock.now);
    assertEquals(first.snapshotId(), service.trusted("USD/INR").snapshotId());
  }

  @Test
  void malformedResponseAndTimeoutPreserveSafety() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/exchange_rate",
        exchange -> {
          byte[] body = "{\"rate\":83}".getBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var live = make("live", "http://127.0.0.1:" + server.getAddress().getPort(), 500);
      live.ingest("USD/INR", "USD/INR", new BigDecimal("83"), clock.now);
      String id = live.trusted("USD/INR").snapshotId();
      live.poll();
      assertEquals(id, live.trusted("USD/INR").snapshotId());
      assertFalse(live.trusted("USD/INR").providerHealthy());
      server.removeContext("/exchange_rate");
      server.createContext(
          "/exchange_rate",
          exchange -> {
            try {
              Thread.sleep(200);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            exchange.close();
          });
      var timed = make("live", "http://127.0.0.1:" + server.getAddress().getPort(), 30);
      timed.poll();
      assertThrows(ApiError.class, () -> timed.trusted("USD/INR"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restartRestoresOriginalTimestamp() throws Exception {
    var db = mock(JdbcTemplate.class);
    var row = mock(java.sql.ResultSet.class);
    Instant old = clock.now.minusSeconds(95);
    when(row.getString(1)).thenReturn("snapshot");
    when(row.getBigDecimal(2)).thenReturn(new BigDecimal("83"));
    when(row.getTimestamp(3)).thenReturn(Db.ts(old));
    doAnswer(
            inv -> {
              org.springframework.jdbc.core.RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(row, 0));
            })
        .when(db)
        .query(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<?>>any(),
            eq("USD/INR"));
    var restarted =
        new RateService(
            db,
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            mock(Outbox.class),
            clock,
            "http://localhost",
            "key",
            "mock",
            100,
            List.of("USD/INR"),
            new BigDecimal("0.05"),
            90);
    restarted.restore();
    assertEquals(old, restarted.trusted("USD/INR").observedAt());
    assertFalse(restarted.trusted("USD/INR").fresh());
    assertFalse(restarted.trusted("USD/INR").providerHealthy());
  }
}
