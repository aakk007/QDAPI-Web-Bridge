package com.zxwn.qdapi.cloudbridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

// 领取规则及验收共用云端实现，官方HTTP请求在APP本机执行，不打开任务窗口。
final class LocalBenefitsRunner {
    private static final java.util.concurrent.ScheduledExecutorService requestTimeout=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"QDAPI-request-timeout");t.setDaemon(true);return t;});
    interface Transport {JSONObject execute(JSONObject job) throws Exception;}
    private final CloudClient client;
    private final Transport transport;
    LocalBenefitsRunner(CloudClient client){this(client,LocalBenefitsRunner::executeOfficial);}
    LocalBenefitsRunner(CloudClient client,Transport transport){this.client=client;this.transport=transport;}

    JSONObject run() throws Exception {
        JSONObject started=client.request("POST","/api/device/local-benefits/start",new JSONObject().put("executionVersion",2),true);
        if(started.optBoolean("busy"))return started;
        String session=started.getString("session");
        JSONObject poll=new JSONObject().put("session",session);
        int seconds=started.optInt("expiresIn",300);
        if(seconds<1 || seconds>1500)throw new Exception("APP任务会话时限无效");
        long deadline=android.os.SystemClock.elapsedRealtime()+(seconds+30L)*1000;
        try {
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                JSONObject next=client.request("POST","/api/device/local-benefits/next",poll,true);
                if(next.optBoolean("done"))return next;
                JSONObject job=next.optJSONObject("job");
                if(job==null){Thread.sleep(150);continue;}
                validate(job);
                // 官方请求只执行一次；回执网络失败时只重发同一回执，不重做领取请求。
                JSONObject reply=transport.execute(job);
                JSONObject answer=new JSONObject().put("session",session).put("id",job.getString("id")).put("status",reply.getInt("status")).put("body",reply.getString("body"));
                try{client.request("POST","/api/device/local-benefits/result",answer,true);}
                catch(Exception first){client.request("POST","/api/device/local-benefits/result",answer,true);}
            }
            throw new Exception("APP本机执行超时，请查看运行记录；不会自动重复提交领取");
        } catch(Exception error){
            try{client.request("POST","/api/device/local-benefits/abort",poll,true);}catch(Exception ignored){}
            throw error;
        }
    }

    static void validate(JSONObject job) throws Exception {
        URL url=new URL(job.getString("url"));
        if(!"https".equals(url.getProtocol()) || url.getPort()!=-1 || url.getUserInfo()!=null || url.getRef()!=null || url.toString().length()>4096)throw new Exception("本机福利请求地址无效");
        String path=url.getPath(),method=job.getString("method");
        boolean game="lygame.qidian.com".equals(url.getHost()) && "/home/log/heartbeat".equals(path) && "gameId=201743&platformId=1".equals(url.getQuery()) && "GET".equals(method);
        if(!game && !"h5.if.qidian.com".equals(url.getHost()))throw new Exception("本机福利请求地址无效");
        Set<String> get=new HashSet<>(Arrays.asList(
            "/argus/api/v2/video/adv/mainPage",
            "/argus/api/v3/checkin/getcurrentweekcheckininfo","/argus/api/v3/checkin/checkinexchangepage","/argus/api/v3/checkin/checkinexchangelog",
            "/argus/api/v1/common/risk/check","/argus/api/v2/readtime/readpage","/argus/api/v1/readtime/score/pickup/risk",
            "/argus/api/v1/readtime/daymission/pickup/risk","/argus/api/v1/readtime/pk/reward/pickup/risk"));
        Set<String> post=new HashSet<>(Arrays.asList(
            "/argus/api/v1/video/adv/receiveTaskReward","/argus/api/v1/video/adv/finishWatch","/argus/api/v2/video/callback",
            "/argus/api/v3/checkin/checkin","/argus/api/v3/checkin/exchangegoods","/argus/api/v2/checkin/lottery","/argus/api/v1/readtime/score/batchPickup/risk"));
        if(!(game || "GET".equals(method)&&get.contains(path) || "POST".equals(method)&&post.contains(path)))throw new Exception("本机福利请求不在允许范围内");
        if(job.optString("body").getBytes(StandardCharsets.UTF_8).length>64*1024)throw new Exception("本机福利参数过大");
        if("GET".equals(method)&&!job.optString("body").isEmpty())throw new Exception("本机查询参数无效");
    }

    static JSONObject executeOfficial(JSONObject job) throws Exception {
        validate(job);
        HttpURLConnection connection=(HttpURLConnection)new URL(job.getString("url")).openConnection();
        java.util.concurrent.ScheduledFuture<?> deadline=requestTimeout.schedule(connection::disconnect,25,java.util.concurrent.TimeUnit.SECONDS);
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setRequestMethod(job.getString("method"));
        Set<String> allowedHeaders=new HashSet<>(Arrays.asList("cookie","sdksign","ibex","user-agent","content-type","accept"));
        try{
            JSONObject headers=job.getJSONObject("headers");
            Iterator<String> keys=headers.keys();
            while(keys.hasNext()){String key=keys.next();if(!allowedHeaders.contains(key.toLowerCase(java.util.Locale.ROOT)))throw new Exception("本机请求头无效");connection.setRequestProperty(key,headers.getString(key));}
            if("POST".equals(job.getString("method"))){
                byte[] body=job.optString("body").getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);
                try(java.io.OutputStream out=connection.getOutputStream()){out.write(body);}
            }
            int status=connection.getResponseCode();
            InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            if(input!=null)try(InputStream stream=input){byte[] bytes=new byte[4096];int count;while((count=stream.read(bytes))!=-1){if(buffer.size()+count>128*1024)throw new Exception("官方福利响应过大");buffer.write(bytes,0,count);}}
            return new JSONObject().put("status",status).put("body",buffer.toString("UTF-8"));
        } catch(java.io.IOException error){throw new Exception("APP连接官方福利接口失败或结果未知，请查看运行记录后重试");}
        finally{deadline.cancel(false);connection.disconnect();}
    }
}
