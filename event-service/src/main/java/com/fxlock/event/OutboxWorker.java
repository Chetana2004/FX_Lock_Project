package com.fxlock.event;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker 
{
  private record Claimed(String id, String topic, String aggregate, String payload, String token) 
  {
	  
  }

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;

  public OutboxWorker(
      JdbcTemplate db, TransactionTemplate tx, KafkaTemplate<String, String> kafka) {
    this.db = db;
    this.tx = tx;
    this.kafka = kafka;
  }

  @Scheduled(fixedDelayString = "${fx.outbox.poll-ms}", initialDelay = 5000)
  public void publish() 
  {
    try 
    {
      for (Claimed e : claim()) 
      {
        try 
        {
          kafka.send(e.topic(), e.aggregate(), e.payload()).get(20, TimeUnit.SECONDS);
          // Only a broker ACK allows PUBLISHED. Fencing prevents an old worker updating a new
          // claim.
          db.update(
              "UPDATE fx_outbox SET"
                  + " status='PUBLISHED',published_at=SYS_EXTRACT_UTC(SYSTIMESTAMP),claim_token=NULL,claimed_at=NULL,last_error=NULL"
                  + " WHERE id=? AND status='INFLIGHT' AND claim_token=?",
              e.id(),
              e.token());
        } 
        catch (Exception failure) 
        {
          if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
          db.update(
              "UPDATE fx_outbox SET"
                  + " status='PENDING',available_at=SYS_EXTRACT_UTC(SYSTIMESTAMP)+INTERVAL '10'"
                  + " SECOND,claim_token=NULL,claimed_at=NULL,last_error='Kafka send not"
                  + " acknowledged; retry scheduled' WHERE id=? AND status='INFLIGHT' AND"
                  + " claim_token=?",
              e.id(),
              e.token());
        }
      }
    } 
    catch (Exception failure) 
    {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("Outbox poll failed; next poll retries", failure);
    }
  }

  private List<Claimed> claim() 
  {
    return tx.execute(
        s -> {
          db.update(
              "UPDATE fx_outbox SET"
                  + " status='PENDING',claim_token=NULL,claimed_at=NULL,available_at=SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                  + " WHERE status='INFLIGHT' AND claimed_at<SYS_EXTRACT_UTC(SYSTIMESTAMP)-INTERVAL"
                  + " '300' SECOND");
          String token = UUID.randomUUID().toString();
          List<Claimed> rows =
              db.query(
                  c -> {
                    var p =
                        c.prepareStatement(
                            "SELECT id,topic,aggregate_id,payload FROM fx_outbox WHERE"
                                + " status='PENDING' AND"
                                + " available_at<=SYS_EXTRACT_UTC(SYSTIMESTAMP) ORDER BY created_at"
                                + " FOR UPDATE SKIP LOCKED");
                    p.setMaxRows(10);
                    p.setFetchSize(10);
                    return p;
                  },
                  (r, n) ->
                      new Claimed(
                          r.getString(1), r.getString(2), r.getString(3), r.getString(4), token));
          for (var e : rows)
            db.update(
                "UPDATE fx_outbox SET"
                    + " status='INFLIGHT',claim_token=?,claimed_at=SYS_EXTRACT_UTC(SYSTIMESTAMP),attempts=attempts+1"
                    + " WHERE id=?",
                token,
                e.id());
          return rows;
        });
  }
}



