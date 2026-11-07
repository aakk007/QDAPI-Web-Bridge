package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import de.robv.android.xposed.XposedHelpers;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CloudPanel {
    interface ConfigurationSource {JSONObject load() throws Exception;}
    private final Application context;
    private final CloudStorage storage;
    private final CloudClient client;
    private final ConfigurationSource configuration;
    private final LocalBenefitsRunner benefits;
    private final LocalEngineRunner localEngine;
    private final AppPresence presence;
    private volatile boolean localMode;
    private volatile long suspendedUntil;
    private final TaskRuntimeLog runtimeLog;
    private final TaskLogStore taskLogs;
    private volatile boolean logRetryScheduled;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<WeakReference<Map<String,Object>>> observers=new ArrayList<>();
    private WeakReference<Object> messageTarget=new WeakReference<>(null);
    private WeakReference<Activity> activity=new WeakReference<>(null);
    private volatile boolean busy,consentVisible;
    private volatile long lastAutomaticCollect;
    private String status="APP七项在本机自动执行。尚未授权云端；需手动点击“一键同步并领取福利”并确认才上传。";

    CloudPanel(Application context){this(context,CloudHook::configuration,null);}
    CloudPanel(Application context,ConfigurationSource configuration,LocalBenefitsRunner.Transport transport){
        this(context,configuration,transport,new TaskRuntimeLog(context));
    }
    CloudPanel(Application context,ConfigurationSource configuration,LocalBenefitsRunner.Transport transport,TaskRuntimeLog runtimeLog){
        this.context=context;storage=new CloudStorage(context);client=new CloudClient(storage);this.configuration=configuration;
        this.runtimeLog=runtimeLog;
        taskLogs=new TaskLogStore(context);
        try{taskLogs.recoverInterrupted();}catch(Exception e){runtimeLog.operation("恢复本机任务日志","failed","任务日志恢复失败，请检查存储空间并导出已有记录");}
        benefits=transport==null?new LocalBenefitsRunner(client):new LocalBenefitsRunner(client,transport);
        localEngine=transport==null?new LocalEngineRunner(context):new LocalEngineRunner(context,transport);
        presence=new AppPresence(storage,client);
    }
    void enableLocalExecution(boolean background){
        localMode=true;
        if(background)Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(()->{
            try{
                if(busy || consentVisible){presence.heartbeat();return;}
                retryLogs(false);
                if(android.os.SystemClock.elapsedRealtime()<suspendedUntil)return;
                JSONObject fields=configuration.load();
                if(!storage.localLoginUsable(fields)){presence.release();return;}
                if(presence.prepare(fields))main.post(this::automaticCollect);
            }catch(Exception ignored){/* 不重传凭据；无法续约时由云端在到期后接管。 */}
        },10,30,java.util.concurrent.TimeUnit.SECONDS);
    }
    void rememberActivity(Activity value){activity=new WeakReference<>(value);main.postDelayed(this::automaticCollect,10000);}
    void messageTarget(Object value){messageTarget=new WeakReference<>(value);}
    void observe(Map<String,Object> state){state.put("status",status);observers.add(new WeakReference<>(state));}
    boolean taskEnabled(String id){return storage.taskEnabled(id);}
    void setTask(String id,boolean enabled){
        if(!java.util.Arrays.asList(CloudStorage.APP_TASKS).contains(id))return;
        storage.taskEnabled(id,enabled);
        setStatus("APP任务选项已保存，下次执行生效，正在同步…");
        worker.execute(()->{
            String message;
            try{if(storage.token().isEmpty() || localMode && !presence.linked(configuration.load()))message="APP选项已保存，下次本机执行生效；未上传账号凭据";else{client.saveTasks(storage.appTasks());message="APP任务选项已同步；不改变Web选项及托管启停状态";}}
            catch(Exception e){message="APP选项已保存在本机，云端同步失败："+(e.getMessage()==null?"请稍后重试":e.getMessage())+"；下次同步时重试";}
            runtimeLog.operation("APP任务选项",message.contains("失败")?"failed":"success",message);
            final String result=message;main.post(()->{setStatus(result);notifyUser(result);});
        });
    }
    void entered(){if(storage.firstSyncDone())refresh();else setStatus("APP七项在本机自动执行。尚未授权云端；需手动点击“一键同步并领取福利”并确认才上传。");}
    interface ConsentPrompt {void show(Activity screen,Runnable accepted,Runnable dismissed);}
    private ConsentPrompt consentPrompt=UploadConsent::show;
    void consentPromptForTest(ConsentPrompt prompt){if(!BuildConfig.DEBUG || BuildConfig.QA_CERT_SHA256.isEmpty())throw new IllegalStateException("仅限隔离QA");consentPrompt=prompt;}
    void startSync(Activity screen,JSONArray selected){
        if(busy || consentVisible){notifyUser("正在处理，请稍候");return;}
        if(screen!=null)rememberActivity(screen);
        Activity target=screen!=null?screen:activity.get();
        if(consentPrompt==null || (target==null && BuildConfig.QA_CERT_SHA256.isEmpty())){notifyUser("请在设置页面点击同步并确认上传说明");return;}
        consentVisible=true;
        try{consentPrompt.show(target,()->{consentVisible=false;synchronize(selected,true);},()->consentVisible=false);}
        catch(Exception e){consentVisible=false;notifyUser("上传说明未能打开，请重新进入设置页面");}
    }
    private void synchronize(JSONArray selected,boolean runNow){
        if(busy){notifyUser("正在处理，请稍候");return;}
        setStatus("正在后台获取账号配置并自动验证，请稍候…");
        if(runNow)notifyUser(status);
        run("一键同步并领取福利",()->{
            JSONObject fields=configuration.load();
            client.sync(fields,selected,false);client.saveTasks(storage.appTasks());storage.firstSyncDone(true);
            if(runNow){
                main.post(()->setStatus("配置同步成功，正在检查签到及达标福利，请稍候…"));
                try{return "同步成功；"+collectLocal();}
                catch(Exception e){throw new Exception("配置已同步，但福利检查未完成："+(e.getMessage()==null?"请稍后重试":e.getMessage()));}
            }
            return "手动同步成功";
        },true,true);
    }
    void refresh(){refresh(false);}
    void refreshByUser(){refresh(true);}
    private void refresh(boolean feedback){
        if(feedback && !busy){setStatus("正在刷新云端状态，请稍候…");notifyUser(status);}
        run(feedback?"刷新云端状态":null,()->{
        if(storage.token().isEmpty())throw new Exception("尚未连接云端，请手动点击一键同步并确认上传");
        JSONObject state=client.request("GET","/api/device/status",null,true);
        String code=state.optString("status");
        String title="expired".equals(code)?"云端凭据已过期，重新同步即可恢复":"valid".equals(code)?"云端凭据有效":"not_synced".equals(code)?"尚未同步配置":"云端任务异常，请查看运行记录";
        JSONObject last=state.optJSONObject("lastRun");
        String recent=last==null || last.length()==0?"":"\n最近执行："+("app-benefits".equals(last.optString("source"))?"APP本机":"云端")+" · "+("success".equals(last.optString("status"))?"完成":"未全部完成")+"\n"+last.optString("message");
        return (feedback?"刷新成功\n":"")+title+"\n账号："+state.optString("nickname","未同步")+"\n托管："+(state.optBoolean("enabled")?"已开启":"已暂停")+(localMode?"\n执行归属："+(state.optBoolean("appOnline")?"APP本机，云端待机":"云端兜底待命"):"")+"\n最近同步："+state.optString("syncedAt","—")+"\n最近验证："+state.optString("checkedAt","—")+recent;
    },false,feedback,"云端状态刷新成功");}
    private String collectLocal() throws Exception {
        if(localMode)return collectStandalone(configuration.load());
        JSONObject result=benefits.run();
        runtimeLog.tasks(result.optJSONArray("logs"));
        if(result.optBoolean("busy"))return result.optString("message");
        if(!result.optBoolean("success"))throw new Exception(result.optString("message","APP领取未全部完成，请查看运行记录"));
        return result.optString("message","APP本机执行完成");
    }
    private String collectStandalone(JSONObject fields) throws Exception {
        if(!storage.localLoginUsable(fields)){presence.release();return "本机登录已失效，重新登录后自动恢复本机任务；不会自动更新云端凭据";}
        if(!presence.prepare(fields)){lastAutomaticCollect=0;return "云端/其他APP正在运行或托管已暂停，本机不重复执行";}
        String guid=fields.getString("ywGuid");
        String association=presence.linked(fields)?storage.linkedAccountHash():"";
        TaskLogStore.Run saved=taskLogs.begin(storage.appTasks(),association);
        JSONObject result;
        try{
            result=localEngine.run(fields,storage.appTasks(),()->{
                if(!guid.equals(configuration.load().getString("ywGuid")))throw new Exception("起点账号已切换，本轮停止，不会上传新账号");
                presence.permit();
            },event->{saved.event(event);runtimeLog.tasks(new JSONArray().put(event));});
            saved.finish(result);
        }catch(Exception e){try{saved.failed(TaskRuntimeLog.safe(e.getMessage()));}catch(Exception logFailure){runtimeLog.operation("本机日志持久化","failed","本轮明细保存失败，请检查存储空间；不会重复提交官方动作");}suspendedUntil=android.os.SystemClock.elapsedRealtime()+600000;presence.release();throw e;}
        if(result.optBoolean("expired"))storage.localLoginExpired(fields);
        try{
            if(presence.linked(fields))flushLogs();
        }catch(Exception e){runtimeLog.operation("回传本机日志","failed","本轮明细已落盘并加入待传队列，恢复网络后重传同一执行ID；未重传凭据，未重复执行任务");}
        if(!result.optBoolean("success")){suspendedUntil=android.os.SystemClock.elapsedRealtime()+600000;presence.release();throw new Exception(result.optString("message","本机执行未全部完成"));}
        return result.optString("message","APP本机执行完成");
    }
    private int flushLogs() throws Exception {
        if(storage.token().isEmpty())return 0;
        return taskLogs.flush(storage.linkedAccountHash(),payload->client.request("POST","/api/device/local-result",payload,true));
    }
    String logStatus(){try{return taskLogs.status();}catch(Exception e){return "本机任务日志读取失败，请检查存储空间";}}
    void exportFailure(){main.post(()->notifyUser("日志导出未完成，请检查日志目录或存储空间；不会把空文件报告为成功"));}
    void exportSavedLogs(java.io.File target,java.io.File original) throws Exception {taskLogs.writeExport(target,original);}
    void retryLogs(boolean feedback){
        if(logRetryScheduled)return;logRetryScheduled=true;
        worker.execute(()->{try{int sent=flushLogs();if(sent>0||feedback)runtimeLog.operation("补传任务日志","success","已确认回传"+sent+"轮；"+logStatus());if(feedback)main.post(()->notifyUser(logStatus()));}
            catch(Exception e){runtimeLog.operation("补传任务日志","failed","网络或授权未恢复，待传明细继续保留；不重做任务、不重传登录凭据");if(feedback)main.post(()->notifyUser("日志尚未回传，本机已保留，可先导出"));}
            finally{logRetryScheduled=false;}});
    }
    void automaticCollect(){
        long now=android.os.SystemClock.elapsedRealtime();
        if(busy || consentVisible || (!localMode && !storage.firstSyncDone()) || now<suspendedUntil || (lastAutomaticCollect!=0 && now-lastAutomaticCollect<600000))return;
        lastAutomaticCollect=now;
        run(localMode?"APP本机自动检查福利":"前台自动检查福利",()->{
            if(localMode)return collectStandalone(configuration.load());
            if(storage.token().isEmpty())return "尚未连接云端，点击一键同步即可连接";
            // 仅在本机核对账号；自动流程绝不调用凭据上传或重连入口。
            JSONObject fields=configuration.load();
            if(!storage.matchesAccount(fields.getString("ywGuid")))return "当前起点账号已切换，请手动同步并确认上传";
            JSONObject state=client.request("GET","/api/device/status",null,true);
            if("expired".equals(state.optString("status")))return "云端登录已失效，请手动同步并确认上传；微信绑定保留";
            if(!state.optBoolean("enabled"))return "托管已暂停，不自动恢复领取";
            if(!state.optBoolean("earnedBenefits"))return "云端福利功能未开启，保留原签到调度";
            client.saveTasks(storage.appTasks());
            return collectLocal();
        },!localMode,false);
    }
    void pause(){
        if(!busy){setStatus("正在暂停自动托管，请稍候…");notifyUser(status);}
        run("暂停自动托管",()->{
            JSONObject body=new JSONObject();body.put("enabled",false);
            JSONObject state=client.request("POST","/api/device/hosting",body,true);
            if(!state.has("enabled") || state.getBoolean("enabled"))throw new Exception("云端未确认暂停，请刷新状态后重试");
            if(localMode)presence.release();
            return "云端托管已暂停，后续自动任务不再启动";
        },true,true);
    }
    void unbind(){run("解除设备绑定",()->{client.request("POST","/api/device/revoke",new JSONObject(),true);storage.token("");storage.firstSyncDone(true);return "设备已解绑，云端任务未改变。再次同步须手动确认上传。";},false,true);}
    void reset(){run("清除本机连接",()->{storage.token("");storage.firstSyncDone(true);return "本机连接已清除，云端任务未改变。再次同步须手动确认上传。";},false,true);}
    private interface Operation {String execute() throws Exception;}
    private void run(String name,Operation operation,boolean refreshAfter,boolean feedback){
        run(name,operation,refreshAfter,feedback,null);
    }
    private void run(String name,Operation operation,boolean refreshAfter,boolean feedback,String successFeedback){
        if(busy){if(feedback)notifyUser("正在处理，请稍候");return;}busy=true;
        if(name!=null)runtimeLog.operation(name,"running","开始执行");
        worker.execute(()->{
            String message;boolean ok=false;
            try{message=operation.execute();ok=true;}
            catch(CloudClient.RequestException e){message=e.status==401 || e.status==404?"设备连接已失效，请手动点击一键同步并确认上传；不会自动重传凭据":e.getMessage();}
            catch(Exception e){message=e.getMessage()==null?"操作未完成，请稍后重试":e.getMessage();}
            if(!ok && localMode && "APP本机自动检查福利".equals(name))lastAutomaticCollect=0;
            if(name!=null)runtimeLog.operation(name,ok?"success":"failed",ok && successFeedback!=null?successFeedback:message);
            final String result=message;final boolean success=ok;
            main.post(()->{busy=false;setStatus(success?result:"操作失败："+result);if(feedback)notifyUser(success && successFeedback!=null?successFeedback:status);if(success && refreshAfter)refresh();});
        });
    }
    private void notifyUser(String message){
        Object target=messageTarget.get();
        if(target!=null)try{XposedHelpers.callMethod(target,"assert",message);return;}catch(Throwable ignored){}
        Activity screen=activity.get();
        Toast.makeText(screen!=null?screen:context,message,Toast.LENGTH_LONG).show();
    }
    private void setStatus(String message){
        status=message;
        for(int i=observers.size()-1;i>=0;i--){Map<String,Object> state=observers.get(i).get();if(state==null)observers.remove(i);else state.put("status",message);}
    }
}
