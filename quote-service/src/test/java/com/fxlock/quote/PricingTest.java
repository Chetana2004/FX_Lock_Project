package com.fxlock.quote;

import static org.junit.jupiter.api.Assertions.*;

import com.fxlock.common.Contracts.TrustedRate;
import java.math.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class PricingTest {
  Instant now = Instant.parse("2026-10-15T10:00:00Z");

  TrustedRate rate(Instant time, boolean a, boolean p, String move) {
    return new TrustedRate(
        "snapshot", "USD/INR", new BigDecimal("83.25"), time, 90, true, a, p, new BigDecimal(move));
  }

  @Test
  void decimalSpread() {
    var r = Pricing.offered(new BigDecimal("83.25"), new BigDecimal("0.005"));
    assertEquals(new BigDecimal("82.8337500000"), r);
    assertEquals(new BigDecimal("8283.38"), Pricing.target(new BigDecimal("100"), r));
  }

  @Test
  void halfEvenTies() {
    assertEquals(new BigDecimal("1.00"), Pricing.target(BigDecimal.ONE, new BigDecimal("1.005")));
    assertEquals(new BigDecimal("1.02"), Pricing.target(BigDecimal.ONE, new BigDecimal("1.015")));
  }

  @Test
  void timestampChecks() {
    assertFalse(Pricing.fresh(rate(now.minusSeconds(90), false, true, "0"), now));
    assertFalse(Pricing.fresh(rate(now.plusSeconds(1), false, true, "0"), now));
    assertTrue(Pricing.fresh(rate(now.minusSeconds(89), false, true, "0"), now));
  }

  @Test
  void confidenceWeights() {
    assertEquals(new BigDecimal("100.00"), Pricing.confidence(rate(now, false, true, "0"), now));
    assertEquals(
        new BigDecimal("20.00"),
        Pricing.confidence(rate(now.minusSeconds(45), true, false, "0.05"), now));
  }

  @Test
  void zeroOutputRejects() {
    assertThrows(
        com.fxlock.common.ApiError.class,
        () -> Pricing.target(new BigDecimal("0.01"), new BigDecimal("0.0001")));
  }
}
