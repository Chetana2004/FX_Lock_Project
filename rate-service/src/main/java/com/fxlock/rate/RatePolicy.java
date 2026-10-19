package com.fxlock.rate;

import java.math.*;
import java.time.*;

public final class RatePolicy 
{
  private RatePolicy() 
  {
	  
  }

  public static boolean fresh(Instant observed, Instant now, long age) 
  {
    return !observed.isAfter(now) && now.isBefore(observed.plusSeconds(age));
  }

  public static BigDecimal movement(BigDecimal old, BigDecimal incoming) 
  {
    return incoming.subtract(old).abs().divide(old, 12, RoundingMode.HALF_EVEN);
  }

  public static void validate(
      String expected,
      String actual,
      BigDecimal rate,
      Instant observed,
      Instant now,
      Instant previous) 
  {
    if (!expected.equals(actual)
        || rate == null
        || rate.signum() <= 0
        || rate.precision() - rate.scale() > 10)
      throw new IllegalArgumentException("Invalid provider symbol or rate");
    if (observed == null || !fresh(observed, now, 90))
      throw new IllegalArgumentException("Provider observation is stale or future dated");
    if (previous != null && !observed.isAfter(previous))
      throw new IllegalArgumentException("Repeated or out-of-order observation");
  }
}



