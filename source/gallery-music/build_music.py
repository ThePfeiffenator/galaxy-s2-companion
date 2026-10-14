import os
from pathlib import Path
import hashlib, json, re, shutil, subprocess
P=Path(__file__).resolve().parent;W=P.parent;D=P/'candidate';SRC=P/'music-clean'
J=Path(os.environ['JAVA_HOME']+'/bin')
SDK=Path(os.environ['ANDROID_SDK_ROOT']);BT=SDK/'build-tools/36.1.0'
AND=W/'telegram-s2-client/sdk/platform/android-4.0.4/android.jar'
OLD='com.google.android.music';NEW='org.retrobridge.playmusic2012';C='Lorg/retrobridge/playmusic2012/galaxy/MusicBridge;'
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=240)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
for f in SRC.rglob('*'):
 if not f.is_file() or 'build' in f.relative_to(SRC).parts:continue
 target=D/f.relative_to(SRC);target.parent.mkdir(parents=True,exist_ok=True)
 if f.suffix in ('.smali','.xml','.yml') and 'original' not in f.relative_to(SRC).parts:
  text=f.read_text(encoding='utf-8').replace(OLD,NEW).replace(OLD.replace('.','/'),NEW.replace('.','/'))
  target.write_text(text,encoding='utf-8')
 else:shutil.copyfile(f,target)
base='smali/com/google/android/music/'
def edit(rel,fn):
 file=D/rel;old=file.read_text(encoding='utf-8');new=fn(old);assert new!=old,rel;file.write_text(new,encoding='utf-8')
def method(text,signature,fn):
 pattern=r'(?m)^\.method '+re.escape(signature)+r'\n.*?^\.end method'
 result,n=re.subn(pattern,lambda m:fn(m.group()),text,flags=re.S|re.M);assert n==1,signature;return result
def fixed(text,signature,value):
 return method(text,signature,lambda old:'.method '+signature+'\n    .locals 1\n    const/4 v0, '+value+'\n    return v0\n.end method')
def activity(s):
 s=method(s,'public onCreate(Landroid/os/Bundle;)V',lambda m:m.replace('    invoke-super {p0, p1}, Landroid/support/v4/app/FragmentActivity;->onCreate(Landroid/os/Bundle;)V','    invoke-super {p0, p1}, Landroid/support/v4/app/FragmentActivity;->onCreate(Landroid/os/Bundle;)V\n    invoke-static {p0}, '+C+'->start(Landroid/content/Context;)V'))
 s=method(s,'public onCreateOptionsMenu(Landroid/view/Menu;)Z',lambda m:m.replace('    .prologue','    invoke-static {p1}, '+C+'->addMenu(Landroid/view/Menu;)V\n    .prologue',1))
 s=method(s,'public onOptionsItemSelected(Landroid/view/MenuItem;)Z',lambda m:m.replace('    .prologue','    invoke-static {p0, p1}, '+C+'->menu(Landroid/content/Context;Landroid/view/MenuItem;)Z\n    move-result v0\n    if-eqz v0, :galaxy_original_menu\n    return v0\n    :galaxy_original_menu\n    .prologue',1))
 return s
edit(base+'activitymanagement/TopLevelActivity.smali',activity)
def source(s):
 def patch(m):
  needle='    iput-object v0, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mCurrentSongSourceId:Ljava/lang/String;'
  assert m.count(needle)==1
  addition='''
    iget-object v5, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mContext:Landroid/content/Context;
    invoke-static {v5, v0}, Lorg/retrobridge/playmusic2012/galaxy/MusicBridge;->stream(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :galaxy_original_source
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v5
    if-nez v5, :galaxy_source_ready
    const/4 v4, 0x0
    goto/16 :goto_7
    :galaxy_source_ready
    iput-object v0, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mCurrentUrl:Ljava/lang/String;
    const/16 v5, 0x20
    invoke-direct {p0, v5}, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->addStatus(I)V
    iget-object v5, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mContext:Landroid/content/Context;
    iget-object v6, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mCurrentSongSourceId:Ljava/lang/String;
    invoke-static {v5, v6}, Lorg/retrobridge/playmusic2012/galaxy/MusicBridge;->duration(Landroid/content/Context;Ljava/lang/String;)J
    move-result-wide v5
    iput-wide v5, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mDuration:J
    const-wide/16 v3, 0x0
    cmp-long v3, v5, v3
    if-lez v3, :galaxy_unknown_duration
    const/4 v3, 0x1
    goto :galaxy_duration_ready
    :galaxy_unknown_duration
    const/4 v3, 0x0
    :galaxy_duration_ready
    const/4 v4, 0x0
    goto/16 :goto_5
    :galaxy_original_source
'''
  return m.replace(needle,needle+addition)
 return method(s,'private setDataSourceImpl(ZLorg/retrobridge/playmusic2012/player/AsyncMediaPlayer$AsyncCommandCallback;)V',patch)
edit(base+'player/AsyncMediaPlayer.smali',source)

def pixel_seek(s):
 def patch(m):
  needle='    invoke-virtual {p0}, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->isStreaming()Z'
  assert m.count(needle)==1
  prefix='''    iget-object v1, p0, Lorg/retrobridge/playmusic2012/player/AsyncMediaPlayer;->mCurrentSongSourceId:Ljava/lang/String;
    if-eqz v1, :galaxy_original_seek
    const-string v2, "yt:"
    invoke-virtual {v1, v2}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v1
    if-nez v1, :cond_2
    :galaxy_original_seek
'''
  return m.replace(needle,prefix+needle)
 return method(s,'private seekImpl(I)V',patch)
edit(base+'player/AsyncMediaPlayer.smali',pixel_seek)
def artwork(s):
 def patch(m):
  inject='''    iget-object v8, p0, Lorg/retrobridge/playmusic2012/dl/artwork/ArtDownloadExecutor$ArtDownloadTask;->mLocalLocationFullPath:Ljava/io/File;
    invoke-static {p1, v8}, Lorg/retrobridge/playmusic2012/galaxy/MusicBridge;->downloadArt(Ljava/lang/String;Ljava/io/File;)Z
    move-result v8
    if-eqz v8, :galaxy_original_art
    iput-boolean v8, p0, Lorg/retrobridge/playmusic2012/dl/artwork/ArtDownloadExecutor$ArtDownloadTask;->mDownloadSuccessful:Z
    return-void
    :galaxy_original_art
'''
  return m.replace('    .prologue',inject+'    .prologue',1)
 return method(s,'private streamFromCloud(Ljava/lang/String;)V',patch)
edit(base+'dl/artwork/ArtDownloadExecutor$ArtDownloadTask.smali',artwork)
def art_completed(s):
 def patch(m):
  needle='    if-nez p3, :cond_2'
  assert m.count(needle)==1
  # A failed task is not a changed cover. Broadcasting it requeues the same
  # missing image through the receiver, indefinitely invalidating the UI.
  return m.replace(needle,needle+'\n    if-nez p2, :cond_2\n    invoke-virtual {v1}, Lorg/retrobridge/playmusic2012/dl/artwork/ArtDownloadExecutor$ArtDownloadTask;->wasDownloadSuccessful()Z\n    move-result v3\n    if-eqz v3, :cond_2')
 return method(s,'private declared-synchronized afterExecute(Ljava/lang/Runnable;Ljava/lang/Throwable;Z)V',patch)
edit(base+'dl/artwork/ArtDownloadExecutor.smali',art_completed)
def art_provider(s):
 return method(s,'public openFile(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;',lambda m:m.replace('    move-result v10','    move-result v10\n    invoke-static {v4, v8, v9, v10}, '+C+'->hasArtwork(Lorg/retrobridge/playmusic2012/store/Store;JZ)Z\n    move-result v10',1))
edit(base+'store/MusicContentProvider.smali',art_provider)
# Keep unassigned uploads/videos available after the named albums, instead of
# letting empty SQL strings fill the first several screens of the Albums tab.
edit(base+'store/MusicContentProvider.smali',lambda s:s.replace('"MUSIC.CanonicalAlbum"','"CASE WHEN MUSIC.Album IS NULL OR MUSIC.Album = \'\' THEN 1 ELSE 0 END, MUSIC.CanonicalAlbum"'))
def art_ready_filter(s):
 def patch(m):
  # The sender includes a content:// album URI. An action-only filter rejects
  # that broadcast, leaving the placeholder until the activity is recreated.
  needle='    invoke-virtual {v0, v1}, Landroid/content/IntentFilter;->addAction(Ljava/lang/String;)V'
  assert m.count(needle)==1
  return m.replace(needle,needle+'\n    const-string v1, "content"\n    invoke-virtual {v0, v1}, Landroid/content/IntentFilter;->addDataScheme(Ljava/lang/String;)V\n    const-string v1, "vnd.android.cursor.item/vnd.google.music.album"\n    invoke-virtual {v0, v1}, Landroid/content/IntentFilter;->addDataType(Ljava/lang/String;)V')
 return method(s,'private createArtChangedBroadcaseReceiver(Landroid/content/Context;)V',patch)
edit(base+'dl/artwork/ArtDownloadServiceConnection.smali',art_ready_filter)
edit(base+'utils/AlbumArtUtils.smali',lambda s:method(s,'private static resizeHelper(Landroid/graphics/Bitmap;IIZ)Landroid/graphics/Bitmap;',lambda m:'.method private static resizeHelper(Landroid/graphics/Bitmap;IIZ)Landroid/graphics/Bitmap;\n    .locals 1\n    invoke-static {p0, p1, p2, p3}, '+C+'->fitArtwork(Landroid/graphics/Bitmap;IIZ)Landroid/graphics/Bitmap;\n    move-result-object v0\n    return-object v0\n.end method'))
def art_trace(s):
 for signature in ['private updateQueueImpl(Landroid/content/Intent;)V','private updateTrackInfoImpl(Landroid/content/Intent;)V']:
  s=method(s,signature,lambda m:m.replace('    .prologue','    invoke-static {p1}, '+C+'->traceArtwork(Landroid/content/Intent;)V\n    .prologue',1))
 return s
edit(base+'MediaPlaybackActivity.smali',art_trace)
edit(base+'dl/artwork/ArtDownloadServiceConnection$AlbumArtChangedBroadcastReceiver.smali',lambda s:method(s,'public onReceive(Landroid/content/Context;Landroid/content/Intent;)V',lambda m:m.replace('    .prologue','    invoke-static {p2}, '+C+'->traceArtwork(Landroid/content/Intent;)V\n    .prologue',1)))
edit(base+'store/Store.smali',lambda s:method(s,'public requiresDownloadManager([J)[J',lambda m:m.replace('"SourceAccount != 0"','"SourceAccount != 0 AND SourceAccount != 9001"')))
edit(base+'jumper/MusicPreferences.smali',lambda s:fixed(fixed(fixed(s,'public wasTutorialViewed()Z','0x1'),'public isStreamingEnabled()Z','0x1'),'public isOfflineFeatureAvailable()Z','0x0'))
def manifest(s):
 s=re.sub(r'\s*<original-package[^>]*/>','',s)
 s=s.replace('<application ','<application android:debuggable="true" android:allowBackup="false" ',1)
 s=s.replace('</application>','<service android:name="'+NEW+'.galaxy.MusicBridge" android:exported="false" android:process=":main"/></application>')
 # This clone uses no retired Google account sync adapter or cloud tickle receiver.
 for name in ['sync.SyncAdapterService','sync.google.TickleReceiver','tutorial.SignupTickleReceiver','AccountsBroadcastReceiver']:
  s=s.replace('android:name="'+NEW+'.'+name+'"','android:enabled="false" android:name="'+NEW+'.'+name+'"')
 return s
edit('AndroidManifest.xml',manifest)
edit('apktool.yml',lambda s:s.replace('versionCode: 615','versionCode: 628').replace('versionName: 4.3.615.481147','versionName: 4.3.615-Pixel-preview13'))

stub=P/'bridge-stubs/org/retrobridge/playmusic2012/store';stub.mkdir(parents=True,exist_ok=True)
(stub/'Store.java').write_text('package org.retrobridge.playmusic2012.store; import android.content.*;import android.database.sqlite.*; public class Store {public static Store getInstance(Context c){return null;} public SQLiteDatabase beginWriteTxn(){return null;} public void endWriteTxn(SQLiteDatabase db,boolean ok){} public SQLiteDatabase beginRead(){return null;} public void endRead(SQLiteDatabase db){} public String getRemoteArtLocationForAlbum(long id){return null;}}')
setters={'SourceAccount':'int','SourceId':'String','SourceVersion':'String','NeedsSync':'boolean','Title':'String','TrackArtist':'String','AlbumArtist':'String','AlbumName':'String','DurationInMilliSec':'long','LocalCopyType':'int','MimeType':'String','Rating':'int','AlbumArtLocation':'String'}
stubcode='package org.retrobridge.playmusic2012.store;import android.database.sqlite.*;public class MusicFile { public MusicFile(){} public static SQLiteStatement compileMusicInsertStatement(SQLiteDatabase db){return null;} public static SQLiteStatement compileFullUpdateStatement(SQLiteDatabase db){return null;} public static MusicFile readMusicFile(SQLiteDatabase db,String account,String id,MusicFile f){return null;} public long insertMusicFile(SQLiteStatement s){return 0;} public void updateMusicFile(SQLiteStatement s,SQLiteDatabase db){}'
stubcode+=''.join('public void set'+name+'('+typ+' value){}' for name,typ in setters.items())+'public long getDurationInMilliSec(){return 0;}}'
(stub/'MusicFile.java').write_text(stubcode)
for name in ['bridge-classes','bridge-dex','bridge-smali']:
 target=(P/name).resolve();assert target.parent==P.resolve()
 if target.exists():shutil.rmtree(target)
 target.mkdir()
run([J/'javac.exe','-source','8','-target','8','-encoding','UTF-8','-classpath',AND,'-d',P/'bridge-classes',*list((P/'bridge-src').glob('*.java')),*stub.glob('*.java')])
classes=list((P/'bridge-classes/org/retrobridge/playmusic2012/galaxy').rglob('*.class'))
run([J/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','15','--lib',AND,'--output',P/'bridge-dex',*classes])
run([J/'java.exe','-jar',W/'call-feasibility/baksmali-2.5.2.jar','d',P/'bridge-dex/classes.dex','-o',P/'bridge-smali'])
for file in (P/'bridge-smali').rglob('*.smali'):
 target=D/'smali'/file.relative_to(P/'bridge-smali');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,target)
run([J/'java.exe','-jar',W/'peggle-debug-tools/apktool.jar','b','-p',W/'peggle-debug-tools/framework',D,'-o',P/'music-unsigned.apk'])
run([BT/'zipalign.exe','-f','4',P/'music-unsigned.apk',P/'music-aligned.apk'])
key=W/'signing-private';out=P/'play-music-pixel-preview.apk'
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key/'signing.p12','--ks-pass','file:'+str(key/'signing-password.txt'),'--min-sdk-version','15','--out',out,P/'music-aligned.apk'])
run([J/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--min-sdk-version','15',out])
receipt={'apk':out.name,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'package':NEW,'device_tested':False,'account_catalog_tested':False,'playback_tested':False}
(P/'music-build.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt))
