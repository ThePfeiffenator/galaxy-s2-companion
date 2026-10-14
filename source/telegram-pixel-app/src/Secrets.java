package org.retrobridge.galaxychat.pixel;
import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
import org.retrobridge.galaxychat.protocol.Wire;

/** Only the Pixel's own app configuration. No access to another Telegram installation. */
final class Secrets {
    private final Context context;private static final String ALIAS="GalaxyChat.private.v1";
    Secrets(Context c){context=c.getApplicationContext();}
    private javax.crypto.SecretKey key()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
    }
    synchronized JSONObject read()throws Exception{
        String value=context.getSharedPreferences("encrypted",0).getString("data",null);if(value==null)return new JSONObject();
        String[] parts=value.split(":",-1);if(parts.length!=2)throw new Exception("Encrypted configuration invalid");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return Wire.object(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)));
    }
    synchronized void write(JSONObject value)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[] encrypted=c.doFinal(Wire.utf8(value.toString()));
        if(!context.getSharedPreferences("encrypted",0).edit().putString("data",Wire.token(c.getIV())+":"+Wire.token(encrypted)).commit())throw new Exception("Configuration save failed");
    }
    synchronized byte[] pairKey()throws Exception{JSONObject o=read();java.io.File staged=new java.io.File(context.getFilesDir(),"pair-key.txt");
        if(staged.exists()){
            if(staged.length()!=44&&staged.length()!=45)throw new Exception("Pair bootstrap invalid");
            byte[] bytes=new byte[(int)staged.length()];java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(staged));try{in.readFully(bytes);if(in.read()!=-1)throw new Exception("Pair bootstrap changed");}finally{in.close();}
            String value=new String(bytes,"US-ASCII");if(value.endsWith("\n"))value=value.substring(0,value.length()-1);byte[] key=Wire.token(value);
            if(o.has("pair")&&!o.getString("pair").equals(value))throw new Exception("A different app pairing already exists");
            o.put("pair",value);write(o);if(!staged.delete())throw new Exception("Pair bootstrap cleanup failed");return key;
        }
        if(!o.has("pair")){o.put("pair",Wire.token(Wire.random(32)));write(o);}return Wire.token(o.getString("pair"));}
    synchronized byte[] databaseKey()throws Exception{JSONObject o=read();if(!o.has("database")){o.put("database",Wire.token(Wire.random(32)));write(o);}return Wire.token(o.getString("database"));}
    synchronized void credentials(int id,String hash)throws Exception{JSONObject o=read();o.put("api_id",id);o.put("api_hash",hash);write(o);}
    synchronized void revokePairing()throws Exception{JSONObject o=read();o.put("pair",Wire.token(Wire.random(32)));write(o);}
}
