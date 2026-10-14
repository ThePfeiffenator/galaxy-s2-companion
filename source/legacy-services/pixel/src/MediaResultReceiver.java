package org.retrobridge.galaxyservices.pixel;
import android.content.*;
public final class MediaResultReceiver extends BroadcastReceiver {
 public void onReceive(Context c,Intent i){if(i.getData()==null||!"galaxy-media-result".equals(i.getData().getScheme()))return;String id=i.getData().getSchemeSpecificPart();if(!id.matches("[0-9a-f]{32}"))return;try{MediaRelay.get(c).result(id,getResultCode());}catch(Exception e){android.util.Log.w("GalaxyMedia","Result persistence unavailable");}}
}
