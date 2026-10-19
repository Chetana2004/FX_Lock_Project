-- Every query should return zero rows.

SELECT quote_id,COUNT(*) FROM fx_settlement GROUP BY quote_id HAVING COUNT(*)>1;
SELECT w.id,w.balance,w.opening_balance+NVL(SUM(l.delta),0) expected
FROM fx_wallet w LEFT JOIN fx_ledger l ON l.wallet_id=w.id
GROUP BY w.id,w.balance,w.opening_balance
HAVING w.balance<>w.opening_balance+NVL(SUM(l.delta),0);
SELECT s.id FROM fx_settlement s LEFT JOIN fx_ledger l ON l.settlement_id=s.id GROUP BY s.id HAVING COUNT(l.id)<>2;
SELECT q.id FROM fx_quote q LEFT JOIN fx_settlement s ON s.quote_id=q.id
WHERE (q.status='SETTLED' AND s.id IS NULL) OR (q.status='OPEN' AND s.id IS NOT NULL);
SELECT s.id FROM fx_settlement s JOIN fx_quote q ON q.id=s.quote_id
WHERE s.owner_id<>q.owner_id OR s.source_amount<>q.source_amount OR s.target_amount<>q.target_amount;

-- Never sum ledger amounts across different currencies as if they were equal units.
SELECT s.id FROM fx_settlement s JOIN fx_quote q ON q.id=s.quote_id
WHERE NOT EXISTS(SELECT 1 FROM fx_ledger l JOIN fx_wallet w ON w.id=l.wallet_id
 WHERE l.settlement_id=s.id AND w.owner_id=q.owner_id AND w.currency=q.source_ccy AND l.delta=-q.source_amount)
OR NOT EXISTS(SELECT 1 FROM fx_ledger l JOIN fx_wallet w ON w.id=l.wallet_id
 WHERE l.settlement_id=s.id AND w.owner_id=q.owner_id AND w.currency=q.target_ccy AND l.delta=q.target_amount);

 
 
 
 
 