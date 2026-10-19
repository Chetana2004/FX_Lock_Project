package com.fxlock.settlement;

import static org.junit.jupiter.api.Assertions.*;

import com.fxlock.common.*;
import com.fxlock.common.Contracts.Settlement;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"eureka.client.enabled=false"})
@EnabledIfEnvironmentVariable(named = "RUN_ORACLE_TESTS", matches = "true")

class OracleSettlementTest 
{
  @Autowired JdbcTemplate db;
  @Autowired SettlementService service;
  @Autowired PlatformTransactionManager manager;
  String owner, quote, snapshot;

  @BeforeEach
  void fixture() 
  {
    owner = "it-" + UUID.randomUUID();
    snapshot = UUID.randomUUID().toString();
    db.update("INSERT INTO fx_customer(id) VALUES(?)", owner);
    db.update(
        "INSERT INTO fx_wallet(owner_id,currency,opening_balance,balance) VALUES(?,'USD',100,100)",
        owner);
    db.update(
        "INSERT INTO fx_wallet(owner_id,currency,opening_balance,balance) VALUES(?,'INR',0,0)",
        owner);
    db.update(
        "INSERT INTO fx_rate_snapshot(id,pair,rate,observed_at,created_at)"
            + " VALUES(?,'USD/INR',83,?,?)",
        snapshot,
        Db.ts(Instant.now()),
        Db.ts(Instant.now()));
    quote = newQuote(new BigDecimal("70"));
  }

  String newQuote(BigDecimal amount) 
  {
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    db.update(
        "INSERT INTO"
            + " fx_quote(id,owner_id,snapshot_id,source_ccy,target_ccy,source_amount,target_amount,offered_rate,confidence,created_at,expires_at,status)"
            + " VALUES(?,?,?,'USD','INR',?,?,83,100,?,?,'OPEN')",
        id,
        owner,
        snapshot,
        amount,
        amount.multiply(new BigDecimal("83")),
        Db.ts(now),
        Db.ts(now.plusSeconds(60)));
    return id;
  }

  BigDecimal balance(String c) 
  {
    return db.queryForObject(
        "SELECT balance FROM fx_wallet WHERE owner_id=? AND currency=?",
        BigDecimal.class,
        owner,
        c);
  }

  int count(String table) 
  {
    return db.queryForObject(
        "SELECT COUNT(*) FROM "
            + table
            + " WHERE "
            + (table.equals("fx_ledger")
                ? "settlement_id IN (SELECT id FROM fx_settlement WHERE owner_id=?)"
                : "owner_id=?"),
        Integer.class,
        owner);
  }

  @Test
  void hundredAcceptsCommitOnce() throws Exception 
  {
    var pool = Executors.newFixedThreadPool(20);
    
    try 
    {
      List<Future<Settlement>> work = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        final int n = i;
        work.add(pool.submit(() -> service.accept(owner, quote, "parallel-" + n)));
      }
      Set<String> ids = new HashSet<>();
      for (var f : work) ids.add(f.get(60, TimeUnit.SECONDS).id());
      assertEquals(1, ids.size());
      assertEquals(1, count("fx_settlement"));
      assertEquals(2, count("fx_ledger"));
      assertEquals(0, new BigDecimal("30").compareTo(balance("USD")));
    } 
    finally 
    {
      pool.shutdownNow();
    }
  }

  @Test
  void competingSpendsDoNotOverdraw() throws Exception 
  {
    String second = newQuote(new BigDecimal("70"));
    var pool = Executors.newFixedThreadPool(2);
    
    try 
    {
      var a = pool.submit(() -> service.accept(owner, quote, "spend-a"));
      var b = pool.submit(() -> service.accept(owner, second, "spend-b"));
      int success = 0;
      for (var f : List.of(a, b))
    	  
        try 
        {
          f.get(30, TimeUnit.SECONDS);
          success++;
        } 
        catch (ExecutionException e) 
        {
          assertInstanceOf(ApiError.class, e.getCause());
        }
      
      assertEquals(1, success);
      assertEquals(0, new BigDecimal("30").compareTo(balance("USD")));
      
    } 
    finally 
    {
      pool.shutdownNow();
    }
  }

  @Test
  void failedCreditRollsBackAllFinancialChanges() 
  {
    // NUMBER overflow on credit occurs after the source debit statement.
    db.update(
        "UPDATE fx_wallet SET opening_balance=9999999999999999.99,balance=9999999999999999.99 WHERE"
            + " owner_id=? AND currency='INR'",
        owner);
    assertThrows(RuntimeException.class, () -> service.accept(owner, quote, "overflow"));
    assertEquals(0, new BigDecimal("100").compareTo(balance("USD")));
    assertEquals(0, count("fx_settlement"));
    assertEquals(0, count("fx_ledger"));
    assertEquals(0, count("fx_idempotency"));
    assertEquals(
        "OPEN", db.queryForObject("SELECT status FROM fx_quote WHERE id=?", String.class, quote));
    assertEquals(1, count("fx_audit"));
  }

  @Test
  void responseLossAndKeyConflict() 
  {
    var result = service.accept(owner, quote, "retry");
    assertEquals(result.id(), service.accept(owner, quote, "retry").id());
    String other = newQuote(BigDecimal.ONE);
    assertThrows(ApiError.class, () -> service.accept(owner, other, "retry"));
    assertEquals(1, count("fx_settlement"));
  }

  @Test
  void ownershipRejects() 
  {
    assertThrows(ApiError.class, () -> service.accept("bob", quote, "wrong-owner"));
    assertEquals(0, count("fx_settlement"));
  }

  @Test
  void expiryCheckedAfterWalletWait() throws Exception
  {
    db.update(
        "UPDATE fx_quote SET expires_at=? WHERE id=?", Db.ts(Instant.now().plusSeconds(2)), quote);
    var pool = Executors.newFixedThreadPool(2);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    
    try 
    {
      var lock =
          pool.submit(
              () ->
                  new TransactionTemplate(manager)
                      .execute(
                          s -> {
                            db.queryForList(
                                "SELECT id FROM fx_wallet WHERE owner_id=? AND currency='USD' FOR"
                                    + " UPDATE",
                                owner);
                            locked.countDown();
                            try {
                              release.await(8, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                            return null;
                          }));
      
      assertTrue(locked.await(5, TimeUnit.SECONDS));
      var attempt = pool.submit(() -> service.accept(owner, quote, "wait-expiry"));
      Thread.sleep(2500);
      release.countDown();
      lock.get(10, TimeUnit.SECONDS);
      var failure = assertThrows(ExecutionException.class, () -> attempt.get(15, TimeUnit.SECONDS));
      assertInstanceOf(ApiError.class, failure.getCause());
      assertEquals(0, count("fx_settlement"));
    } 
    finally 
    {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @AfterEach
  void cleanup() 
  {
    // Isolated dev schema only; no concurrently running Event Service on this schema.
    db.update(
        "DELETE FROM fx_outbox WHERE aggregate_id IN (SELECT id FROM fx_quote WHERE owner_id=?)",
        owner);
    db.update("DELETE FROM fx_audit WHERE owner_id=?", owner);
    db.update("DELETE FROM fx_idempotency WHERE owner_id=?", owner);
    db.update(
        "DELETE FROM fx_ledger WHERE settlement_id IN (SELECT id FROM fx_settlement WHERE"
            + " owner_id=?)",
        owner);
    db.update("DELETE FROM fx_settlement WHERE owner_id=?", owner);
    db.update("DELETE FROM fx_quote WHERE owner_id=?", owner);
    db.update("DELETE FROM fx_wallet WHERE owner_id=?", owner);
    db.update("DELETE FROM fx_customer WHERE id=?", owner);
    db.update("DELETE FROM fx_rate_snapshot WHERE id=?", snapshot);
  }
}

