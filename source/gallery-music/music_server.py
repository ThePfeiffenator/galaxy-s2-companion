"""Private Pixel catalog for the original S2 player. No account-write routes.

Google's official API exposes owned playlists and likes, not the full YTM saved
album/library collection. Preserve that distinction in the returned capabilities.
"""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib, hmac, json, os, re, threading, time
from urllib.parse import urlsplit

HOME = Path.home() / 'galaxy-music'
VIDEO = re.compile(r'^[A-Za-z0-9_-]{11}$')
PLAYLIST = re.compile(r'^[A-Za-z0-9_-]{2,160}$')
MAX_CATALOG = 16 * 1024 * 1024
MAX_TRACKS = 15000

def atomic_json(path, value):
    data = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
    if len(data) > MAX_CATALOG:
        raise RuntimeError('catalog_limit')
    temp = path.with_suffix(path.suffix + '.tmp')
    with open(temp, 'wb') as out:
        out.write(data)
        out.flush()
        os.fsync(out.fileno())
    temp.replace(path)

def seconds(duration):
    match = re.fullmatch(r'P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?', duration or '')
    if not match:
        return 0
    d, h, m, s = (float(x or 0) for x in match.groups())
    return int(d * 86400 + h * 3600 + m * 60 + s)

def normalize_video(item, liked=False):
    video_id = item.get('id', '')
    snippet = item.get('snippet', {})
    details = item.get('contentDetails', {})
    if not VIDEO.fullmatch(video_id) or not snippet.get('title'):
        return None
    return {'id': video_id, 'title': snippet['title'],
            'artist': snippet.get('channelTitle', '').removesuffix(' - Topic'),
            # YouTube Data does not supply an album; never invent an album name.
            'album': '', 'duration_ms': seconds(details.get('duration')) * 1000,
            'liked': bool(liked), 'art': 'https://i.ytimg.com/vi/' + video_id + '/hqdefault.jpg',
            'music': snippet.get('categoryId') == '10',
            'public_playback': item.get('status', {}).get('privacyStatus') != 'private'}

class GoogleLibrary:
    token_lock = threading.RLock()
    def __init__(self, home=HOME, session=None):
        self.home = home
        if session is None:
            import requests
            session = requests.Session()
        self.session = session
        self.calls = 0
        self.deadline = 0

    def token(self, force=False):
        with self.token_lock:
            return self._token(force)

    def _token(self, force=False):
        path = self.home / 'oauth.json'
        data = json.loads(path.read_text())
        if force or data.get('expires_at', 0) < time.time() + 120:
            config = json.loads((self.home / 'client.json').read_text())['installed']
            response = self.session.post('https://oauth2.googleapis.com/token', data={
                'client_id': config['client_id'], 'client_secret': config['client_secret'],
                'refresh_token': data['refresh_token'], 'grant_type': 'refresh_token'}, timeout=(10, 30))
            if response.status_code != 200:
                raise RuntimeError('sign_in_required')
            refreshed = response.json()
            data.update(refreshed)
            data['expires_at'] = int(time.time()) + data['expires_in']
            atomic_json(path, data)
        return data['access_token']

    def get(self, endpoint, params):
        assert endpoint in ('playlists', 'playlistItems', 'videos')
        self.calls += 1
        if self.calls > 500 or (self.deadline and time.monotonic() > self.deadline):
            raise RuntimeError('catalog_limit')
        url = 'https://www.googleapis.com/youtube/v3/' + endpoint
        for attempt in range(2):
            response = self.session.get(url, params=params,
                headers={'Authorization': 'Bearer ' + self.token(force=attempt == 1)},
                timeout=(10, 30), allow_redirects=False)
            if response.status_code == 401 and attempt == 0:
                continue
            if response.status_code != 200:
                # Do not log URLs, tokens, account information, or response bodies.
                reason = ''
                try:
                    reason = response.json()['error']['errors'][0]['reason']
                except (KeyError, IndexError, ValueError, TypeError):
                    pass
                safe = reason if reason in ('quotaExceeded', 'accessNotConfigured', 'playlistNotFound', 'playlistForbidden') else str(response.status_code)
                raise RuntimeError('google_' + safe)
            return response.json()
        raise RuntimeError('sign_in_required')

    def pages(self, endpoint, **params):
        params = dict(params, maxResults=50)
        result = []
        seen = set()
        while True:
            data = self.get(endpoint, params)
            result.extend(data.get('items', []))
            if len(result) > MAX_TRACKS:
                raise RuntimeError('catalog_limit')
            token = data.get('nextPageToken')
            if not token:
                return result
            if token in seen:
                raise RuntimeError('pagination_loop')
            seen.add(token)
            params['pageToken'] = token

    def catalog(self):
        self.calls = 0
        self.deadline = time.monotonic() + 600
        songs = {}
        liked = []
        for item in self.pages('videos', part='snippet,contentDetails,status', myRating='like'):
            track = normalize_video(item, liked=True)
            if track and track['music']:
                songs[track['id']] = track
                liked.append(track['id'])
        playlists = [{'id': 'liked', 'name': 'Liked songs', 'tracks': liked}]
        for item in self.pages('playlists', part='snippet,contentDetails', mine='true'):
            pid = item.get('id', '')
            if not PLAYLIST.fullmatch(pid):
                continue
            entries = self.pages('playlistItems', part='contentDetails', playlistId=pid)
            ids = [entry.get('contentDetails', {}).get('videoId', '') for entry in entries]
            ids = [vid for vid in ids if VIDEO.fullmatch(vid)]
            missing = list(dict.fromkeys(vid for vid in ids if vid not in songs))
            for start in range(0, len(missing), 50):
                data = self.get('videos', {'part': 'snippet,contentDetails,status', 'id': ','.join(missing[start:start+50])})
                for video in data.get('items', []):
                    track = normalize_video(video)
                    if track:
                        songs[track['id']] = track
            if len(songs) > MAX_TRACKS:
                raise RuntimeError('catalog_limit')
            playlists.append({'id': pid, 'name': item.get('snippet', {}).get('title', 'Playlist'),
                              'tracks': [vid for vid in ids if vid in songs]})
        return {'schema': 1, 'updated': int(time.time() * 1000), 'source': 'youtube-data',
                'capabilities': {'owned_playlists': True, 'music_likes': True,
                                 'saved_albums': False, 'saved_other_playlists': False,
                                 'account_writes': False},
                'songs': list(songs.values()), 'playlists': playlists}

class CatalogState:
    def __init__(self, home=HOME):
        self.home = home
        self.lock = threading.Lock()
        self.busy = False
        self.retry_at = 0
        self.error = None
        self.cached = None
        path = home / 'catalog.json'
        if path.exists() and path.stat().st_size <= MAX_CATALOG:
            try:
                value = json.loads(path.read_text())
                if value.get('schema') == 1:
                    self.cached = value
            except (ValueError, OSError):
                pass

    def refresh(self):
        with self.lock:
            stale = self.cached is None or time.time()*1000 - self.cached.get('updated', 0) > 3600000
            if self.busy or time.time() < self.retry_at or not stale:
                return
            self.busy = True
        def run():
            try:
                value = GoogleLibrary(self.home).catalog()
                atomic_json(self.home / 'catalog.json', value)
                with self.lock:
                    self.cached, self.error = value, None
            except Exception as exc:
                message = str(exc) if isinstance(exc, RuntimeError) else 'connection_failed'
                with self.lock:
                    self.error = message if re.fullmatch('[A-Za-z0-9_]+', message) else 'connection_failed'
                    self.retry_at = time.time() + 300
            finally:
                with self.lock:
                    self.busy = False
        threading.Thread(target=run, daemon=True, name='music-catalog').start()

def serve(home=HOME):
    os.umask(0o077)
    home.mkdir(parents=True, exist_ok=True)
    secret = (home / 'relay.key').read_text().strip()
    if not re.fullmatch('[0-9a-f]{64}', secret):
        raise RuntimeError('Relay pairing missing')
    state = CatalogState(home)
    from music_tracks import Tracks
    tracks = Tracks(home, state)
    from music_albums import Albums
    albums = Albums(home, state, tracks.cache, tracks.uploads)
    tracks.albums = albums
    tracks.uploads.albums=albums
    state.refresh()
    albums.start()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_GET(self):
            if not hmac.compare_digest(self.headers.get('X-Galaxy-Music-Key', ''), secret):
                self.send_error(403)
                return
            if self.path == '/health':
                code, value = 200, {'ready': True, 'signed_in': (home/'oauth.json').exists()}
            elif re.fullmatch(r'/sabr_hls_adapter/music/[A-Za-z0-9_-]{11}/index\.m3u8',self.path) or re.fullmatch(r'/sabrhls-audio-fragment/music/[A-Za-z0-9_-]{11}/\d{1,5}\.ts',self.path):
                try:
                    ident=self.path.split('/')[3]
                    playlist=self.path.endswith('.m3u8')
                    data=(tracks.uploads.playlist(ident) if playlist else
                          tracks.uploads.segment(ident,int(self.path.rsplit('/',1)[1][:-3])))
                    self.send_response(200)
                    self.send_header('Content-Type','application/vnd.apple.mpegurl' if playlist else 'video/mp2t')
                    self.send_header('Content-Length',str(len(data)))
                    self.send_header('Cache-Control','no-store')
                    self.send_header('Connection','close')
                    self.end_headers();self.wfile.write(data)
                    return
                except Exception as exc:
                    reason=str(exc) if isinstance(exc,RuntimeError) else 'upload_stream_failed'
                    code,value=503,{'error':reason if re.fullmatch('[a-z_]+',reason) else 'upload_stream_failed'}
            elif self.path == '/catalog':
                state.refresh()
                with state.lock:
                    code, value = (200, state.cached) if state.cached else (503, {'error': state.error or 'loading'})
                if code == 200:
                    value = albums.snapshot(value)
            elif re.fullmatch(r'/resolve/[A-Za-z0-9_-]{11}', self.path):
                try:
                    code, value = 200, tracks.resolve(self.path.rsplit('/', 1)[1])
                except Exception as exc:
                    reason = str(exc) if isinstance(exc, RuntimeError) else 'connection_failed'
                    code, value = 503, {'error':reason if re.fullmatch('[a-z_]+', reason) else 'connection_failed'}
            elif re.fullmatch(r'/art/[A-Za-z0-9_-]{11}\.jpg', self.path):
                try:
                    data = tracks.artwork(self.path.rsplit('/', 1)[1][:-4])
                    self.send_response(200)
                    self.send_header('Content-Type', 'image/jpeg')
                    self.send_header('Content-Length', str(len(data)))
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    self.wfile.write(data)
                    return
                except Exception:
                    code, value = 503, {'error':'artwork_unavailable'}
            else:
                self.send_error(404)
                return
            data = json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
        def setup(self):
            super().setup()
            self.connection.settimeout(20)
    ThreadingHTTPServer(('127.0.0.1', 8769), Handler).serve_forever()

if __name__ == '__main__':
    import sys
    if sys.argv[1:] == ['check']:
        result = GoogleLibrary().catalog()
        atomic_json(HOME/'catalog.json', result)
        print(json.dumps({'songs':len(result['songs']), 'playlists':len(result['playlists']), 'source':result['source']}))
    else:
        serve()
