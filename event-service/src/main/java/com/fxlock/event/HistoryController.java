package com.fxlock.event;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HistoryController 
{
  private final JdbcTemplate db;

  public HistoryController(JdbcTemplate db) 
  {
    this.db = db;
  }

  @GetMapping({"/ops/history", "/internal/history"})
  public Object history() 
  {
    return Map.of(
        "outbox",
            db.queryForList(
                "SELECT id,topic,status,attempts,last_error,created_at,published_at FROM fx_outbox"
                    + " ORDER BY created_at DESC FETCH FIRST 100 ROWS ONLY"),
        "audit",
            db.queryForList(
                "SELECT * FROM fx_audit ORDER BY created_at DESC FETCH FIRST 100 ROWS ONLY"),
        "events",
            db.queryForList(
                "SELECT event_id,event_type,aggregate_id,received_at FROM fx_event_history ORDER BY"
                    + " received_at DESC FETCH FIRST 100 ROWS ONLY"));
  }
}






