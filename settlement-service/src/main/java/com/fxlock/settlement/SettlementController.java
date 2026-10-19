package com.fxlock.settlement;

import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettlementController 
{
  private final SettlementService service;

  public SettlementController(SettlementService service) 
  {
    this.service = service;
  }

  @PostMapping("/api/settlements/{quoteId}")
  public Object accept(
  @PathVariable String quoteId, @RequestHeader("Idempotency-Key") String key, Principal p) 
  {
    return service.accept(p.getName(), quoteId, key);
  }

  @GetMapping("/api/settlements/{id}")
  public Object result(@PathVariable String id, Principal p) 
  {
    return service.result(p.getName(), id);
  }

  @GetMapping("/api/wallets")
  public Object wallets(Principal p) 
  {
    return service.wallets(p.getName());
  }
}
