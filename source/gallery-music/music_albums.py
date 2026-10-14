"""Persist exact YouTube Music album metadata, independently of playback."""
import json, re, threading, time
from music_tracks import VIDEO, safe_art, matching_release

def album_entry(track):
    ident = track.get('videoId', '')
    album = (track.get('album') or {}).get('name') or ''
    if not VIDEO.fullmatch(ident) or not album or len(album) > 1000:
        return None
    images = [x for x in track.get('thumbnail', track.get('thumbnails', [])) if safe_art(x.get('url', ''))]
    art = max(images, key=lambda x:x.get('width', 0))['url'] if images else ''
    art = re.sub(r'=w\d+-h\d+', '=w480-h480', art)
    return {'album':album, 'art':art, 'checked':time.time(), 'status':'matched'}

def lookup_album(api, song, tracks):
    exact = next((album_entry(t) for t in tracks if t.get('videoId') == song['id'] and album_entry(t)), None)
    if exact:
        return exact
    # Retired release IDs often remain in likes, while watch-next silently
    # starts with a different song. Reuse the playback resolver's strict
    # artist, title, duration and unique-result rule only for unavailable IDs.
    results = api.search(song['title']+' '+song['artist'],filter='songs',limit=5)[:10]
    exact = next((album_entry(t) for t in results if t.get('videoId') == song['id'] and album_entry(t)), None)
    if exact:
        return exact
    if api.get_song(song['id']).get('playabilityStatus',{}).get('status') != 'UNPLAYABLE':
        return None
    replacement = matching_release(song, results)
    if not replacement:
        return None
    if api.get_song(replacement['videoId']).get('playabilityStatus',{}).get('status') != 'OK':
        return None
    entry = album_entry(replacement)
    if entry:
        entry['matched_release'] = replacement['videoId']
    return entry

class Albums:
    def __init__(self, home, state, resolved, uploads=None):
        self.home, self.state = home, state
        self.uploads=uploads
        self.lock = threading.Lock()
        self.entries, self.updated = {}, 0
        self.path = home/'albums.json'
        if self.path.exists() and self.path.stat().st_size < 16*1024*1024:
            try:
                saved = json.loads(self.path.read_text())
                if saved.get('schema') == 1:
                    self.entries, self.updated = saved['entries'], saved['updated']
                    self.entries = {k:v for k,v in self.entries.items() if v.get('album') or v.get('lookup_version') == 2}
            except (ValueError, KeyError, OSError):
                pass
        # Already resolved releases are tied to the requested track by the
        # playback resolver, including its conservative stale-release checks.
        for ident, entry in resolved.items():
            if entry.get('album') and ident not in self.entries:
                self.entries[ident] = {k:entry[k] for k in ('album','art','checked')}
                self.entries[ident]['status'] = 'matched'
        if self.entries:
            with self.lock:
                self.save_locked()

    def save_locked(self):
        from music_server import atomic_json
        self.updated = max(self.updated + 1, int(time.time()*1000))
        atomic_json(self.path, {'schema':1, 'updated':self.updated, 'entries':self.entries})

    def art(self, ident):
        with self.lock:
            return (self.entries.get(ident) or {}).get('art', '')

    def snapshot(self, catalog):
        if not catalog:
            return None
        now = time.time()
        with self.lock:
            songs, pending, matched = [], 0, 0
            for source in catalog['songs']:
                song = dict(source)
                entry = self.entries.get(song['id'], {})
                if entry.get('album'):
                    song['album'] = entry['album']
                    if entry.get('upload') and entry.get('artist'):song['artist']=entry['artist']
                    matched += 1
                elif (source.get('public_playback') or self.uploads) and entry.get('status') not in ('not_found', 'failed'):
                    pending += 1
                songs.append(song)
            return dict(catalog, songs=songs, updated=max(catalog['updated'],self.updated),
                        metadata_pending=pending, metadata_matched=matched)

    def start(self):
        threading.Thread(target=self.run,daemon=True,name='music-albums').start()

    def run(self):
        import requests
        from ytmusicapi import YTMusic
        class Session(requests.Session):
            def request(self, method, url, **kwargs):
                kwargs['timeout'] = (5,8)
                return super().request(method,url,**kwargs)
        api = YTMusic(requests_session=Session())
        while True:
            with self.state.lock:
                songs = list((self.state.cached or {}).get('songs',[]))
            now = time.time()
            with self.lock:
                pending = [s for s in songs if (s.get('public_playback') or self.uploads) and
                    (s['id'] not in self.entries or (not self.entries[s['id']].get('album') and
                     self.entries[s['id']].get('retry_at',float('inf')) <= now))]
            if not pending:
                time.sleep(30)
                continue
            song = pending[0]
            try:
                if song.get('public_playback'):
                    result = api.get_watch_playlist(videoId=song['id'],limit=10).get('tracks',[])
                    requested = lookup_album(api, song, result)
                else:
                    result=[]
                    requested=self.uploads.metadata(song)
                known = {s['id'] for s in songs}
                with self.lock:
                    for track in result:
                        ident = track.get('videoId')
                        entry = album_entry(track)
                        if ident in known and entry and not self.entries.get(ident,{}).get('album'):
                            self.entries[ident] = entry
                    if requested:
                        self.entries[song['id']] = requested
                    if not self.entries.get(song['id'],{}).get('album'):
                        self.entries[song['id']] = {'status':'not_found','lookup_version':2,'checked':now,'retry_at':now+7*86400}
                    self.save_locked()
            except Exception:
                with self.lock:
                    attempts = self.entries.get(song['id'],{}).get('attempts',0)+1
                    self.entries[song['id']] = {'status':'retry' if attempts<3 else 'failed',
                        'lookup_version':2,'checked':now,'attempts':attempts,'retry_at':now+(300 if attempts<3 else 86400)}
                    self.save_locked()
                time.sleep(5)
            # One background request at a time; playback uses its own session.
            time.sleep(1)
