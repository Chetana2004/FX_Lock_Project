package com.fxlock.rate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fxlock.common.*;
import com.fxlock.common.Contracts.TrustedRate;
import jakarta.annotation.PostConstruct;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Service
public class RateService 
{
  private record Snapshot(String id, BigDecimal rate, Instant observed) 
  {
	  
  }

  private final Map<String, Snapshot> cache = new HashMap<>();
  private final Map<String, Boolean> health = new HashMap<>(), anomalies = new HashMap<>();
  private final Map<String, BigDecimal> movement = new HashMap<>();
  private final Map<String, String> reasons = new HashMap<>();
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Outbox outbox;
  private final Clock clock;
  private final RestClient http;
  private final String key, mode;
  private final List<String> pairs;
  private final BigDecimal threshold;
  private final long maxAge;

  public RateService(
      JdbcTemplate db,
      TransactionTemplate tx,
      Outbox outbox,
      Clock clock,
      @Value("${fx.provider.url}") String url,
      @Value("${fx.provider.key}") String key,
      @Value("${fx.provider.mode}") String mode,
      @Value("${fx.provider.timeout-ms}") int timeout,
      @Value("${fx.rate.pairs}") List<String> pairs,
      @Value("${fx.rate.anomaly-threshold}") BigDecimal threshold,
      @Value("${fx.rate.max-age-seconds}") long maxAge) {
	  
    this.db = db;
    this.tx = tx;
    this.outbox = outbox;
    this.clock = clock;
    this.key = key;
    this.mode = mode;
    this.pairs = pairs;
    this.threshold = threshold;
    this.maxAge = maxAge;
    
    if (maxAge != 90
        || threshold.signum() <= 0
        || threshold.compareTo(BigDecimal.ONE) >= 0
        || !Set.of("live", "mock").contains(mode))
    	
      throw new IllegalArgumentException("Invalid provider/risk configuration");
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(timeout);
    f.setReadTimeout(timeout);
    http = RestClient.builder().baseUrl(url).requestFactory(f).build();
  }

  @PostConstruct
  public synchronized void restore() 
  {
    for (String pair : pairs) 
    {
      var rows =
          db.query(
              "SELECT id,rate,observed_at FROM fx_rate_snapshot WHERE pair=? ORDER BY observed_at"
                  + " DESC FETCH FIRST 1 ROW ONLY",
              (r, n) ->
                  new Snapshot(r.getString(1), r.getBigDecimal(2), r.getTimestamp(3).toInstant()),
              pair);
      if (!rows.isEmpty()) cache.put(pair, rows.getFirst());
      health.put(pair, false);
      anomalies.put(pair, false);
      movement.put(pair, BigDecimal.ZERO);
      reasons.put(pair, "Awaiting provider observation");
    }
  }

  @Scheduled(fixedDelayString = "${fx.rate.refresh-ms}", initialDelay = 1000)
  public synchronized void poll() 
  {
    for (String pair : pairs)
      try 
      {
        fetch(pair);
      } 
      catch (Exception e) 
      {
        health.put(pair, false);
        reasons.put(pair, "Provider request or persistence failed");
        alert(pair, "PROVIDER_UNAVAILABLE");
      }
  }

  private void fetch(String pair) 
  {
    if (mode.equals("mock")) 
    {
      BigDecimal rate =
          switch (pair) 
          {
            case "USD/INR" -> new BigDecimal("83.2500000000");
            case "EUR/USD" -> new BigDecimal("1.0900000000");
            default -> throw new IllegalArgumentException("No mock pair");
          };
          
      ingest(pair, pair, rate, clock.instant().truncatedTo(ChronoUnit.MICROS));
      return;
    }
    if (key.isBlank()) throw new IllegalStateException("Missing provider API key");
    JsonNode body =
        http.get()
            .uri(
                b ->
                    b.path("/exchange_rate")
                        .queryParam("symbol", pair)
                        .queryParam("apikey", key)
                        .build())
            .retrieve()
            .body(JsonNode.class);
    
    if (body == null
        || !body.hasNonNull("symbol")
        || !body.hasNonNull("rate")
        || !body.path("timestamp").isIntegralNumber()
        || !body.path("timestamp").canConvertToLong()
        || "error".equals(body.path("status").asText()))
      throw new IllegalArgumentException("Malformed provider response");
    
    ingest(
        pair,
        body.get("symbol").asText(),
        new BigDecimal(body.get("rate").asText()),
        Instant.ofEpochSecond(body.get("timestamp").longValue()));
  }

  synchronized void ingest(String pair, String actual, BigDecimal rate, Instant observed) {
    Snapshot old = cache.get(pair);
    try 
    {
      RatePolicy.validate(
          pair, actual, rate, observed, clock.instant(), old == null ? null : old.observed());
    } 
    catch (IllegalArgumentException e) 
    {
      health.put(pair, false);
      reasons.put(pair, e.getMessage());
      alert(pair, e.getMessage());
      return;
    }
    
    rate = rate.setScale(10, RoundingMode.HALF_EVEN);
    if (rate.signum() <= 0) throw new IllegalArgumentException("Rate rounds to zero");
    BigDecimal move = old == null ? BigDecimal.ZERO : RatePolicy.movement(old.rate(), rate);
    movement.put(pair, move);
    health.put(pair, true);
    if (old != null && move.compareTo(threshold) > 0) 
    {
      anomalies.put(pair, true);
      reasons.put(pair, "ANOMALY: movement exceeds threshold");
      alert(pair, "ANOMALY");
      return;
    }
    
    Snapshot next = new Snapshot(UUID.randomUUID().toString(), rate, observed);
    
    tx.executeWithoutResult(
        s -> 
        {
          db.update(
              "INSERT INTO fx_rate_snapshot(id,pair,rate,observed_at,created_at) VALUES(?,?,?,?,?)",
              next.id(),
              pair,
              next.rate(),
              Db.ts(next.observed()),
              Db.ts(clock.instant()));
          outbox.append(
              "fx-rate-updated",
              pair,
              Map.of(
                  "snapshotId",
                  next.id(),
                  "pair",
                  pair,
                  "rate",
                  next.rate(),
                  "observedAt",
                  next.observed()));
        });
    cache.put(pair, next);
    anomalies.put(pair, false);
    reasons.put(pair, "OK");
  }

  private void alert(String pair, String reason) 
  {
    try 
    {
      tx.executeWithoutResult(
          s -> outbox.append("fx-system-alert", pair, Map.of("pair", pair, "reason", reason)));
    } 
    catch (Exception e) 
    {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Unable to persist rate alert for {}", pair);
    }
  }

  public synchronized TrustedRate trusted(String pair) 
  {
    if (!pairs.contains(pair)) throw ApiError.bad("Unsupported currency pair");
    
    Snapshot s = cache.get(pair);
    
    if (s == null) throw ApiError.unavailable("No trusted observation available");
    
    return new TrustedRate(
        s.id(),
        pair,
        s.rate(),
        s.observed(),
        maxAge,
        RatePolicy.fresh(s.observed(), clock.instant(), maxAge),
        anomalies.getOrDefault(pair, false),
        health.getOrDefault(pair, false),
        movement.getOrDefault(pair, BigDecimal.ZERO));
  }

  public synchronized List<Map<String, Object>> risk() 
  {
    return pairs.stream()
        .map(
            p -> 
            {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("pair", p);
              row.put("providerMode", mode);
              row.put("providerHealthy", health.getOrDefault(p, false));
              row.put("anomaly", anomalies.getOrDefault(p, false));
              row.put("reason", reasons.getOrDefault(p, "EMPTY"));
              row.put("snapshot", cache.containsKey(p) ? trusted(p) : null);
              return row;
            })
        .toList();
  }
}

