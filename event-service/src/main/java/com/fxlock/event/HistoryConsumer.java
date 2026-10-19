package com.fxlock.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class HistoryConsumer 
{
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public HistoryConsumer(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) 
  {
    this.db = db;
    this.tx = tx;
    this.json = json;
  }

  @KafkaListener(topics = {"fx-rate-updated", "fx-settlement-completed", "fx-system-alert"})
  public void receive(ConsumerRecord<String, String> record) throws Exception 
  {
    var e = json.readTree(record.value());
    String id = e.path("eventId").asText(),
        type = e.path("type").asText(),
        aggregate = e.path("aggregateId").asText();
    if (!id.matches("[0-9a-fA-F-]{36}")
        || e.path("version").asInt() != 1
        || !record.topic().equals(type)
        || aggregate.isBlank()) throw new IllegalArgumentException("Invalid event envelope");
    tx.executeWithoutResult(
        s -> {
          try {
            db.update(
                "INSERT INTO fx_event_history(event_id,event_type,aggregate_id,payload,received_at)"
                    + " VALUES(?,?,?,?,SYS_EXTRACT_UTC(SYSTIMESTAMP))",
                id,
                type,
                aggregate,
                record.value());
          } 
          catch (DuplicateKeyException duplicate) 
          {
            /* Prior delivery already committed this effect. */
          }
        });
    // Container's RECORD acknowledgment occurs after the DB transaction completes.
  }
}
