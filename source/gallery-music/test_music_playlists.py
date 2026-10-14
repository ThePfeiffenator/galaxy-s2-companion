"""Synthetic, offline saved-playlist parsing/pagination and catalog checks."""
import unittest
from music_playlists import SavedPlaylists
from music_server import GoogleLibrary

def playlist(ident,name='Saved list'):
    return {'tileRenderer':{'contentType':'TILE_CONTENT_TYPE_PLAYLIST',
        'onSelectCommand':{'browseEndpoint':{'browseId':'VL'+ident}},
        'metadata':{'tileMetadataRenderer':{'title':{'runs':[{'text':name}]}}}}}
def track(ident,pid='PL_SAVED'):
    return {'tileRenderer':{'onSelectCommand':{'watchEndpoint':{'videoId':ident,'playlistId':pid}}}}
def page(items,next_token=None):
    grid={'items':items}
    if next_token:grid['continuations']=[{'nextContinuationData':{'continuation':next_token}}]
    return {'contents':{'gridRenderer':grid}}
class Fake(SavedPlaylists):
    def __init__(self,pages):self.responses=iter(pages);self.bodies=[]
    def request(self,body):self.bodies.append(body);return next(self.responses)
class Checks(unittest.TestCase):
    def test_selected_library_only_and_no_duplicate_likes_or_menu_lists(self):
        selected=page([playlist('LM'),playlist('PL_SAVED'),playlist('PL_SAVED')])
        selected['onLongPressCommand']=playlist('PL_MENU')
        tabs=[{'tabRenderer':{'selected':False,'content':page([playlist('PL_HIDDEN')])}},
              {'tabRenderer':{'selected':True,'content':selected}}]
        result=Fake([{'contents':tabs}]).playlists()
        self.assertEqual([{'id':'PL_SAVED','name':'Saved list'}],result)
    def test_paged_tracks_preserve_repeats_and_skip_recommendations(self):
        api=Fake([page([track('AAAAAAAAAAA'),track('BBBBBBBBBBB')],'NEXT'),
                  page([track('AAAAAAAAAAA'),track('CCCCCCCCCCC','PL_OTHER'),track('../invalid')])])
        self.assertEqual(['AAAAAAAAAAA','BBBBBBBBBBB','AAAAAAAAAAA'],api.tracks('PL_SAVED'))
        self.assertEqual({'continuation':'NEXT'},api.bodies[1])
    def test_reload_cursor_is_not_next_page(self):
        data=page([playlist('PL_SAVED')]);data['reloadContinuationData']={'continuation':'RELOAD'}
        api=Fake([data]);self.assertEqual(1,len(api.playlists()));self.assertEqual(1,len(api.bodies))
    def test_cycle_does_not_silently_truncate(self):
        with self.assertRaisesRegex(RuntimeError,'pagination_loop'):
            Fake([page([],'LOOP'),page([],'LOOP')]).playlists()
    def test_unknown_layout_and_multiple_cursors_fail(self):
        with self.assertRaisesRegex(RuntimeError,'layout_changed'):Fake([{'newLayout':[]}]).playlists()
        data=page([],'A');data['extra']={'nextContinuationData':{'continuation':'B'}}
        with self.assertRaisesRegex(RuntimeError,'layout_changed'):Fake([data]).playlists()
    def test_invalid_playlist_fails_before_request(self):
        api=Fake([])
        with self.assertRaisesRegex(RuntimeError,'invalid_playlist'):api.tracks('../private')
        self.assertEqual([],api.bodies)
    def test_catalog_merges_owned_saved_order_and_unavailable_tracks(self):
        saved=Fake([page([playlist('PL_OWNED'),playlist('PL_SAVED')]),
                    page([track('BBBBBBBBBBB'),track('AAAAAAAAAAA'),track('BBBBBBBBBBB'),track('XXXXXXXXXXX')])])
        class Library(GoogleLibrary):
            def __init__(self):self.calls=0;self.deadline=0
            def saved_library(self):return saved
            def get(self,endpoint,params):
                def v(key):return {'id':key,'snippet':{'title':'Synthetic song','channelTitle':'Synthetic artist','categoryId':'10'},'contentDetails':{'duration':'PT2M'},'status':{'privacyStatus':'public'}}
                if endpoint=='playlists':return {'items':[{'id':'PL_OWNED','snippet':{'title':'Owned'}}]}
                if endpoint=='playlistItems':return {'items':[{'contentDetails':{'videoId':'AAAAAAAAAAA'}}]}
                if 'myRating' in params:return {'items':[v('AAAAAAAAAAA')]}
                return {'items':[v(key) for key in params['id'].split(',') if key!='XXXXXXXXXXX']}
        data=Library().catalog()
        self.assertEqual(['liked','PL_OWNED','PL_SAVED'],[p['id'] for p in data['playlists']])
        self.assertEqual(['BBBBBBBBBBB','AAAAAAAAAAA','BBBBBBBBBBB'],data['playlists'][-1]['tracks'])
        self.assertTrue(data['capabilities']['saved_other_playlists'])
        self.assertFalse(data['capabilities']['account_writes'])
        self.assertEqual(2,len(data['songs']))

if __name__=='__main__':unittest.main()
