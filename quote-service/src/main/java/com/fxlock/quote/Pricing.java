package com.fxlock.quote;

import com.fxlock.common.ApiError;
import com.fxlock.common.Contracts.TrustedRate;
import java.math.*;
import java.time.*;

public final class Pricing 
{
	
  private Pricing() 
  {
	  
  }

  public static BigDecimal offered(BigDecimal market, BigDecimal spread) 
  {
    return market.multiply(BigDecimal.ONE.subtract(spread)).setScale(10, RoundingMode.HALF_EVEN);
  }

  public static BigDecimal target(BigDecimal amount, BigDecimal rate) 
  {
    BigDecimal result = amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    if (result.signum() <= 0 || result.precision() > 18)
      throw ApiError.bad("Converted amount outside supported range");
    return result;
  }

  public static boolean fresh(TrustedRate r, Instant now) 
  {
    return r.fresh()
        && r.observedAt() != null
        && !r.observedAt().isAfter(now)
        && now.isBefore(r.observedAt().plusSeconds(90));
  }

  public static BigDecimal confidence(TrustedRate r, Instant now) 
  {
    double age = Math.max(0, Duration.between(r.observedAt(), now).toMillis() / 1000.0);
    double f = Math.max(0, 1 - age / 90.0),
        a = r.anomaly() ? 0 : 1,
        p = r.providerHealthy() ? 1 : 0,
        s = Math.max(0, Math.min(1, 1 - r.movement().doubleValue() / 0.05));
    return BigDecimal.valueOf(40 * f + 20 * a + 20 * p + 20 * s)
        .setScale(2, RoundingMode.HALF_EVEN);
  }
}


