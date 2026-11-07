package com.zxwn.qdapi.cloudbridge;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class CloudClient {
    static final class RequestException extends Exception {
        final int status;
        final boolean reconnect;
        RequestException(int status,String message){this(status,message,false);}
        RequestException(int status,String message,boolean reconnect){super(message);this.status=status;this.reconnect=reconnect;}
    }
    final CloudStorage storage;
    CloudClient(CloudStorage storage){this.storage=storage;}
    void saveTasks(org.json.JSONArray selected) throws Exception {
        try{request("POST","/api/device/tasks",new JSONObject().put("appTasks",selected),true);}
        catch(RequestException e){if(e.status==404)throw new Exception("云端任务开关接口尚未更新，请更新服务端后重试");throw e;}
    }
    static String validateServer(String input) throws Exception {
        if(input==null || input.trim().isEmpty())throw new Exception("本构建未配置云端服务；本机任务可独立运行，云端需使用部署者配置的构建");
        URL url=new URL(input.trim());
        if(!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null || !(url.getPath().isEmpty() || "/".equals(url.getPath())))throw new Exception("云端连接配置无效，请更新模块后重试");
        return input.trim().replaceAll("/+$","");
    }
    void sync(JSONObject fields,org.json.JSONArray tasks) throws Exception {
        sync(fields,tasks,true);
    }
    void sync(JSONObject fields,org.json.JSONArray tasks,boolean runNow) throws Exception {
        sync(fields,tasks,true,runNow);
    }
    void reconnect(JSONObject fields,org.json.JSONArray tasks) throws Exception {
        if(!storage.matchesAccount(fields.getString("ywGuid")))throw new Exception("当前起点账号已切换，请先清除本机连接后重新同步");
        sync(fields,tasks,false,false);
    }
    private void sync(JSONObject fields,org.json.JSONArray tasks,boolean resume,boolean runNow) throws Exception {
        JSONObject body=new JSONObject();body.put("fields",fields);body.put("taskTypes",tasks);body.put("resume",resume);body.put("runNow",runNow);
        String token;
        try{token=storage.token();}catch(Exception e){storage.token("");token="";}
        if(!token.isEmpty()){
            try{request("POST","/api/device/sync",body,true);storage.synced(fields.getString("ywGuid"),tasks);return;}
            catch(RequestException e){if(e.status!=401 && e.status!=404 && !(e.status==409 && e.reconnect))throw e;storage.token("");}
        }
        JSONObject result=request("POST","/api/device/connect",body,false);
        storage.token(result.getString("token"));
        storage.synced(fields.getString("ywGuid"),tasks);
    }
    JSONObject request(String method,String path,JSONObject payload,boolean authenticated) throws Exception {
        URL url=new URL(validateServer(storage.server())+path);HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        // 设备回归专用：仅允许显式编译的回环证书指纹；普通构建没有此信任入口。
        if(BuildConfig.DEBUG && !BuildConfig.QA_CERT_SHA256.isEmpty() && "127.0.0.1".equals(url.getHost())){
            TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);final X509TrustManager standard=(X509TrustManager)factory.getTrustManagers()[0];
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return standard.getAcceptedIssuers();}
                public void checkClientTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException{standard.checkClientTrusted(chain,auth);}
                public void checkServerTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException{
                    try{standard.checkServerTrusted(chain,auth);return;}catch(java.security.cert.CertificateException original){
                        try{if(chain.length!=1)throw original;chain[0].checkValidity();byte[] hash=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(!hex.toString().equalsIgnoreCase(BuildConfig.QA_CERT_SHA256))throw original;}catch(java.security.cert.CertificateException e){throw e;}catch(Exception e){throw new java.security.cert.CertificateException(e);}
                    }
                }
            }},null);((HttpsURLConnection)connection).setSSLSocketFactory(tls.getSocketFactory());
        }
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(45000);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
        try {
            if(authenticated){String token=storage.token();if(token.isEmpty())throw new RequestException(401,"尚未连接云端，请手动点击一键同步并确认上传");connection.setRequestProperty("Authorization","Bearer "+token);}
            if(payload!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            int code=connection.getResponseCode();if(code==404 && "/api/device/connect".equals(path))throw new RequestException(503,"云端自动连接服务尚未更新，请更新服务端后重试");InputStream input=code>=400?connection.getErrorStream():connection.getInputStream();if(input==null)throw new RequestException(code,"云端暂时不可用，请稍后重试");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream stream=input){byte[] bytes=new byte[4096];int n;while((n=stream.read(bytes))!=-1){if(buffer.size()+n>128*1024)throw new Exception("云端响应过大");buffer.write(bytes,0,n);}}
            JSONObject result;try{result=new JSONObject(buffer.toString("UTF-8"));}catch(Exception e){throw new RequestException(code,"云端接口尚未就绪，请稍后重试");}
            if(code<200 || code>=300)throw new RequestException(code,result.optString("error","云端请求失败，请稍后重试"),result.optBoolean("reconnect"));return result;
        } catch(java.io.IOException e){throw new Exception("无法连接云端，请检查网络后重试");
        } finally {connection.disconnect();}
    }
}
