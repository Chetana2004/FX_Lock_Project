package com.fxlock.settlement;

import com.fxlock.common.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AttemptAudit 
{
  private final JdbcTemplate db;
  private final Outbox outbox;
  private final Clock clock;

  public AttemptAudit(JdbcTemplate db, Outbox outbox, Clock clock) 
  {
    this.db = db;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failure(String owner, String quote, String reason) 
  {
    db.update(
        "INSERT INTO fx_audit(id,owner_id,quote_id,outcome,detail,created_at)"
            + " VALUES(?,?,?,'FAILED',?,?)",
        UUID.randomUUID().toString(),
        owner,
        quote,
        reason,
        Db.ts(clock.instant()));
    outbox.append("fx-system-alert", quote, Map.of("quoteId", quote, "reason", reason));
  }
}


