package com.fxlock.common;

import java.math.BigDecimal;
import java.time.Instant;

public final class Contracts 
{
  private Contracts() 
  {
	  
  }

  public record TrustedRate(
      String snapshotId,
      String pair,
      BigDecimal rate,
      Instant observedAt,
      long maxAgeSeconds,
      boolean fresh,
      boolean anomaly,
      boolean providerHealthy,
      BigDecimal movement) {}

  public record Quote(
      String id,
      String owner,
      String snapshotId,
      String sourceCurrency,
      String targetCurrency,
      BigDecimal sourceAmount,
      BigDecimal targetAmount,
      BigDecimal offeredRate,
      BigDecimal confidence,
      Instant createdAt,
      Instant expiresAt,
      String status) {}

  public record Settlement(
      String id,
      String quoteId,
      String owner,
      BigDecimal sourceAmount,
      BigDecimal targetAmount,
      Instant completedAt) {}

  public record Envelope(
      String eventId,
      int version,
      String type,
      String aggregateId,
      Instant occurredAt,
      Object data) {}
}
