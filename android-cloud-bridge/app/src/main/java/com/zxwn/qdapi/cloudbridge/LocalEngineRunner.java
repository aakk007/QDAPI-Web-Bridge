package com.zxwn.qdapi.cloudbridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

// 凭据和官方请求只经本机匿名管道传递，不向云端发送，也不写临时文件。
final class LocalEngineRunner {
    interface Permit {void check() throws Exception;}
    interface EventSink {void accept(JSONObject event) throws Exception;}
    private final Context context;
    private final LocalBenefitsRunner.Transport transport;
    LocalEngineRunner(Context context){this(context,LocalBenefitsRunner::executeOfficial);}
    LocalEngineRunner(Context context,LocalBenefitsRunner.Transport transport){this.context=context;this.transport=transport;}
    JSONObject run(JSONObject fields,JSONArray tasks,Permit permit) throws Exception {
        return run(fields,tasks,permit,null);
    }
    JSONObject run(JSONObject fields,JSONArray tasks,Permit permit,EventSink events) throws Exception {
        ApplicationInfo module=context.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID,0);
        java.io.File executable=new java.io.File(module.nativeLibraryDir,"libqdapi_engine.so");
        if(!executable.isFile() || !executable.canExecute())throw new Exception("本机任务引擎不可用，请安装完整模块");
        Process process=new ProcessBuilder(executable.getAbsolutePath()).start();
        Thread watchdog=new Thread(()->{try{if(!process.waitFor(26,java.util.concurrent.TimeUnit.MINUTES))process.destroy();}catch(InterruptedException ignored){}},"QDAPI-local-timeout");
        watchdog.setDaemon(true);watchdog.start();
        Thread errors=new Thread(()->{try(java.io.InputStream input=process.getErrorStream()){byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){/* 不记录原始进程输出。 */}}catch(Exception ignored){}},"QDAPI-local-errors");
        errors.setDaemon(true);errors.start();
        long deadline=android.os.SystemClock.elapsedRealtime()+1560000;
        try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            OutputStreamWriter output=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8)){
            output.write(new JSONObject().put("fields",fields).put("tasks",tasks).toString()+"\n");output.flush();
            String line;
            while((line=input.readLine())!=null){
                if(line.length()>524288 || android.os.SystemClock.elapsedRealtime()>deadline)throw new Exception("本机任务执行超时或响应过大");
                JSONObject frame=new JSONObject(line);
                if("event".equals(frame.optString("type"))){if(events!=null)events.accept(frame.getJSONObject("event"));continue;}
                if("done".equals(frame.optString("type")))return frame;
                if("checkinAd".equals(frame.optString("type"))){
                    JSONObject reply=new JSONObject();
                    try{
                        permit.check();
                        CheckInSubmissionGuard.check(context,fields.optString("ywGuid"),String.valueOf(frame.getLong("checkInTime")));
                        reply.put("adID",CheckInRewardAd.watch(permit));
                    }
                    catch(Exception error){reply.put("error","宿主广告流程未确认完成");}
                    output.write(reply.toString()+"\n");output.flush();continue;
                }
                if(!"job".equals(frame.optString("type")))throw new Exception("本机任务通道无效");
                JSONObject job=frame.getJSONObject("job");LocalBenefitsRunner.validate(job);permit.check();
                JSONObject response;
                try {
                    CheckInSubmissionGuard.reserve(context,fields.optString("ywGuid"),job);
                    response=transport.execute(job);
                } catch(Exception error) {
                    // 广告报告失败也让引擎继续只读核验；不能把异常变成领取成功。
                    if(!new java.net.URL(job.getString("url")).getPath().equals("/argus/api/v2/checkin/adreport"))throw error;
                    response=new JSONObject().put("status",502).put("body","{\"Result\":-1,\"Message\":\"签到广告提交未确认，不重复提交\"}");
                }
                output.write(response.toString()+"\n");output.flush();
            }
            throw new Exception("本机任务引擎中断，请核对官方结果；不会重复提交");
        } finally {watchdog.interrupt();process.destroy();}
    }
}
