package com.fxlock.settlement;

import com.fxlock.common.*;
import com.fxlock.common.Contracts.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SettlementService 
{
  private record Wallet(long id, String currency, BigDecimal balance) 
  {
	  
  }

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Outbox outbox;
  private final AttemptAudit audit;
  private final Clock clock;

  public SettlementService(
      JdbcTemplate db, TransactionTemplate tx, Outbox outbox, AttemptAudit audit, Clock clock) {
    this.db = db;
    this.tx = tx;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
  }

  public Settlement accept(String owner, String quote, String key) 
  {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
      throw ApiError.bad("Idempotency-Key must contain 1-100 letters, digits or ._:-");
    try 
    {
      return tx.execute(s -> settle(owner, quote, key));
    } 
    catch (RuntimeException failure) 
    {
      if (failure instanceof DataIntegrityViolationException) 
      {
        Settlement existing = replay(owner, quote, key);
        if (existing != null) return existing;
      }
      try 
      {
        audit.failure(
            owner,
            quote,
            failure instanceof ApiError
                ? failure.getMessage()
                : "Settlement transaction rolled back");
      } 
      catch (Exception e) 
      {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .error("Failed-attempt audit could not be persisted", e);
      }
      throw failure;
    }
  }

  private Settlement replay(String owner, String quote, String key) 
  {
    var rows =
        db.query(
            "SELECT s.* FROM fx_idempotency i JOIN fx_settlement s ON s.id=i.settlement_id WHERE"
                + " i.owner_id=? AND i.idem_key=?",
            Db::settlement,
            owner,
            key);
    if (rows.isEmpty()) return null;
    if (!rows.getFirst().quoteId().equals(quote))
      throw ApiError.conflict("Idempotency key belongs to another quote");
    return rows.getFirst();
  }

  private void bind(String owner, String key, String quote, String result) 
  {
    db.update(
        "INSERT INTO fx_idempotency(owner_id,idem_key,quote_id,settlement_id) VALUES(?,?,?,?)",
        owner,
        key,
        quote,
        result);
  }

  private Settlement settle(String owner, String quote, String key) 
  {
    var quotes =
        db.query(
            "SELECT * FROM fx_quote WHERE id=? AND owner_id=? FOR UPDATE WAIT 10",
            Db::quote,
            quote,
            owner);
    if (quotes.isEmpty()) throw ApiError.missing();
    Quote q = quotes.getFirst();
    Settlement cached = replay(owner, quote, key);
    if (cached != null) return cached;
    var existing =
        db.query(
            "SELECT * FROM fx_settlement WHERE quote_id=? AND owner_id=?",
            Db::settlement,
            quote,
            owner);
    if (!existing.isEmpty()) 
    {
      var result = existing.getFirst();
      bind(owner, key, quote, result.id());
      return result;
    }
    
    var ids =
        db.queryForList(
            "SELECT id FROM fx_wallet WHERE owner_id=? AND currency IN (?,?) ORDER BY id",
            Long.class,
            owner,
            q.sourceCurrency(),
            q.targetCurrency());
    
    if (ids.size() != 2) throw ApiError.conflict("Both wallets must exist");
    // Explicitly acquire one wallet at a time in ascending ID order.
    Map<String, Wallet> wallets = new HashMap<>();
    
    for (Long id : ids) 
    {
      var wallet =
          db.queryForObject(
              "SELECT id,currency,balance FROM fx_wallet WHERE id=? AND owner_id=? FOR UPDATE WAIT"
                  + " 10",
              (r, n) -> new Wallet(r.getLong(1), r.getString(2), r.getBigDecimal(3)),
              id,
              owner);
      wallets.put(wallet.currency(), wallet);
    }
    
    cached = replay(owner, quote, key);
    if (cached != null) return cached;
    // Check expiry using the database clock AFTER any wallet lock wait.
    
    Instant now =
        db.queryForObject(
            "SELECT SYS_EXTRACT_UTC(SYSTIMESTAMP) FROM dual",
            (r, n) -> r.getTimestamp(1).toInstant());
    if (!q.status().equals("OPEN")) throw ApiError.conflict("Quote is no longer open");
    if (!now.isBefore(q.expiresAt())) throw ApiError.conflict("Quote expired");
    Wallet source = wallets.get(q.sourceCurrency()), target = wallets.get(q.targetCurrency());
    if (source.balance().compareTo(q.sourceAmount()) < 0)
      throw ApiError.conflict("Insufficient funds");
    db.update("UPDATE fx_wallet SET balance=balance-? WHERE id=?", q.sourceAmount(), source.id());
    db.update("UPDATE fx_wallet SET balance=balance+? WHERE id=?", q.targetAmount(), target.id());
    Settlement result =
        new Settlement(
            UUID.randomUUID().toString(), quote, owner, q.sourceAmount(), q.targetAmount(), now);
    db.update(
        "INSERT INTO fx_settlement(id,quote_id,owner_id,source_amount,target_amount,completed_at)"
            + " VALUES(?,?,?,?,?,?)",
        result.id(),
        quote,
        owner,
        q.sourceAmount(),
        q.targetAmount(),
        Db.ts(now));
    db.update(
        "INSERT INTO fx_ledger(id,settlement_id,wallet_id,delta,created_at) VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        result.id(),
        source.id(),
        q.sourceAmount().negate(),
        Db.ts(now));
    db.update(
        "INSERT INTO fx_ledger(id,settlement_id,wallet_id,delta,created_at) VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        result.id(),
        target.id(),
        q.targetAmount(),
        Db.ts(now));
    db.update("UPDATE fx_quote SET status='SETTLED' WHERE id=?", quote);
    bind(owner, key, quote, result.id());
    db.update(
        "INSERT INTO fx_audit(id,owner_id,quote_id,outcome,detail,created_at)"
            + " VALUES(?,?,?,'SUCCESS','Settlement committed',?)",
        UUID.randomUUID().toString(),
        owner,
        quote,
        Db.ts(now));
    outbox.append("fx-settlement-completed", quote, result);
    return result;
  }

  public Object wallets(String owner) 
  {
    return db.queryForList(
        "SELECT currency,balance FROM fx_wallet WHERE owner_id=? ORDER BY currency", owner);
  }

  public Settlement result(String owner, String id) 
  {
    var rows =
        db.query(
            "SELECT * FROM fx_settlement WHERE id=? AND owner_id=?", Db::settlement, id, owner);
    if (rows.isEmpty()) throw ApiError.missing();
    return rows.getFirst();
  }
}



