"""Process the CDN URL using the official yt-dlp player parser, on Pixel only."""
from urllib.parse import parse_qs, urlsplit
import copy, threading, time
_context_lock=threading.Lock()
_context_value=None
_context_time=0

class Quiet:
    def debug(self, *args): pass
    def warning(self, *args): pass
    def error(self, *args): pass

def context():
    global _context_value,_context_time
    with _context_lock:
        if _context_value and time.monotonic()-_context_time<3600:
            return copy.deepcopy(_context_value)
        _context_value=_context()
        _context_time=time.monotonic()
        return copy.deepcopy(_context_value)

def _context():
    from yt_dlp import YoutubeDL
    from yt_dlp.extractor.youtube._base import INNERTUBE_CLIENTS
    with YoutubeDL({'quiet':True,'no_warnings':True,'logger':Quiet(),
                    'js_runtimes':{'node':{}},'socket_timeout':15}) as ydl:
        ie=ydl.get_info_extractor('Youtube');ie.initialize()
        player_url=ie._download_player_url('music',fatal=True)
        timestamp=ie._extract_signature_timestamp('music',player_url,fatal=True)
        client=dict(INNERTUBE_CLIENTS['tv']['INNERTUBE_CONTEXT']['client'])
        return {'context':{'client':client},
                'playbackContext':{'contentPlaybackContext':{'signatureTimestamp':timestamp}}},player_url

def prepare(ident, player):
    from yt_dlp import YoutubeDL
    from yt_dlp.extractor.youtube._video import JsChallengeRequest, JsChallengeType, NChallengeInput
    from yt_dlp.utils import update_url_query
    url = player['streamingData']['serverAbrStreamingUrl']
    challenge = parse_qs(urlsplit(url).query).get('n', [None])[0]
    if not challenge:
        return
    with YoutubeDL({'quiet':True,'no_warnings':True,'logger':Quiet(),
                    'js_runtimes':{'node':{}},'socket_timeout':15}) as ydl:
        ie=ydl.get_info_extractor('Youtube')
        ie.initialize()
        player_url=player.get('assets',{}).get('js') or ie._download_player_url(ident, fatal=True)
        request=JsChallengeRequest(type=JsChallengeType.N,video_id=ident,
            input=NChallengeInput(challenges=[challenge],player_url=player_url))
        result=None
        for _, response in ie._jsc_director.bulk_solve([request]):
            result=response.output.results.get(challenge)
        if not result:
            raise RuntimeError('signature_unavailable')
        player['streamingData']['serverAbrStreamingUrl']=update_url_query(url,{'n':result})
