package com.fxlock.rate;

import org.springframework.web.bind.annotation.*;

@RestController
public class RateController 
{
  private final RateService rates;

  public RateController(RateService rates) 
  {
    this.rates = rates;
  }

  @GetMapping("/internal/rates")
  public Object rate(@RequestParam String pair) 
  {
    return rates.trusted(pair);
  }

  @GetMapping({"/ops/risk", "/internal/risk"})
  public Object risk() 
  {
    return rates.risk();
  }
}



