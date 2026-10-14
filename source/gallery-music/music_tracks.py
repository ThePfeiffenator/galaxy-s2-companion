"""Public track metadata and conservative stale-release resolution on the Pixel."""
import json, re, threading, time, unicodedata
from urllib.parse import urlsplit

VIDEO = re.compile(r'^[A-Za-z0-9_-]{11}$')

def normalized(text):
    return ' '.join(unicodedata.normalize('NFKC', text or '').casefold().split())

def matching_release(song, results):
    matches = {}
    for item in results:
        ident = item.get('videoId', '')
        artists = {normalized(a.get('name')) for a in item.get('artists', [])}
        if not VIDEO.fullmatch(ident) or ident == song['id']:
            continue
        if normalized(item.get('title')) != normalized(song['title']) or normalized(song['artist']) not in artists:
            continue
        duration = item.get('duration_seconds')
        if not isinstance(duration, (int, float)) or not song.get('duration_ms') or abs(duration * 1000 - song['duration_ms']) > 2000:
            continue
        matches[ident] = item
    return next(iter(matches.values())) if len(matches) == 1 else None

def safe_art(url):
    parsed = urlsplit(url)
    return parsed.scheme == 'https' and parsed.hostname in ('i.ytimg.com', 'i9.ytimg.com', 'yt3.googleusercontent.com', 'lh3.googleusercontent.com') and parsed.port in (None, 443) and not parsed.username and not parsed.password

class Tracks:
    def __init__(self, home, state):
        self.home, self.state = home, state
        self.lock = threading.RLock()
        self.cache = {}
        from music_uploads import Uploads
        self.uploads = Uploads(home, state)
        path = home/'resolved.json'
        if path.exists() and path.stat().st_size < 16*1024*1024:
            try:
                self.cache = json.loads(path.read_text())
            except (ValueError, OSError):
                pass

    def resolve(self, ident):
        if not VIDEO.fullmatch(ident):
            raise RuntimeError('invalid_track')
        with self.state.lock:
            song = next((s for s in (self.state.cached or {}).get('songs', []) if s['id'] == ident), None)
        if song is None:
            raise RuntimeError('unknown_track')
        if not song.get('public_playback'):
            return self.uploads.describe(ident)
        with self.lock:
            cached = self.cache.get(ident)
            if cached and time.time() - cached.get('checked', 0) < 86400:
                return cached
            from ytmusicapi import YTMusic
            import requests
            class Session(requests.Session):
                def request(self, method, url, **kwargs):
                    kwargs['timeout'] = (5, 8)
                    return super().request(method, url, **kwargs)
            api = YTMusic(requests_session=Session())
            player = api.get_song(ident)
            good = player.get('playabilityStatus', {}).get('status') == 'OK'
            results = []
            try:
                results = api.search(song['title'] + ' ' + song['artist'], filter='songs', limit=5)[:10]
            except Exception:
                pass
            meta = next((s for s in results if s.get('videoId') == ident), None)
            play_id = ident
            if not good:
                meta = matching_release(song, results)
                if meta is None:
                    raise RuntimeError('track_unavailable')
                play_id = meta['videoId']
                if api.get_song(play_id).get('playabilityStatus', {}).get('status') != 'OK':
                    raise RuntimeError('track_unavailable')
            art = 'https://i.ytimg.com/vi/' + play_id + '/hqdefault.jpg'
            album = ''
            if meta:
                album = (meta.get('album') or {}).get('name') or ''
                images = [x for x in meta.get('thumbnails', []) if safe_art(x.get('url', ''))]
                if images:
                    art = max(images, key=lambda x:x.get('width', 0)).get('url')
                    # These are public image sizing parameters from YouTube Music.
                    art = re.sub(r'=w\d+-h\d+', '=w480-h480', art)
            result = {'source_id':ident, 'play_id':play_id, 'album':album, 'art':art,
                      'replacement':play_id != ident, 'checked':time.time()}
            self.cache[ident] = result
            from music_server import atomic_json
            atomic_json(self.home/'resolved.json', self.cache)
            return result

    def artwork(self, ident):
        if not VIDEO.fullmatch(ident):
            raise RuntimeError('invalid_track')
        with self.state.lock:
            song = next((s for s in (self.state.cached or {}).get('songs', []) if s['id'] == ident), None)
            if song is None:
                raise RuntimeError('unknown_track')
        # Artwork prefetch must not trigger hundreds of track-resolution searches.
        with self.lock:
            entry = self.cache.get(ident) or {}
        album_art = self.albums.art(ident) if hasattr(self,'albums') else ''
        url = (self.uploads.art(ident) if not song.get('public_playback') else
               album_art or entry.get('art') or 'https://i.ytimg.com/vi/' + ident + '/hqdefault.jpg')
        if not safe_art(url):
            raise RuntimeError('artwork_unavailable')
        import requests
        with requests.get(url, timeout=(5, 10), stream=True, allow_redirects=False) as response:
            if response.status_code != 200:
                raise RuntimeError('artwork_unavailable')
            parts, size = [], 0
            for chunk in response.iter_content(32768):
                size += len(chunk)
                if size > 2*1024*1024:
                    raise RuntimeError('artwork_limit')
                parts.append(chunk)
            return b''.join(parts)
