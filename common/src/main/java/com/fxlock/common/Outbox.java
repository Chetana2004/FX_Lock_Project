package com.fxlock.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.common.Contracts.Envelope;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class Outbox 
{
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public Outbox(JdbcTemplate db, ObjectMapper json, Clock clock) 
  {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(String topic, String aggregateId, Object data) 
  {
    String id = UUID.randomUUID().toString();
    
    try 
    {
      String payload =
          json.writeValueAsString(new Envelope(id, 1, topic, aggregateId, clock.instant(), data));
      db.update(
          "INSERT INTO"
              + " fx_outbox(id,topic,aggregate_id,payload,status,attempts,available_at,created_at)"
              + " VALUES(?,?,?,?,'PENDING',0,SYS_EXTRACT_UTC(SYSTIMESTAMP),SYS_EXTRACT_UTC(SYSTIMESTAMP))",
          id,
          topic,
          aggregateId,
          payload);
    } 
    catch (com.fasterxml.jackson.core.JsonProcessingException e) 
    {
      throw new IllegalStateException("Event serialization failed", e);
    }
  }
}

