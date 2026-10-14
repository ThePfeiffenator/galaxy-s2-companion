"""Pixel-only OAuth setup. Tokens and library results stay in private app storage."""
from pathlib import Path
import json, os, sys, time
import requests
os.umask(0o077)
home=Path.home()/'galaxy-music'
config=json.loads((home/'client.json').read_text())['installed']
def save(name,value):
    temp=home/(name+'.tmp');temp.write_text(json.dumps(value));temp.replace(home/name)
command=sys.argv[1]
if command in ('start','startfull'):
    scope='https://www.googleapis.com/auth/youtube' if command=='startfull' else 'https://www.googleapis.com/auth/youtube.readonly'
    r=requests.post('https://oauth2.googleapis.com/device/code',data={'client_id':config['client_id'],'scope':scope},timeout=20)
    data=r.json()
    if 'device_code' not in data: print('Authorization request failed:',data.get('error'));sys.exit(1)
    data['created']=time.time();save('pending.json',data)
    print(json.dumps({k:data[k] for k in ('user_code','verification_url','expires_in')}))
elif command=='poll':
    p=json.loads((home/'pending.json').read_text());interval=max(5,p.get('interval',5))
    while time.time()<p['created']+p['expires_in']:
        r=requests.post('https://oauth2.googleapis.com/token',data={'client_id':config['client_id'],'client_secret':config['client_secret'],'device_code':p['device_code'],'grant_type':'urn:ietf:params:oauth:grant-type:device_code'},timeout=25)
        data=r.json()
        if 'access_token' in data:
            data['expires_at']=int(time.time())+data['expires_in'];save('oauth.json',data);(home/'pending.json').unlink();print('Music authorization saved privately on Pixel.',flush=True);break
        err=data.get('error','unknown')
        if err=='slow_down':interval+=5
        elif err!='authorization_pending':print('Authorization:',err,flush=True);sys.exit(1)
        time.sleep(interval)
    else: print('Authorization expired.',flush=True);sys.exit(1)
elif command=='check':
    from ytmusicapi import YTMusic, OAuthCredentials
    api=YTMusic(str(home/'oauth.json'),oauth_credentials=OAuthCredentials(config['client_id'],config['client_secret']))
    for name,fn in [('songs',api.get_library_songs),('playlists',api.get_library_playlists),('likes',api.get_liked_songs)]:
        try:
            result=fn(limit=10);save('check-'+name+'.json',result)
            print(name,'read succeeded',len(result.get('tracks',[])) if isinstance(result,dict) else len(result),flush=True)
        except Exception as e:
            # Avoid outputting any response bodies, account data or access tokens.
            detail=str(e)
            indicators=[term for term in ['401','403','404','PERMISSION_DENIED','ACCESS_TOKEN_SCOPE_INSUFFICIENT','insufficient','authentication scopes','has not been used','disabled','accessNotConfigured','UNAUTHENTICATED','Invalid Credentials'] if term.lower() in detail.lower()]
            save('check-'+name+'-error.json',{'type':type(e).__name__,'detail':detail})
            print(name,'read failed',type(e).__name__,indicators,flush=True)
