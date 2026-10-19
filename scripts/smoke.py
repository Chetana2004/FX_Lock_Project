"""Run after starting the system; spends 10 simulated USD. Standard library only."""
import os,json,base64,urllib.request
BASE='http://localhost:8082'
AUTH='Basic '+base64.b64encode(('alice:'+os.environ['ALICE_PASSWORD']).encode()).decode()
def call(path,body=None,key=None):
 headers={'Authorization':AUTH,'Content-Type':'application/json'}
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method='GET' if body is None else 'POST')
 with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
quote=call('/api/quotes',{'sourceCurrency':'USD','targetCurrency':'INR','amount':'10.00'})
path='/api/quotes/'+quote['id']+'/accept'
a=call(path,{},'smoke-'+quote['id']);b=call(path,{},'smoke-'+quote['id'])
assert a['id']==b['id']
assert call('/api/quotes/'+quote['id'])['status']=='SETTLED'
print('PASS: quote, acceptance replay, stored status')
print(json.dumps(call('/api/wallets'),indent=2))
