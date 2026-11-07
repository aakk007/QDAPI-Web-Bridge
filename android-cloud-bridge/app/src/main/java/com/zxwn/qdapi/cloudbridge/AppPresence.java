package com.zxwn.qdapi.cloudbridge;

import android.os.SystemClock;
import org.json.JSONObject;

// 只上传设备在线状态，不上传或刷新起点凭据；租约失效后本机请求必须重新取得许可。
final class AppPresence {
    private final CloudStorage storage;
    private final CloudClient client;
    private boolean coordinated;
    private long validUntil;
    AppPresence(CloudStorage storage,CloudClient client){this.storage=storage;this.client=client;}
    synchronized boolean prepare(JSONObject fields) throws Exception {
        String guid=fields.getString("ywGuid");
        if(!storage.hasAccount() || !storage.matchesAccount(guid)){
            release();coordinated=false;return true;
        }
        if(storage.token().isEmpty())throw new Exception("此账号曾启用云端但连接已清除，请手动重新连接或先在管理台暂停云端，避免两端重复执行");
        JSONObject state=client.request("GET","/api/device/status",null,true);
        if("expired".equals(state.optString("status")) || "not_synced".equals(state.optString("status"))){release();coordinated=false;return true;}
        if(!state.optBoolean("enabled")){release();return false;}
        coordinated=true;return renew();
    }
    private boolean renew() throws Exception {
        long sentAt=SystemClock.elapsedRealtime();
        JSONObject lease=client.request("POST","/api/device/app-lease",new JSONObject().put("active",true),true);
        if(!lease.optBoolean("granted")){validUntil=0;return false;}
        int seconds=lease.optInt("expiresIn");
        if(seconds<60 || seconds>120)throw new Exception("云端接管时限无效");
        validUntil=sentAt+seconds*1000L;return true;
    }
    synchronized void heartbeat() throws Exception {if(coordinated && !renew())throw new Exception("云端或其他APP正在执行，本机暂不接管");}
    synchronized void permit() throws Exception {
        if(coordinated && validUntil-SystemClock.elapsedRealtime()<=45000){
            if(!renew() || validUntil-SystemClock.elapsedRealtime()<=30000)throw new Exception("执行许可已失效或网络延迟过长，本机停止请求，交由云端重新核验");
        }
    }
    synchronized void release(){
        if(coordinated)try{client.request("POST","/api/device/app-lease",new JSONObject().put("active",false),true);}catch(Exception ignored){}
        coordinated=false;validUntil=0;
    }
    synchronized boolean linked(JSONObject fields) throws Exception {return storage.hasAccount() && storage.matchesAccount(fields.getString("ywGuid")) && !storage.token().isEmpty();}
}
