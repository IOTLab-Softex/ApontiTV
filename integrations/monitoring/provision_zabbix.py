"""Idempotent provisioning for Zabbix 6.4, through the supported API.

Requires ZABBIX_API_TOKEN and APONTI_MONITORING_TOKEN in the environment.
Run from an administrative shell; neither secret is printed or stored here.
"""
import json, os, urllib.request

API_URL = os.environ.get('ZABBIX_API_URL', 'http://192.168.1.109/zabbix/api_jsonrpc.php')
sid = os.environ['ZABBIX_API_TOKEN']

def api(method, params):
    def normalize(value):
        if isinstance(value, dict):
            if 'error_handler' in value: value.setdefault('error_handler_params', '')
            for child in value.values(): normalize(child)
        elif isinstance(value, list):
            for child in value: normalize(child)
    normalize(params)
    data = json.dumps(dict(jsonrpc='2.0', method=method, params=params, auth=sid, id=1)).encode()
    request = urllib.request.Request(API_URL, data=data, headers={'Content-Type':'application/json'})
    result = json.load(urllib.request.urlopen(request, timeout=25))
    if 'error' in result:
        raise RuntimeError(method + ': ' + json.dumps(result['error']))
    return result['result']

def group(kind, name):
    found = api(kind+'.get', {'filter':{'name':name}})
    return found[0]['groupid'] if found else api(kind+'.create', {'name':name})['groupids'][0]

def item(hostid, key, **params):
    existing = api('item.get', {'hostids':[hostid], 'filter':{'key_':key}, 'output':['itemid','type','master_itemid']})
    if existing:
        wanted_master = str(params.get('master_itemid') or '0')
        current_master = str(existing[0].get('master_itemid') or '0')
        if int(existing[0]['type']) != int(params['type']) or current_master != wanted_master:
            api('item.delete', [existing[0]['itemid']])
            return api('item.create', dict(params, hostid=hostid, key_=key))['itemids'][0]
        api('item.update', dict(params, itemid=existing[0]['itemid']))
        return existing[0]['itemid']
    return api('item.create', dict(params, hostid=hostid, key_=key))['itemids'][0]


if os.environ.get('INSPECT'):
    hosts=api('host.get', {'search':{'host':'aponti-'},'output':['hostid','host','name']})
    print(json.dumps(hosts,ensure_ascii=False))
    ids=[h['hostid'] for h in hosts]
    print(json.dumps(api('item.get',{'hostids':ids,'output':['itemid','hostid','name','key_','lastvalue','lastclock','state','error']}),ensure_ascii=False))
    print(json.dumps(api('discoveryrule.get',{'hostids':ids,'output':['itemid','name','state','error']}),ensure_ascii=False))
else:
    token = os.environ['APONTI_MONITORING_TOKEN']
    gid = group('hostgroup', 'Aponti TV / TVs')
    mgid = group('hostgroup', 'Aponti TV / Integracao')
    tgid = group('templategroup', 'Templates/Aponti TV')
    existing=api('template.get',{'filter':{'host':'Aponti TV por HTTP'}})
    tid=existing[0]['templateid'] if existing else api('template.create', {'host':'Aponti TV por HTTP','groups':[{'groupid':tgid}]})['templateids'][0]
    headers={'Authorization':'Bearer {$APONTI.TOKEN}'}
    master=item(tid,'aponti.tv.raw',name='Aponti: dados da TV',type=19,value_type=4,delay='10s',history='1d',trends='0',url='{$APONTI.URL}',headers=headers,timeout='10s',status_codes='200',follow_redirects=0,preprocessing=[{'type':12,'params':'$.tvs[?(@.id == {$APONTI.TV.ID})].first()','error_handler':0}])
    # These values come from the same Rails snapshot, preventing the general
    # status from combining samples collected at different moments.
    metrics=[('tv_on','Aponti: status da TV',3,''),('app_online','Aponti: status do aplicativo',3,''),('app_status','Aponti: estado detalhado do app',3,''),('playing','Aponti: reproduzindo',3,''),('web_only','Aponti: somente web',3,''),('operating_status','Aponti: conteúdo exibido',3,''),('expected_running','Aponti: transmissao configurada',3,''),('overall_status','Aponti: status geral',3,''),('last_seen','Aponti: ultimo contato',3,'unixtime'),('last_seen_age','Aponti: segundos sem contato',0,'s')]
    for key,name,kind,units in metrics:
        item(tid,'aponti.tv.'+key,name=name,type=18,value_type=kind,delay='0',master_itemid=master,history='30d',trends='365d',units=units,preprocessing=[{'type':12,'params':'$.'+key,'error_handler':0}])
    item(tid,'icmpping',name='Aponti: conectividade de rede',type=3,value_type=3,delay='10s',history='30d',trends='365d')
    item(tid,'icmppingsec',name='Aponti: latencia de rede',type=3,value_type=0,delay='10s',history='30d',trends='365d',units='s')
    macros=[{'macro':'{$APONTI.URL}','value':os.environ.get('APONTI_MONITORING_URL', 'http://192.168.1.98:3000/monitoring/tvs')},{'macro':'{$APONTI.TOKEN}','value':token,'type':1}]
    existing=api('host.get',{'filter':{'host':'aponti-tv-inventory'}})
    hid=existing[0]['hostid'] if existing else api('host.create',{'host':'aponti-tv-inventory','name':'Aponti TV - Descoberta automatica','groups':[{'groupid':mgid}],'macros':macros})['hostids'][0]
    api('host.update',{'hostid':hid,'macros':macros})
    inventory=item(hid,'aponti.inventory',name='Aponti: inventario de TVs',type=19,value_type=4,delay='10s',history='1d',trends='0',url='{$APONTI.URL}',headers=headers,timeout='10s',status_codes='200',follow_redirects=0)
    for key,name,path in [('count','Aponti: total de TVs','$.tvs.length()'),('online','Aponti: apps online','$.tvs[?(@.app_online == 1)].length()'),('playing','Aponti: apps reproduzindo','$.tvs[?(@.playing == 1)].length()'),('collected','Aponti: coleta mais recente','$.collected_at')]:
        item(hid,'aponti.inventory.'+key,name=name,type=18,value_type=3,delay='0',master_itemid=inventory,history='30d',trends='365d',preprocessing=[{'type':12,'params':path,'error_handler':0}])
    discovery=dict(name='TVs cadastradas no Aponti TV',type=18,delay='0',master_itemid=inventory,lifetime='1h',preprocessing=[{'type':12,'params':'$.tvs','error_handler':0}],lld_macro_paths=[{'lld_macro':'{#TV.ID}','path':'$.id'},{'lld_macro':'{#TV.NAME}','path':'$.name'},{'lld_macro':'{#TV.IP}','path':'$.ip'}],filter={'evaltype':0,'conditions':[{'macro':'{#TV.IP}','value':'.+','operator':8}]})
    found=api('discoveryrule.get',{'hostids':[hid],'filter':{'key_':'aponti.tvs.discovery'}})
    if found:
        rid=found[0]['itemid']; api('discoveryrule.update',dict(discovery,itemid=rid))
    else:
        rid=api('discoveryrule.create',dict(discovery,hostid=hid,key_='aponti.tvs.discovery'))['itemids'][0]
    prototype=dict(host='aponti-tv-{#TV.ID}',name='{#TV.NAME}',groupLinks=[{'groupid':gid}],templates=[{'templateid':tid}],macros=macros+[{'macro':'{$APONTI.TV.ID}','value':'{#TV.ID}'}],custom_interfaces=1,interfaces=[{'main':1,'type':1,'useip':1,'ip':'{#TV.IP}','dns':'','port':'10050'}],tags=[{'tag':'source','value':'aponti-tv'},{'tag':'aponti-id','value':'{#TV.ID}'}])
    found=api('hostprototype.get',{'discoveryids':[rid]})
    if found: api('hostprototype.update',dict(prototype,hostid=found[0]['hostid']))
    else: api('hostprototype.create',dict(prototype,ruleid=rid))
    api('task.create',{'type':6,'request':{'itemid':inventory}})
    print(json.dumps({'templateid':tid,'inventory_hostid':hid,'discoveryid':rid,'tv_groupid':gid,'status':'configured'}))
