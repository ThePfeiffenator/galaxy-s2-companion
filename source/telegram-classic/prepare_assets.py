from pathlib import Path
import shutil,json,hashlib
P=Path(__file__).resolve().parent
APP=P.parent/'telegram-s2-client'
REF=P/'reference-decoded/res'
names=['ic_launcher.png','msg_in.9.png','msg_out.9.png','ic_send.png','ic_ab_attach.png','ic_msg_panel_smiles.png','msg_check.png','ic_ab_back.png','ic_ab_other.png','dialogs_badge.9.png','compose_panel.9.png']
copied=[]
for density in ['mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi']:
    source=REF/('drawable-'+density+'-v4')
    dest=APP/'res'/('drawable-'+density)
    dest.mkdir(parents=True,exist_ok=True)
    for name in names:
        f=source/name
        if not f.exists():continue
        target=dest/('classic_'+name)
        shutil.copyfile(f,target)
        copied.append({'reference':str(f.relative_to(P)),'destination':str(target.relative_to(APP)),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
wallpaper=REF/'drawable-mdpi-v4/background_hd.jpg'
dest=APP/'res/drawable-nodpi/classic_wallpaper.jpg'
dest.parent.mkdir(parents=True,exist_ok=True)
shutil.copyfile(wallpaper,dest)
copied.append({'reference':str(wallpaper.relative_to(P)),'destination':str(dest.relative_to(APP)),'sha256':hashlib.sha256(wallpaper.read_bytes()).hexdigest()})
(P/'asset-receipt.json').write_text(json.dumps(copied,indent=2)+'\n',encoding='utf-8')
styles=APP/'res/values/styles.xml'
styles.parent.mkdir(exist_ok=True)
styles.write_text('''<resources>
 <style name="ClassicTelegram" parent="android:style/Theme.Holo.Light.DarkActionBar">
  <item name="android:actionBarStyle">@style/ClassicActionBar</item>
  <item name="android:windowBackground">@android:color/white</item>
  <item name="android:colorBackground">@android:color/white</item>
 </style>
 <style name="ClassicActionBar" parent="android:style/Widget.Holo.Light.ActionBar.Solid.Inverse">
  <item name="android:background">#527da3</item>
 </style>
</resources>\n''',encoding='utf-8')
print('Copied',len(copied),'reference assets without redrawing them.')
