"""Read saved YouTube Music playlists with the owner's existing OAuth session.

Only browse requests are implemented. This is an unofficial TV browse adapter;
responses stay in the owner's private catalog and upstream layouts may change.
"""
import re,time

PLAYLIST=re.compile(r'^[A-Za-z0-9_-]{2,160}$')
VIDEO=re.compile(r'^[A-Za-z0-9_-]{11}$')
MAX_ITEMS=15000

def nodes(value, key):
    """Walk only the selected content, excluding menus and inactive tab data."""
    if isinstance(value,dict):
        if 'tabRenderer' in value:
            tab=value['tabRenderer']
            if tab.get('selected') is True:
                yield from nodes(tab.get('content',{}),key)
            return
        for name,child in value.items():
            if name in ('onLongPressCommand','menu','reloadContinuationData','trackingParams','clickTrackingParams'):
                continue
            if name==key:yield child
            else:yield from nodes(child,key)
    elif isinstance(value,list):
        for child in value:yield from nodes(child,key)

def continuation(data):
    tokens=[]
    for value in nodes(data,'nextContinuationData'):
        token=value.get('continuation')
        if token and token not in tokens:tokens.append(token)
    for value in nodes(data,'continuationItemRenderer'):
        token=value.get('continuationEndpoint',{}).get('continuationCommand',{}).get('token')
        if token and token not in tokens:tokens.append(token)
    if len(tokens)>1:raise RuntimeError('saved_playlist_layout_changed')
    if tokens and (not isinstance(tokens[0],str) or len(tokens[0])>32768):
        raise RuntimeError('saved_playlist_invalid_cursor')
    return tokens[0] if tokens else None

def title(tile):
    text=tile.get('metadata',{}).get('tileMetadataRenderer',{}).get('title',{})
    result=text.get('simpleText') or ''.join(r.get('text','') for r in text.get('runs',[]))
    return result[:1000] if isinstance(result,str) else ''

class SavedPlaylists:
    def __init__(self, library):self.library=library

    def request(self, body):
        from yt_dlp.extractor.youtube._base import INNERTUBE_CLIENTS
        lib=self.library
        lib.calls+=1
        if lib.calls>500 or (lib.deadline and time.monotonic()>lib.deadline):raise RuntimeError('catalog_limit')
        body=dict(body,context=INNERTUBE_CLIENTS['tv']['INNERTUBE_CONTEXT'])
        for attempt in range(2):
            response=lib.session.post('https://www.youtube.com/youtubei/v1/browse',
                headers={'Authorization':'Bearer '+lib.token(force=attempt==1)},json=body,
                timeout=(10,30),allow_redirects=False)
            if response.status_code==401 and attempt==0:continue
            if response.status_code!=200:raise RuntimeError('saved_playlists_unavailable')
            if len(response.content)>16*1024*1024:raise RuntimeError('catalog_limit')
            data=response.json()
            if not isinstance(data,dict) or data.get('error'):raise RuntimeError('saved_playlists_unavailable')
            return data
        raise RuntimeError('sign_in_required')

    def pages(self, browse_id):
        body={'browseId':browse_id};seen=set();count=0
        for page in range(100):
            data=self.request(body)
            if not any(k in data for k in ('contents','continuationContents','onResponseReceivedActions','onResponseReceivedEndpoints')):
                raise RuntimeError('saved_playlist_layout_changed')
            tiles=list(nodes(data,'tileRenderer'));count+=len(tiles)
            if count>MAX_ITEMS:raise RuntimeError('catalog_limit')
            yield tiles
            token=continuation(data)
            if not token:return
            if token in seen:raise RuntimeError('pagination_loop')
            seen.add(token);body={'continuation':token}
        raise RuntimeError('catalog_limit')

    def playlists(self):
        result=[];seen=set()
        for tiles in self.pages('FEmusic_liked_playlists'):
            for tile in tiles:
                if tile.get('contentType')!='TILE_CONTENT_TYPE_PLAYLIST':continue
                browse=tile.get('onSelectCommand',{}).get('browseEndpoint',{}).get('browseId','')
                if not isinstance(browse,str) or not browse.startswith('VL'):continue
                pid=browse[2:]
                # The existing Liked songs entry already represents LM/LL.
                if pid in ('LM','LL') or pid in seen or not PLAYLIST.fullmatch(pid):continue
                name=title(tile)
                if not name:continue
                seen.add(pid);result.append({'id':pid,'name':name})
        return result

    def tracks(self, pid):
        if not PLAYLIST.fullmatch(pid):raise RuntimeError('invalid_playlist')
        result=[]
        for tiles in self.pages('VL'+pid):
            for tile in tiles:
                endpoint=tile.get('onSelectCommand',{}).get('watchEndpoint',{})
                ident=endpoint.get('videoId','')
                # Ignore unrelated recommendations; preserve intentional repeats.
                if endpoint.get('playlistId')==pid and VIDEO.fullmatch(ident):result.append(ident)
        return result
