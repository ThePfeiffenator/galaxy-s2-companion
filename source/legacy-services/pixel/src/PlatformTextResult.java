package org.retrobridge.galaxyservices.pixel;
import android.content.*;
public final class PlatformTextResult extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){if(i.getData()==null||!"galaxy-text-result".equals(i.getData().getScheme()))return;String[] key=i.getData().getSchemeSpecificPart().split(":");if(key.length!=2||!key[0].matches("[0-9a-f]{64}")||!key[1].matches("[0-9]{1,3}"))return;PlatformText.get(c).result(key[0],Integer.parseInt(key[1]),getResultCode());}
}
