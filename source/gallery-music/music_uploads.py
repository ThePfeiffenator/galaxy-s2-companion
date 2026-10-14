"""Account-authorized audio-only uploads, kept entirely in the Pixel runtime."""
from pathlib import Path
import base64, json, math, os, re, subprocess, tempfile, threading, time
from urllib.parse import urlsplit

STEP=6
VIDEO=re.compile(r'^[A-Za-z0-9_-]{11}$')

def duration(player):
    for fmt in player.get('streamingData',{}).get('adaptiveFormats',[]):
        if fmt.get('itag') == 140 and not fmt.get('drmFamilies'):
            value=float(fmt.get('approxDurationMs',0))/1000
            if 0 < value <= 43200:
                return value
    raise RuntimeError('upload_format_unavailable')

def manifest(ident, length):
    if not VIDEO.fullmatch(ident) or not 0 < length <= 43200:
        raise RuntimeError('invalid_track')
    lines=['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:6',
           '#EXT-X-MEDIA-SEQUENCE:0','#EXT-X-PLAYLIST-TYPE:VOD']
    for n in range(math.ceil(length/STEP)):
        lines.extend(['#EXTINF:%.3f,' % min(STEP,length-n*STEP),
            'http://127.0.0.1:8768/sabrhls-audio-fragment/music/%s/%d.ts' % (ident,n)])
    return ('\n'.join(lines+['#EXT-X-ENDLIST'])+'\n').encode()

def metadata_entry(song, data):
    from music_tracks import normalized
    def text(value):
        return value.get('simpleText') or ''.join(r.get('text','') for r in value.get('runs',[]))
    contents=data.get('contents',{}).get('singleColumnWatchNextResults',{}).get('results',{}).get('results',{}).get('contents',[])
    for section in contents:
        for item in section.get('itemSectionRenderer',{}).get('contents',[]):
            meta=item.get('musicWatchMetadataRenderer',{})
            if normalized(text(meta.get('title',{}))) != normalized(song['title']): continue
            album=text(meta.get('albumName',{}));artist=text(meta.get('byline',{}))
            if album and len(album)<=1000 and artist and len(artist)<=1000:
                return {'album':album,'artist':artist,'status':'matched','checked':time.time(),'upload':True}
    return None

class Uploads:
    def __init__(self, home, state):
        self.home,self.state=home,state
        self.lock=threading.RLock()
        self.players={}
        self.parts={}
        self.cache=home/'upload-stream-cache-copy-v1'
        self.cache.mkdir(mode=0o777 if os.name=='nt' else 0o700,exist_ok=True)

    def known(self, ident):
        if not VIDEO.fullmatch(ident): raise RuntimeError('invalid_track')
        with self.state.lock:
            return any(s['id']==ident and not s.get('public_playback')
                for s in (self.state.cached or {}).get('songs',[]))

    def player(self, ident):
        if not self.known(ident): raise RuntimeError('unknown_upload')
        import requests
        with self.lock:
            old=self.players.get(ident)
            if old and time.monotonic()-old['checked']<900: return old
            from music_server import GoogleLibrary
            from music_signature import context
            library=GoogleLibrary(self.home)
            body,player_url=context()
            body['videoId']=ident
            for attempt in range(2):
                token=library.token(force=attempt==1)
                response=requests.post('https://www.youtube.com/youtubei/v1/player',
                    headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},
                    json=body,timeout=(5,15),allow_redirects=False)
                if response.status_code==401 and attempt==0: continue
                if response.status_code!=200: raise RuntimeError('upload_authorization_failed')
                player=response.json()
                if player.get('playabilityStatus',{}).get('status')!='OK': raise RuntimeError('upload_unavailable')
                length=duration(player)
                player['assets']={'js':player_url}
                player['_galaxyClientVersion']=body['context']['client']['clientVersion']
                # Signature processing is deferred until playback; thumbnail browsing
                # must not load a JavaScript player for every uploaded album.
                entry={'player':player,'length':length,'token':token,'prepared':False,'checked':time.monotonic()}
                if len(self.players)>=4:
                    oldest=min(self.players,key=lambda k:self.players[k]['checked'])
                    self.players.pop(oldest,None);self.parts.pop(oldest,None)
                self.players[ident]=entry
                return entry

    def describe(self, ident):
        self.player(ident)
        meta={}
        if hasattr(self,'albums'):
            with self.albums.lock:meta=dict(self.albums.entries.get(ident,{}))
        if not meta.get('album'):
            with self.state.lock:
                song=next(s for s in self.state.cached['songs'] if s['id']==ident)
            try:
                meta=self.metadata(song) or {}
                if meta and hasattr(self,'albums'):
                    with self.albums.lock:
                        self.albums.entries[ident]=meta;self.albums.save_locked()
            except Exception:
                meta={}
        return {'source_id':ident,'play_id':ident,'transport':'upload','album':meta.get('album',''),
                'artist':meta.get('artist',''),'replacement':False}

    def metadata(self, song):
        import requests
        from music_server import GoogleLibrary
        from yt_dlp.extractor.youtube._base import INNERTUBE_CLIENTS
        response=requests.post('https://www.youtube.com/youtubei/v1/next',
            headers={'Authorization':'Bearer '+GoogleLibrary(self.home).token()},
            json={'context':INNERTUBE_CLIENTS['tv']['INNERTUBE_CONTEXT'],'videoId':song['id']},
            timeout=(5,12),allow_redirects=False)
        if response.status_code!=200: raise RuntimeError('upload_metadata_unavailable')
        return metadata_entry(song,response.json())

    def art(self, ident):
        entry=self.player(ident)
        from music_tracks import safe_art
        images=[t for t in entry['player'].get('videoDetails',{}).get('thumbnail',{}).get('thumbnails',[])
                if safe_art(t.get('url',''))]
        if not images: raise RuntimeError('artwork_unavailable')
        return max(images,key=lambda t:t.get('width',0))['url']

    def fetch(self, ident, entry, offset):
        for attempt in range(2):
            if not entry['prepared']:
                from music_signature import prepare
                prepare(ident,entry['player']);entry['prepared']=True
            result=subprocess.run(['node',str(self.home/'upload_sabr.js')],
                input=json.dumps({'player':entry['player'],'offset':offset,'token':entry['token']}),
                text=True,capture_output=True,timeout=55)
            if len(result.stdout)>8*1024*1024: raise RuntimeError('upload_audio_limit')
            data=json.loads(result.stdout)
            if result.returncode==0: break
            if attempt==0 and data.get('httpStatus')==403:
                self.players.pop(ident,None);entry=self.player(ident)
            else: raise RuntimeError('upload_audio_unavailable')
        chunks=data.get('parts',[])
        parts=self.parts.setdefault(ident,{})
        for chunk in chunks:
            start,length=chunk['start'],chunk['duration']
            if not isinstance(start,(int,float)) or not isinstance(length,(int,float)) or start<0 or not 0<length<60: continue
            data=base64.b64decode(chunk['data'],validate=True)
            if len(data)>2*1024*1024: raise RuntimeError('upload_chunk_limit')
            parts[start]={'start':start,'end':start+length,'data':data}

    def cleanup(self):
        now=time.time()
        files=[p for p in self.cache.glob('*.ts') if re.fullmatch(r'[A-Za-z0-9_-]{11}-\d+\.ts',p.name)]
        size=sum(p.stat().st_size for p in files)
        for p in sorted(files,key=lambda p:p.stat().st_mtime):
            st=p.stat()
            if size>64*1024*1024 or now-st.st_mtime>3600:
                p.unlink();size-=st.st_size

    def segment(self, ident, n):
        with self.lock:
            entry=self.player(ident)
            start=n*STEP;end=min(start+STEP,entry['length'])
            if not isinstance(n,int) or n<0 or start>=entry['length']: raise RuntimeError('segment_out_of_range')
            target=self.cache/('%s-%d.ts'%(ident,n))
            if target.exists() and time.time()-target.stat().st_mtime<3600:
                return target.read_bytes()
            for attempt in range(4):
                parts=self.parts.setdefault(ident,{})
                for key,p in list(parts.items()):
                    if p['end']<start-12 or p['start']>end+18: del parts[key]
                cursor=start;selected=[]
                for p in sorted(parts.values(),key=lambda p:p['start']):
                    if p['end']<=cursor+0.001 or p['start']>=end: continue
                    if p['start']>cursor+0.06: break
                    selected.append(p);cursor=p['end']
                    if cursor>=end-0.06: break
                if cursor>=end-0.06 and selected: break
                self.fetch(ident,entry,cursor+0.01 if cursor>start else start)
            else: raise RuntimeError('upload_interval_incomplete')
            with tempfile.TemporaryDirectory(prefix='part-',dir=self.cache) as temp:
                root=Path(temp);lines=[]
                for i,p in enumerate(selected):
                    name='%d.mp4'%i;(root/name).write_bytes(p['data'])
                    lines.extend(["file '%s'"%name,'duration %.9f'%(p['end']-p['start'])])
                (root/'parts.txt').write_text('\n'.join(lines)+'\n')
                out=root/'out.ts'
                args=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','concat','-safe','1',
                    '-i',str(root/'parts.txt'),'-ss','%.9f'%(start-selected[0]['start']),
                    '-t','%.9f'%(end-start),'-map','0:a:0','-c:a','copy',
                    '-output_ts_offset','%.6f'%(start+1),'-avoid_negative_ts','disabled',
                    '-muxdelay','0','-muxpreload','0','-f','mpegts',str(out)]
                converted=subprocess.run(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=20)
                if converted.returncode or not out.exists() or out.stat().st_size<188: raise RuntimeError('upload_conversion_failed')
                out.replace(target)
            data=target.read_bytes();self.cleanup()
            return data

    def playlist(self, ident):
        entry=self.player(ident)
        self.segment(ident,0)
        return manifest(ident,entry['length'])
