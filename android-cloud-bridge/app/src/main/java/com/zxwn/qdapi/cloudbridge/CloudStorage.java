package com.zxwn.qdapi.cloudbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CloudStorage {
    private final SharedPreferences prefs;
    private static final String ALIAS="qdapi.cloudbridge.v1";
    CloudStorage(Context context) {prefs=context.getSharedPreferences("qdapi_cloud_bridge",Context.MODE_PRIVATE);}
    String server(){return BuildConfig.CLOUD_SERVER;}
    boolean firstSyncDone(){return prefs.getBoolean("first_sync_done",false);}
    void firstSyncDone(boolean value){prefs.edit().putBoolean("first_sync_done",value).apply();}
    org.json.JSONArray tasks(){return new org.json.JSONArray();}
    static final String[] APP_TASKS={"hourly-chest","daily-rewards","video-rewards","game-rewards","checkin","exchange","lottery","read-points"};
    synchronized boolean taskEnabled(String id){return prefs.getBoolean("app_task_"+id,!"read-points".equals(id));}
    synchronized void taskEnabled(String id,boolean enabled){prefs.edit().putBoolean("app_task_"+id,enabled).apply();}
    synchronized org.json.JSONArray appTasks(){org.json.JSONArray selected=new org.json.JSONArray();for(String id:APP_TASKS)if(taskEnabled(id))selected.put(id);return selected;}
    void synced(String guid,org.json.JSONArray tasks) throws Exception {prefs.edit().putString("account_hash",accountHash(guid)).apply();}
    boolean matchesAccount(String guid) throws Exception {String saved=prefs.getString("account_hash","");return saved.isEmpty() || saved.equals(accountHash(guid));}
    boolean hasAccount(){return !prefs.getString("account_hash","").isEmpty();}
    String linkedAccountHash(){return prefs.getString("account_hash","");}
    boolean localLoginUsable(org.json.JSONObject fields) throws Exception {return !prefs.getString("expired_local_login","").equals(accountHash(fields.getString("ywGuid")+"\n"+fields.getString("ywKey")));}
    void localLoginExpired(org.json.JSONObject fields) throws Exception {prefs.edit().putString("expired_local_login",accountHash(fields.getString("ywGuid")+"\n"+fields.getString("ywKey"))).apply();}
    private String accountHash(String guid) throws Exception {return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(guid.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
    synchronized String token() throws Exception {
        String encoded=prefs.getString("token","");if(encoded.isEmpty())return "";
        byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);if(bytes.length<29)throw new Exception("本机绑定信息损坏，请重新绑定");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,bytes,0,12));
        return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
    }
    synchronized void token(String value) throws Exception {
        if(value.isEmpty()){prefs.edit().remove("token").apply();return;}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] iv=cipher.getIV();byte[] result=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(encrypted,0,result,iv.length,encrypted.length);prefs.edit().putString("token",Base64.encodeToString(result,Base64.NO_WRAP)).apply();
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
}
