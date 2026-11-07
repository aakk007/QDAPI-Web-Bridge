package com.zxwn.qdapi.cloudbridge;

import android.app.Instrumentation;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

// 仅对编译时显式指定的回环 QA 服务运行，不读取起点账号或触发真实奖励。
public final class CloudClientDeviceTest extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);this.arguments=arguments;start();}
    @Override public void onStart(){
        if(arguments!=null && arguments.containsKey("projectCreditsLinkTest")){ProjectCreditsDeviceTest.runLinksOnly(this);return;}
        if(arguments!=null && arguments.containsKey("projectCreditsTest")){ProjectCreditsDeviceTest.run(this);return;}
        if(arguments!=null && arguments.containsKey("coldStartConfigurationTest")){ColdStartConfigurationDeviceTest.run(this);return;}
        if(arguments!=null && arguments.containsKey("durableLogTest")){DurableLogDeviceTest.run(this,arguments);return;}
        if(arguments!=null && arguments.containsKey("localEngineTest")){LocalEngineDeviceTest.run(this,arguments);return;}
        if(arguments!=null && arguments.containsKey("nativeLabel")){NativeTaskSwitchDeviceTest.run(this,arguments);return;}
        Bundle result=new Bundle();
        try{
            CloudStorage storage=new CloudStorage(getTargetContext());
            if(!storage.server().startsWith("https://127.0.0.1:"))throw new Exception("设备测试仅允许回环服务");
            storage.token("");
            CloudClient client=new CloudClient(storage);
            JSONObject fields=new JSONObject(new String(Base64.decode(arguments.getString("fields"),Base64.DEFAULT),StandardCharsets.UTF_8));
            JSONArray tasks=new JSONArray();
            verifyManualOnlyUpload(storage,client,fields,tasks);
            client.sync(fields,tasks);
            String firstToken=storage.token();
            if(firstToken.isEmpty())throw new Exception("首次自动连接未保存令牌");
            JSONObject first=client.request("GET","/api/device/status",null,true);
            if(!"valid".equals(first.optString("status")))throw new Exception("首次连接状态无效");
            verifyNoAutomaticReconnect(storage,client,fields);
            verifyRuntimeLogFormatting();
            verifyRefreshFeedback(storage,client);
            verifyTaskSelections(storage,client);
            verifyActionFeedback(storage,client,fields,tasks);
            verifyAutomaticSeven(storage,client,fields);
            JSONObject pause=new JSONObject();pause.put("enabled",false);
            client.request("POST","/api/device/hosting",pause,true);
            client.reconnect(fields,tasks);
            if(client.request("GET","/api/device/status",null,true).optBoolean("enabled"))throw new Exception("自动重连意外恢复了暂停任务");
            client.sync(fields,tasks);
            if(!client.request("GET","/api/device/status",null,true).optBoolean("enabled"))throw new Exception("重复同步未恢复托管");
            client.request("POST","/api/device/revoke",new JSONObject(),true);
            client.sync(fields,tasks);
            if(firstToken.equals(storage.token()))throw new Exception("失效令牌没有自动更换");
            JSONObject restored=client.request("GET","/api/device/status",null,true);
            if(!first.getString("accountId").equals(restored.getString("accountId")))throw new Exception("重连重复创建账号");
            final boolean[] picked={false};
            JSONObject collected=new LocalBenefitsRunner(client,job->{
                if(job.getJSONObject("headers").optString("ibex").isEmpty())throw new Exception("本机请求缺少ibex");
                String path=new java.net.URL(job.getString("url")).getPath();
                String data;
                if(path.endsWith("/video/adv/mainPage"))data="{\"NickName\":\"设备回归账号\"}";
                else if(path.endsWith("/checkin/checkin"))data="{}";
                else if(path.endsWith("/getcurrentweekcheckininfo"))data="{\"IsSunday\":0,\"LotteryInfo\":{\"LotteryCount\":0,\"HasVideoUrge\":1}}";
                else if(path.endsWith("/checkinexchangepage"))data="{\"Balance\":0,\"Goods\":[]}";
                else if(path.endsWith("/readtime/readpage"))data="{\"ReadInfo\":{\"ReadScore\":"+(picked[0]?130:100)+"},\"TodayRead\":{\"TotalReadTime\":300,\"RewardList\":[{\"PackageId\":1,\"Time\":300,\"Rewards\":[{\"Id\":1,\"Amount\":30,\"HasPickUp\":"+(picked[0]?1:0)+"}]}]}}";
                else if(path.endsWith("/score/batchPickup/risk")){if(picked[0])throw new Exception("重复领取合成积分");picked[0]=true;data="{}";}
                else throw new Exception("本机回归调用了范围外接口");
                return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
            }).run();
            if(!collected.optBoolean("done") || !collected.optBoolean("success") || !picked[0])throw new Exception("本机领取及回读核验失败");
            java.util.List<String> events=new java.util.ArrayList<>();
            new TaskRuntimeLog((priority,tag,message)->events.add(message)).tasks(collected.optJSONArray("logs"));
            if(events.stream().noneMatch(line->line.contains("[成功][每日阅读5分钟积分]")) || events.stream().noneMatch(line->line.contains("[跳过][每小时宝箱]")))throw new Exception("本机逐任务日志未包含成功和跳过明细："+events);
            JSONObject last=client.request("GET","/api/device/status",null,true).getJSONObject("lastRun");
            if(!"app-benefits".equals(last.optString("source")))throw new Exception("APP本机运行记录未保存");
            try{LocalBenefitsRunner.validate(new JSONObject().put("url","https://h5.if.qidian.com/argus/api/v1/readtime/startnextpk").put("method","POST"));throw new Exception("本机放行非授权接口");}
            catch(Exception expected){if(!expected.getMessage().contains("允许范围"))throw expected;}
            fields.put("ywGuid","different-account");
            String savedToken=storage.token();
            try{client.reconnect(fields,tasks);throw new Exception("自动重连允许切换账号");}
            catch(Exception expected){if(!expected.getMessage().contains("账号已切换"))throw expected;}
            if(!savedToken.equals(storage.token()))throw new Exception("自动重连错误清除了原账号令牌");
            try{client.sync(fields,tasks);throw new Exception("错误账号被允许覆盖");}
            catch(CloudClient.RequestException expected){if(expected.status!=409)throw expected;}
            client.request("POST","/qa/done",new JSONObject(),false);
            storage.token("");
            result.putString("stream","手动确认/取消零上传、失效不自动重传、七项前台自动执行、逐项日志及原按钮回归通过\n");
            finish(ActivityResult.OK,result);
        }catch(Exception e){result.putString("stream","设备自动连接回归失败："+e.getClass().getSimpleName()+" "+e.getMessage()+"\n");finish(ActivityResult.FAILED,result);}
    }
    private void verifyRuntimeLogFormatting() throws Exception {
        java.util.List<String> lines=new java.util.ArrayList<>();
        TaskRuntimeLog log=new TaskRuntimeLog((priority,tag,message)->{
            if(!TaskRuntimeLog.TAG.equals(tag) || (priority!=android.util.Log.INFO && priority!=android.util.Log.ERROR))throw new AssertionError("日志未进入原进程INFO/ERROR通道");
            lines.add(message);
        });
        log.tasks(new JSONArray().put(new JSONObject().put("name","免费抽奖").put("status","success").put("message","剩余次数已核对"))
            .put(new JSONObject().put("name","签到碎片兑换").put("status","skipped").put("message","非周日"))
            .put(new JSONObject().put("name","积分领取").put("status","failed").put("message","连接失败 https://invalid.test/path?token=secret ywKey=secret\n原因保留")));
        if(lines.size()!=3 || !lines.get(0).contains("[成功]") || !lines.get(1).contains("[跳过]") || !lines.get(2).contains("[失败]") || !lines.get(2).contains("原因保留") || lines.get(2).contains("secret") || lines.get(2).contains("https://"))throw new Exception("运行日志结果、原因或脱敏不符");
    }
    private void verifyActionFeedback(CloudStorage storage,CloudClient client,JSONObject fields,JSONArray tasks) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean missingFields=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.List<String> operationLogs=new java.util.concurrent.CopyOnWriteArrayList<>();
        CloudPanel panel=new CloudPanel((Application)getTargetContext().getApplicationContext(),()->{
            if(missingFields.get())throw new Exception("回归模拟：尚未取得完整账号配置");
            return new JSONObject(fields.toString());
        },job->{
            String path=new java.net.URL(job.getString("url")).getPath();
            String data;
            if(path.endsWith("/video/adv/mainPage"))data="{\"NickName\":\"设备回归账号\"}";
            else if(path.endsWith("/checkin/checkin"))data="{}";
            else if(path.endsWith("/getcurrentweekcheckininfo"))data="{\"IsSunday\":0,\"LotteryInfo\":{\"LotteryCount\":0}}";
            else if(path.endsWith("/readtime/readpage"))data="{\"ReadInfo\":{\"ReadScore\":100}}";
            else throw new Exception("按钮回归调用了范围外接口");
            return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
        },new TaskRuntimeLog((priority,tag,message)->operationLogs.add(message)));
        Map<String,Object> state=new HashMap<>();
        panel.consentPromptForTest((screen,accepted,dismissed)->accepted.run());
        runOnMainSync(()->{
            panel.observe(state);panel.pause();
            if(!String.valueOf(state.get("status")).startsWith("正在暂停"))throw new AssertionError("暂停没有即时加载状态");
            panel.pause();
        });
        awaitPanel(state,"托管：已暂停",true);
        if(client.request("GET","/api/device/status",null,true).optBoolean("enabled"))throw new Exception("暂停按钮未停止托管");
        runOnMainSync(()->{
            panel.startSync(null,tasks);
            if(!String.valueOf(state.get("status")).startsWith("正在后台"))throw new AssertionError("同步没有即时加载状态");
            panel.startSync(null,tasks);panel.pause();
        });
        awaitPanel(state,"托管：已开启",true);
        if(!"success".equals(client.request("GET","/api/device/status",null,true).getJSONObject("lastRun").optString("status")))throw new Exception("同步按钮未完成本机福利检查");
        String token=storage.token();
        try{
            actionFailure(client,"/api/device/hosting",false);
            runOnMainSync(panel::pause);awaitPanel(state,"操作失败：回归模拟：操作服务暂时不可用");
            if(!client.request("GET","/api/device/status",null,true).optBoolean("enabled"))throw new Exception("暂停失败错误改变了托管状态");
            actionFailure(client,"",true);
            runOnMainSync(panel::pause);awaitPanel(state,"操作失败：云端未确认暂停");
            actionFailure(client,"/api/device/sync",false);
            runOnMainSync(()->panel.startSync(null,tasks));awaitPanel(state,"操作失败：回归模拟：操作服务暂时不可用");
            if(!token.equals(storage.token()))throw new Exception("同步失败清除了有效连接");
            actionFailure(client,"/api/device/local-benefits/start",false);
            runOnMainSync(()->panel.startSync(null,tasks));awaitPanel(state,"操作失败：配置已同步，但福利检查未完成：回归模拟：操作服务暂时不可用");
            actionFailure(client,"",false);
            missingFields.set(true);
            runOnMainSync(()->panel.startSync(null,tasks));awaitPanel(state,"操作失败：回归模拟：尚未取得完整账号配置");
            storage.token("");
            runOnMainSync(panel::pause);awaitPanel(state,"操作失败：设备连接已失效");
        }finally{storage.token(token);actionFailure(client,"",false);}
        if(operationLogs.stream().noneMatch(line->line.contains("[失败][一键同步并领取福利]") && line.contains("尚未取得完整账号配置")) || operationLogs.stream().noneMatch(line->line.contains("[成功][暂停自动托管]")))throw new Exception("按钮成功或执行前失败未进入运行日志");
    }
    private void actionFailure(CloudClient client,String path,boolean unconfirmed) throws Exception {
        client.request("POST","/qa/action-failure",new JSONObject().put("path",path).put("unconfirmed",unconfirmed),false);
    }
    private void verifyTaskSelections(CloudStorage storage,CloudClient client) throws Exception {
        for(String id:CloudStorage.APP_TASKS)storage.taskEnabled(id,true);
        client.saveTasks(storage.appTasks());
        JSONObject status=client.request("GET","/api/device/status",null,true);
        if(status.getJSONArray("appTasks").length()!=8 || status.getJSONArray("cloudTasks").length()!=7)throw new Exception("APP七项加积分/Web七项选项不符");
        CloudPanel panel=new CloudPanel((Application)getTargetContext().getApplicationContext());
        Map<String,Object> state=new HashMap<>();
        runOnMainSync(()->{panel.observe(state);panel.setTask("hourly-chest",false);});
        awaitPanel(state,"APP任务选项已同步");
        status=client.request("GET","/api/device/status",null,true);
        if(status.getJSONArray("appTasks").length()!=7 || status.getJSONArray("cloudTasks").length()!=7 || storage.taskEnabled("hourly-chest"))throw new Exception("APP选项未保存或覆盖Web");
        actionFailure(client,"/api/device/tasks",false);
        try{
            runOnMainSync(()->panel.setTask("daily-rewards",false));
            awaitPanel(state,"APP选项已保存在本机，云端同步失败：");
            if(storage.taskEnabled("daily-rewards"))throw new Exception("选项同步失败丢失本地选择");
        }finally{actionFailure(client,"",false);}
        for(String id:CloudStorage.APP_TASKS)storage.taskEnabled(id,true);
        client.saveTasks(storage.appTasks());
    }
    private void verifyRefreshFeedback(CloudStorage storage,CloudClient client) throws Exception {
        CloudPanel panel=new CloudPanel((Application)getTargetContext().getApplicationContext());
        Map<String,Object> state=new HashMap<>();
        runOnMainSync(()->{
            panel.observe(state);panel.refreshByUser();
            if(!String.valueOf(state.get("status")).startsWith("正在刷新"))throw new AssertionError("刷新没有即时加载状态");
            panel.refreshByUser();
            if(!String.valueOf(state.get("status")).startsWith("正在刷新"))throw new AssertionError("重复点击覆盖了加载状态");
        });
        awaitPanel(state,"刷新成功");
        runOnMainSync(panel::refresh);
        awaitPanel(state,"云端凭据有效");
        client.request("POST","/qa/status-failure",new JSONObject().put("enabled",true),false);
        try{runOnMainSync(panel::refreshByUser);awaitPanel(state,"操作失败：回归模拟：状态服务暂时不可用");}
        finally{client.request("POST","/qa/status-failure",new JSONObject().put("enabled",false),false);}
        String token=storage.token();storage.token("");
        try{runOnMainSync(panel::refreshByUser);awaitPanel(state,"操作失败：尚未连接云端");}
        finally{storage.token(token);}
    }
    private void awaitPanel(Map<String,Object> state,String expected) throws Exception {
        awaitPanel(state,expected,false);
    }
    private void verifyManualOnlyUpload(CloudStorage storage,CloudClient client,JSONObject fields,JSONArray tasks) throws Exception {
        storage.firstSyncDone(false);
        java.util.concurrent.atomic.AtomicInteger loads=new java.util.concurrent.atomic.AtomicInteger();
        CloudPanel panel=new CloudPanel((Application)getTargetContext().getApplicationContext(),()->{loads.incrementAndGet();return fields;},null);
        final Runnable[] cancel={null};final int[] prompts={0};
        panel.consentPromptForTest((screen,accepted,dismissed)->{prompts[0]++;cancel[0]=dismissed;});
        int before=client.request("GET","/qa/uploads",null,false).getInt("count");
        runOnMainSync(()->{panel.entered();panel.automaticCollect();panel.startSync(null,tasks);panel.startSync(null,tasks);});
        SystemClock.sleep(300);
        if(loads.get()!=0 || prompts[0]!=1 || cancel[0]==null)throw new Exception("确认前已获取配置或弹窗重复");
        runOnMainSync(cancel[0]);
        if(client.request("GET","/qa/uploads",null,false).getInt("count")!=before || loads.get()!=0)throw new Exception("冷启动/取消弹窗发生了凭据上传");
    }
    private void verifyAutomaticSeven(CloudStorage storage,CloudClient client,JSONObject fields) throws Exception {
        for(String id:CloudStorage.APP_TASKS)storage.taskEnabled(id,!"read-points".equals(id));
        java.util.Map<String,Integer> finished=new java.util.HashMap<>(),received=new java.util.HashMap<>();
        java.util.Set<String> actions=new java.util.HashSet<>();
        java.util.List<String> logs=new java.util.concurrent.CopyOnWriteArrayList<>();
        CloudPanel panel=new CloudPanel((Application)getTargetContext().getApplicationContext(),()->fields,job->{
            java.net.URL url=new java.net.URL(job.getString("url"));String path=url.getPath();String data="{}";
            if(path.endsWith("/video/adv/mainPage")){
                JSONObject page=new JSONObject().put("NickName","设备回归账号");
                for(String id:new String[]{"chest","daily","video","game"}){
                    int done=finished.getOrDefault(id,"game".equals(id)?1:0);
                    JSONObject task=new JSONObject().put("TaskId",id).put("TaskType","game".equals(id)?104:"video".equals(id)?3:0).put("IsFinished",done).put("IsReceived",received.getOrDefault(id,0)).put("Process",done).put("Total",1).put("IntervalTime","0");
                    if("chest".equals(id))page.put("SurpriseBenefit",task);
                    else page.put("daily".equals(id)?"DailyBenefitModule":"video".equals(id)?"VideoRewardTab":"MoreRewardTab",new JSONObject().put("TaskList",new JSONArray().put(task)));
                }
                data=page.toString();
            } else if(path.endsWith("finishWatch") || path.endsWith("receiveTaskReward")){
                String id=java.net.URLDecoder.decode(job.getString("body").split("taskId=")[1].split("&")[0],"UTF-8");
                String key=path+id;if(!actions.add(key))throw new Exception("自动任务重复提交");
                if(path.endsWith("finishWatch"))finished.put(id,1);else received.put(id,1);
            } else if(path.endsWith("getcurrentweekcheckininfo"))data="{\"IsSunday\":0,\"LotteryInfo\":{\"LotteryCount\":0}}";
            else if(!path.endsWith("/checkin/checkin"))throw new Exception("自动任务未知接口");
            return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
        },new TaskRuntimeLog((priority,tag,message)->logs.add(message)));
        int uploads=client.request("GET","/qa/uploads",null,false).getInt("count");
        runOnMainSync(panel::automaticCollect);
        long until=SystemClock.elapsedRealtime()+25000;
        while(SystemClock.elapsedRealtime()<until && logs.stream().noneMatch(line->line.contains("[成功][前台自动检查福利]")))SystemClock.sleep(50);
        for(String name:new String[]{"每小时宝箱","每日任务","视频任务","游戏任务","每日签到","签到碎片兑换","免费抽奖"})if(logs.stream().noneMatch(line->line.contains("["+name+"]") && (line.contains("[成功]") || line.contains("[跳过]"))))throw new Exception("自动七项日志缺失："+name+logs);
        if(actions.size()!=5 || client.request("GET","/qa/uploads",null,false).getInt("count")!=uploads)throw new Exception("自动任务未完整执行或上传了凭据："+actions);
        for(String id:CloudStorage.APP_TASKS)storage.taskEnabled(id,true);
        client.saveTasks(storage.appTasks());
        LocalBenefitsRunner.validate(new JSONObject().put("url","https://lygame.qidian.com/home/log/heartbeat?gameId=201743&platformId=1").put("method","GET"));
        LocalBenefitsRunner.validate(new JSONObject().put("url","https://h5.if.qidian.com/argus/api/v1/video/adv/finishWatch").put("method","POST"));
    }
    private void verifyNoAutomaticReconnect(CloudStorage storage,CloudClient client,JSONObject fields) throws Exception {
        storage.firstSyncDone(true);
        int before=client.request("GET","/qa/uploads",null,false).getInt("count");
        for(String forced:new String[]{"expired","unauthorized"}){
            client.request("POST","/qa/forced-status",new JSONObject().put("status",forced),false);
            CloudPanel panel=new CloudPanel((Application)getTargetContext().getApplicationContext(),()->fields,null);
            Map<String,Object> state=new HashMap<>();
            runOnMainSync(()->{panel.observe(state);panel.refreshByUser();});
            awaitPanel(state,"expired".equals(forced)?"刷新成功":"操作失败：设备连接已失效");
            runOnMainSync(panel::automaticCollect);
            awaitPanel(state,"expired".equals(forced)?"云端登录已失效":"操作失败：设备连接已失效");
        }
        client.request("POST","/qa/forced-status",new JSONObject().put("status",""),false);
        if(client.request("GET","/qa/uploads",null,false).getInt("count")!=before)throw new Exception("刷新/前台任务自动重传了凭据");
    }
    private void awaitPanel(Map<String,Object> state,String expected,boolean contains) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+20000;
        AtomicReference<String> value=new AtomicReference<>("");
        while(SystemClock.elapsedRealtime()<deadline){
            runOnMainSync(()->value.set(String.valueOf(state.get("status"))));
            if(contains?value.get().contains(expected):value.get().startsWith(expected))return;
            SystemClock.sleep(50);
        }
        throw new Exception("刷新状态不符：期望"+expected+"，实际"+value.get());
    }
    private static final class ActivityResult {static final int OK=-1,FAILED=0;}
}
