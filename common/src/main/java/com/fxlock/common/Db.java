package com.fxlock.common;

import com.fxlock.common.Contracts.*;
import java.sql.*;
import java.time.Instant;

public final class Db 
{
  private Db() 
  {
	  
  }

  public static Timestamp ts(Instant t) 
  {
    return Timestamp.from(t);
  }

  public static Quote quote(ResultSet r, int n) throws SQLException 
  {
    return new Quote(
        r.getString("id"),
        r.getString("owner_id"),
        r.getString("snapshot_id"),
        r.getString("source_ccy"),
        r.getString("target_ccy"),
        r.getBigDecimal("source_amount"),
        r.getBigDecimal("target_amount"),
        r.getBigDecimal("offered_rate"),
        r.getBigDecimal("confidence"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("expires_at").toInstant(),
        r.getString("status"));
  }

  public static Settlement settlement(ResultSet r, int n) throws SQLException 
  {
    return new Settlement(
        r.getString("id"),
        r.getString("quote_id"),
        r.getString("owner_id"),
        r.getBigDecimal("source_amount"),
        r.getBigDecimal("target_amount"),
        r.getTimestamp("completed_at").toInstant());
  }
}



