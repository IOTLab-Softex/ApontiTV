"""Generate the dynamic Grafana dashboard (no passwords or fixed TV list)."""
import json
from pathlib import Path

DATASOURCE = {'type': 'alexanderzobnin-zabbix-datasource', 'uid': 'bdlokpjbz277kd'}

def target(item, inventory=False):
    return {
        'refId':'A', 'datasource':DATASOURCE, 'schema':12, 'queryType':'0',
        'group':{'filter':'Aponti TV / Integracao' if inventory else 'Aponti TV / TVs'},
        'host':{'filter':'/.*/'}, 'item':{'filter':item},
        'application':{'filter':''}, 'itemTag':{'filter':''}, 'tags':{'filter':''},
        'proxy':{'filter':''}, 'trigger':{'filter':''}, 'macro':{'filter':''},
        'functions':[] if inventory else [{'def':{'name':'setAlias','category':'Alias','params':[{'name':'alias','type':'string'}],'defaultParams':[]},'params':['$host'],'text':'setAlias($host)'}],
        'options':{'showDisabledItems':False,'skipEmptyValues':True,'useTrends':'false',
                   'disableDataAlignment':True,'useZabbixValueMapping':False},
        'resultFormat':'time_series', 'table':{'skipEmptyValues':True}
    }

def panel(pid,title,item,x,y,w,h,mapping=None,inventory=False,kind='stat',unit='none'):
    defaults={'unit':unit,'noValue':'Sem coleta recente',
              'color':{'mode':'thresholds'},
              'thresholds':{'mode':'absolute','steps':[{'color':'blue','value':None}]},
              'mappings':[]}
    if mapping:
        defaults['mappings']=[{'type':'value','options':{str(k):{'text':v[0],'color':v[1],'index':i} for i,(k,v) in enumerate(mapping.items())}}]
    return {'id':pid,'type':kind,'title':title,'datasource':DATASOURCE,
            'gridPos':{'x':x,'y':y,'w':w,'h':h}, 'targets':[target(item,inventory)],
            'fieldConfig':{'defaults':defaults,'overrides':[]},
            'timeFrom':'6h' if kind=='state-timeline' else '2m',
            'options':{'reduceOptions':{'values':False,'calcs':['lastNotNull'],'fields':''},
                       'orientation':'auto','textMode':'value_and_name','colorMode':'background',
                       'graphMode':'none','justifyMode':'auto','showValue':'auto',
                       'rowHeight':0.85,'mergeValues':True,
                       'legend':{'displayMode':'list','placement':'bottom','showLegend':True},
                       'tooltip':{'mode':'single','sort':'none'}}}

def dashboard():
    state={0:('App offline','red'),1:('App online','blue'),2:('Reproduzindo','green'),3:('Erro no app','orange')}
    tv_state={0:('Desligada','red'),1:('Ligada','green')}
    app_state={0:('Fechado','red'),1:('Aberto','green')}
    content_state={0:('Sem exibição','gray'),1:('Somente web','purple'),2:('Reproduzindo','green'),3:('App aberto','blue'),4:('Erro','orange')}
    overall_state={1:('Online','green'),2:('TV ligada / app fechado','orange'),3:('TV desligada','red'),4:('Erro no app','dark-red'),5:('Ligada sem conteúdo','yellow')}
    panels=[
        panel(1,'TVs cadastradas','Aponti: total de TVs',0,0,6,4,inventory=True),
        panel(2,'Apps online','Aponti: apps online',6,0,6,4,inventory=True),
        panel(3,'Reproduzindo','Aponti: apps reproduzindo',12,0,6,4,inventory=True),
        panel(4,'Ultima coleta do cadastro','Aponti: coleta mais recente',18,0,6,4,inventory=True,unit='dateTimeAsLocal'),
        panel(5,'Status geral','Aponti: status geral',0,4,24,6,overall_state),
        panel(6,'Status da TV','Aponti: status da TV',0,10,8,6,tv_state),
        panel(7,'Status do aplicativo','Aponti: status do aplicativo',8,10,8,6,app_state),
        panel(8,'Conteúdo exibido','Aponti: conteúdo exibido',16,10,8,6,content_state),
        panel(9,'Histórico do status geral — últimas 6 horas','Aponti: status geral',0,16,24,9,overall_state,kind='state-timeline'),
        panel(10,'Histórico: TV ligada/desligada — últimas 6 horas','Aponti: status da TV',0,25,12,8,tv_state,kind='state-timeline'),
        panel(11,'Histórico: app aberto/fechado — últimas 6 horas','Aponti: status do aplicativo',12,25,12,8,app_state,kind='state-timeline'),
        panel(12,'Tempo desde o último contato do app','Aponti: segundos sem contato',0,33,24,5,{-1:('Nunca conectou','gray')},unit='s'),
        {'id':13,'type':'text','title':'Como este painel funciona','gridPos':{'x':0,'y':38,'w':24,'h':5},
         'options':{'mode':'markdown','content':'**Status geral:** 1 Online; 2 TV ligada com app fechado; 3 TV desligada; 4 Erro no app; 5 Ligada sem conteúdo identificado. Somente web e Reproduzindo resultam em Online quando a TV e o app estão ativos.\n\nDescoberta, coleta e atualização a cada 10 segundos. TVs removidas são excluídas automaticamente do Zabbix após 1 hora.'}}
    ]
    return {'uid':'aponti-tv-status','title':'Aponti TV — Status das TVs','tags':['Aponti TV','automatico'],
            'timezone':'browser','schemaVersion':39,'version':1,'editable':True,'refresh':'10s',
            'time':{'from':'now-15m','to':'now'},'panels':panels,'templating':{'list':[]},
            'links':[{'title':'Abrir Aponti TV','url':'http://192.168.1.98:3000/broadcasts','targetBlank':True}]}

if __name__=='__main__':
    Path(__file__).with_name('aponti-tv-status.json').write_text(json.dumps(dashboard(),ensure_ascii=False,indent=2),encoding='utf-8')
