package com.fxlock.quote;

import com.fxlock.common.*;
import com.fxlock.common.Contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;

@RestController
public class QuoteController {
  public record Request(
      @NotBlank String sourceCurrency,
      @NotBlank String targetCurrency,
      @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final RestTemplate http;
  private final Clock clock;
  private final BigDecimal spread, min, max;
  private final long expiry;
  private final String password;

  public QuoteController(
      JdbcTemplate db,
      TransactionTemplate tx,
      RestTemplate http,
      Clock clock,
      @Value("${fx.quote.spread}") BigDecimal spread,
      @Value("${fx.quote.min}") BigDecimal min,
      @Value("${fx.quote.max}") BigDecimal max,
      @Value("${fx.quote.expiry-seconds}") long expiry,
      @Value("${fx.auth.service}") String password) {
    this.db = db;
    this.tx = tx;
    this.http = http;
    this.clock = clock;
    this.spread = spread;
    this.min = min;
    this.max = max;
    this.expiry = expiry;
    this.password = password;
    if (spread.signum() < 0 || spread.compareTo(BigDecimal.ONE) >= 0 || expiry != 60)
      throw new IllegalArgumentException("Invalid quote configuration");
  }

  private HttpHeaders serviceHeaders() {
    var h = new HttpHeaders();
    h.setBasicAuth("svc", password);
    return h;
  }

  @PostMapping("/api/quotes")
  public Quote create(@Valid @RequestBody Request req, Principal owner) {
    if (!Set.of("USD", "INR", "EUR").contains(req.sourceCurrency())
        || !Set.of("USD", "INR", "EUR").contains(req.targetCurrency())
        || req.sourceCurrency().equals(req.targetCurrency()))
      throw ApiError.bad("Unsupported or identical currencies");
    if (req.amount().compareTo(min) < 0 || req.amount().compareTo(max) > 0)
      throw ApiError.bad("Amount must be between " + min + " and " + max);
    String pair = req.sourceCurrency() + "/" + req.targetCurrency();
    TrustedRate r;
    try {
      r =
          http.exchange(
                  "http://rate-service/internal/rates?pair={pair}",
                  HttpMethod.GET,
                  new HttpEntity<>(serviceHeaders()),
                  TrustedRate.class,
                  pair)
              .getBody();
    } catch (RestClientException e) {
      throw ApiError.unavailable("Trusted-rate service unavailable for this pair");
    }
    if (r == null
        || !pair.equals(r.pair())
        || r.rate() == null
        || r.rate().signum() <= 0
        || !Pricing.fresh(r, clock.instant()))
      throw ApiError.unavailable("Rate unavailable or older than 90 seconds");
    BigDecimal offered = Pricing.offered(r.rate(), spread),
        target = Pricing.target(req.amount(), offered);
    return tx.execute(
        s -> {
          Instant now = clock.instant();
          if (!Pricing.fresh(r, now)) throw ApiError.unavailable("Rate expired during creation");
          Quote q =
              new Quote(
                  UUID.randomUUID().toString(),
                  owner.getName(),
                  r.snapshotId(),
                  req.sourceCurrency(),
                  req.targetCurrency(),
                  req.amount().setScale(2),
                  target,
                  offered,
                  Pricing.confidence(r, now),
                  now,
                  now.plusSeconds(expiry),
                  "OPEN");
          db.update(
              "INSERT INTO"
                  + " fx_quote(id,owner_id,snapshot_id,source_ccy,target_ccy,source_amount,target_amount,offered_rate,confidence,created_at,expires_at,status)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
              q.id(),
              q.owner(),
              q.snapshotId(),
              q.sourceCurrency(),
              q.targetCurrency(),
              q.sourceAmount(),
              q.targetAmount(),
              q.offeredRate(),
              q.confidence(),
              Db.ts(q.createdAt()),
              Db.ts(q.expiresAt()),
              q.status());
          return q;
        });
  }

  @GetMapping("/api/quotes/{id}")
  public Quote get(@PathVariable String id, Principal p) {
    var rows =
        db.query("SELECT * FROM fx_quote WHERE id=? AND owner_id=?", Db::quote, id, p.getName());
    if (rows.isEmpty()) throw ApiError.missing();
    return rows.getFirst();
  }

  @PostMapping("/api/quotes/{id}/accept")
  public Object accept(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("Authorization") String auth,
      Principal p) {
    get(id, p);
    var h = new HttpHeaders();
    h.set("Authorization", auth);
    h.set("Idempotency-Key", key);
    return remote("http://settlement-service/api/settlements/" + id, HttpMethod.POST, h);
  }

  @GetMapping("/api/wallets")
  public Object wallets(@RequestHeader("Authorization") String auth) {
    var h = new HttpHeaders();
    h.set("Authorization", auth);
    return remote("http://settlement-service/api/wallets", HttpMethod.GET, h);
  }

  @GetMapping("/ops/risk")
  public Object risk() {
    return remote("http://rate-service/internal/risk", HttpMethod.GET, serviceHeaders());
  }

  @GetMapping("/ops/history")
  public Object history() {
    return remote("http://event-service/internal/history", HttpMethod.GET, serviceHeaders());
  }

  private Object remote(String url, HttpMethod method, HttpHeaders h) {
    try {
      return http.exchange(url, method, new HttpEntity<>(h), Object.class).getBody();
    } catch (HttpStatusCodeException e) {
      throw new ApiError(
          HttpStatus.valueOf(e.getStatusCode().value()), e.getResponseBodyAsString());
    } catch (RestClientException e) {
      throw ApiError.unavailable("Downstream unavailable; retry acceptance with the same key");
    }
  }
}
