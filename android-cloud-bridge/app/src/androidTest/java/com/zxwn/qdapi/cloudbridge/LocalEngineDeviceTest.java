package com.zxwn.qdapi.cloudbridge;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// 只用参数传入的合成凭据和合成官方响应，不构造CloudClient或连接云端。
final class LocalEngineDeviceTest {
    static void run(Instrumentation test,Bundle args){
        Bundle output=new Bundle();
        try{
            JSONObject fields=new JSONObject(new String(Base64.decode(args.getString("fields"),Base64.DEFAULT),StandardCharsets.UTF_8));
            if(!"synthetic-phone-auto-connect".equals(fields.optString("ywGuid")))throw new Exception("仅允许指定合成账号");
            if(Boolean.parseBoolean(args.getString("weeklyThirtyOnly","false"))){
                verifyWeeklyThirty(test,fields);
                output.putString("stream","本机30碎片仅兑换一张20点章节卡、回读余额和新增记录、第二轮不重兑通过；真实官方请求0\n");test.finish(-1,output);return;
            }
            Map<String,Integer> done=new HashMap<>(),received=new HashMap<>();Set<String> actions=new HashSet<>();final int[] beats={0};
            LocalEngineRunner engine=new LocalEngineRunner(test.getTargetContext(),job->{
                String path=new java.net.URL(job.getString("url")).getPath();String data="{}";
                if(path.endsWith("mainPage")){
                    JSONObject page=new JSONObject().put("NickName","本机合成账号");
                    for(String id:new String[]{"chest","daily","video","game"}){
                        int progress="game".equals(id)?(beats[0]>=2?1:0):done.getOrDefault(id,0);
                        JSONObject task=new JSONObject().put("TaskId",id).put("TaskType","game".equals(id)?104:"video".equals(id)?3:0).put("IsFinished",done.getOrDefault(id,0)).put("IsReceived",received.getOrDefault(id,0)).put("Process",progress).put("Total",1).put("IntervalTime","0");
                        if("chest".equals(id))page.put("SurpriseBenefit",task);else page.put("daily".equals(id)?"DailyBenefitModule":"video".equals(id)?"VideoRewardTab":"MoreRewardTab",new JSONObject().put("TaskList",new JSONArray().put(task)));
                    }
                    data=page.toString();
                }else if(path.endsWith("finishWatch")||path.endsWith("receiveTaskReward")){
                    String id=java.net.URLDecoder.decode(job.getString("body").split("taskId=")[1].split("&")[0],"UTF-8");
                    if(!actions.add(path+id))throw new Exception("本机引擎重复提交");
                    if(path.endsWith("finishWatch"))done.put(id,1);else received.put(id,1);
                }else if(path.endsWith("heartbeat")){beats[0]++;return new JSONObject().put("status",200).put("body","{\"code\":0,\"data\":{}}");}
                else if(path.endsWith("getcurrentweekcheckininfo"))data="{\"IsSunday\":0,\"LotteryInfo\":{\"LotteryCount\":0}}";
                else if(path.endsWith("checkinexchangepage"))data="{\"Balance\":0,\"Goods\":[]}";
                else if(!path.endsWith("/checkin/checkin"))throw new Exception("本机合成任务出现未知接口");
                return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
            });
            JSONArray selected=new JSONArray();for(String id:CloudStorage.APP_TASKS)if(!"read-points".equals(id))selected.put(id);
            long started=android.os.SystemClock.elapsedRealtime();
            JSONObject result=engine.run(fields,selected,()->{});
            if(!result.optBoolean("success")||actions.size()!=5||beats[0]!=2||android.os.SystemClock.elapsedRealtime()-started<60000)throw new Exception("本机七项/游戏间隔验收失败");
            Set<String> names=new HashSet<>();JSONArray logs=result.getJSONArray("logs");
            for(int i=0;i<logs.length();i++){JSONObject log=logs.getJSONObject(i);if("success".equals(log.optString("status"))||"skipped".equals(log.optString("status")))names.add(log.optString("name"));}
            for(String name:new String[]{"每小时宝箱","每日任务","视频任务","游戏任务","每日签到","签到碎片兑换","免费抽奖"})if(!names.contains(name))throw new Exception("本机日志缺失："+name);
            verifyReadingAndLottery(test,fields);
            verifyWeeklyThirty(test,fields);
            if(!BuildConfig.APPLICATION_ID.equals(test.getTargetContext().getPackageName()))throw new Exception("禁止修改宿主测试数据");
            test.getTargetContext().getSharedPreferences("qdapi_cloud_bridge",0).edit().clear().commit();
            CloudStorage storage=new CloudStorage(test.getTargetContext());for(String id:CloudStorage.APP_TASKS)storage.taskEnabled(id,false);
            java.util.List<String> panelLogs=new java.util.concurrent.CopyOnWriteArrayList<>();
            CloudPanel panel=new CloudPanel((android.app.Application)test.getTargetContext().getApplicationContext(),()->fields,job->{throw new Exception("全关时不应发起任何官方请求");},new TaskRuntimeLog((priority,tag,message)->panelLogs.add(message)));
            panel.enableLocalExecution(false);test.runOnMainSync(panel::automaticCollect);
            long limit=android.os.SystemClock.elapsedRealtime()+5000;
            while(android.os.SystemClock.elapsedRealtime()<limit && panelLogs.stream().noneMatch(line->line.contains("[成功][APP本机自动检查福利]")))android.os.SystemClock.sleep(50);
            if(panelLogs.stream().noneMatch(line->line.contains("[成功][APP本机自动检查福利]")) || storage.firstSyncDone() || !storage.token().isEmpty() || storage.hasAccount())throw new Exception("未上传无法自动运行，或自动创建了云端连接");
            for(String id:CloudStorage.APP_TASKS)storage.taskEnabled(id,!"read-points".equals(id));
            output.putString("stream","未上传本机七项、真实60秒游戏间隔、四类积分五笔领取、连续两次任务+1及抽奖、重复不重领均通过；云端连接0、真实官方请求0\n");test.finish(-1,output);
        }catch(Exception e){output.putString("stream","本机引擎验收失败："+e.getClass().getSimpleName()+" "+e.getMessage()+"\n");test.finish(0,output);}
    }

    private static void verifyWeeklyThirty(Instrumentation test,JSONObject fields) throws Exception {
        final int[] fragments={30},claims={0};
        LocalEngineRunner engine=new LocalEngineRunner(test.getTargetContext(),job->{
            String path=new java.net.URL(job.getString("url")).getPath(),data="{}";
            if(path.endsWith("getcurrentweekcheckininfo"))data="{\"IsSunday\":1}";
            else if(path.endsWith("checkinexchangepage")){
                JSONArray goods=new JSONArray();int[] prices={15,20,30},faces={10,15,20};
                for(int i=0;i<3;i++)goods.put(new JSONObject().put("GoodsId",819+i).put("GoodsName","章节卡").put("ChapterCardCount",String.valueOf(faces[i])).put("GoodsScore",prices[i]).put("Status",fragments[0]>=prices[i]?1:3));
                data=new JSONObject().put("Balance",fragments[0]).put("Goods",goods).toString();
            }else if(path.endsWith("checkinexchangelog"))data=claims[0]==0?"[]":"[{\"Name\":\"20点章节卡\",\"Time\":12345,\"Count\":30}]";
            else if(path.endsWith("exchangegoods")){
                if(!"POST".equals(job.optString("method")) || !"goodId=821".equals(job.optString("body")) || fragments[0]!=30 || claims[0]!=0)throw new Exception("未选择最大单张或重复兑换");
                fragments[0]=0;claims[0]++;
            }else throw new Exception("30碎片契约出现范围外接口");
            return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
        });
        for(int i=0;i<2;i++)if(!engine.run(fields,new JSONArray().put("exchange"),()->{}).optBoolean("success"))throw new Exception("30碎片本机回读失败");
        if(fragments[0]!=0 || claims[0]!=1)throw new Exception("满30应当只兑换一次");
    }

    private static void verifyReadingAndLottery(Instrumentation test,JSONObject fields) throws Exception {
        final int[] score={100},chance={0},taskAdds={0},draws={0},claims={0};final Set<String> picked=new HashSet<>();
        LocalEngineRunner engine=new LocalEngineRunner(test.getTargetContext(),job->{
            java.net.URL uri=new java.net.URL(job.getString("url"));String path=uri.getPath(),data="{}";
            if(path.endsWith("readpage")){
                JSONObject page=new JSONObject().put("ReadInfo",new JSONObject().put("ReadScore",score[0]));
                page.put("TodayRead",new JSONObject().put("TotalReadTime",72000).put("RewardList",new JSONArray().put(new JSONObject().put("PackageId",1).put("Time",300).put("Rewards",new JSONArray().put(new JSONObject().put("Id",1).put("Amount",30).put("HasPickUp",picked.contains("day")?1:0))))));
                JSONArray weeks=new JSONArray();for(int id:new int[]{10,11})weeks.put(new JSONObject().put("PackageId",id).put("Time",id==10?48000:72000).put("ScoreAmount",id==10?500:1000).put("HasPickUp",picked.contains("week"+id)?1:0));
                page.put("WeekRead",new JSONObject().put("TotalReadTime",72000).put("RewardList",weeks));
                page.put("TaskModule",new JSONObject().put("TaskList",new JSONArray().put(new JSONObject().put("TaskId",100000000062L).put("Type",8004).put("Status",picked.contains("notify")?2:1).put("Reward",200))));
                page.put("ReadPK",new JSONObject().put("PKStatus",picked.contains("pk")?6:5).put("WinScore",5000));data=page.toString();
            }else if(path.endsWith("batchPickup/risk")){if(!picked.add("day")||!java.net.URLDecoder.decode(job.getString("body"),"UTF-8").contains("\"packageId\":1"))throw new Exception("日读重复或参数错误");score[0]+=30;claims[0]++;}
            else if(path.endsWith("score/pickup/risk")){String id=uri.getQuery().contains("packageId=10")?"10":"11";if(!picked.add("week"+id)||!uri.getQuery().contains("rewardId=0"))throw new Exception("周读重复或参数错误");score[0]+="10".equals(id)?500:1000;claims[0]++;}
            else if(path.endsWith("daymission/pickup/risk")){if(!picked.add("notify")||!uri.getQuery().contains("taskId=100000000062"))throw new Exception("提醒参数错误");score[0]+=200;claims[0]++;}
            else if(path.endsWith("pk/reward/pickup/risk")){if(!picked.add("pk"))throw new Exception("PK重复");score[0]+=5000;claims[0]++;}
            else if(path.endsWith("getcurrentweekcheckininfo"))data="{\"AdvEnable\":0,\"LotteryInfo\":{\"LotteryCount\":"+chance[0]+",\"HasVideoUrge\":"+(taskAdds[0]<2?1:0)+"}}";
            else if(path.endsWith("risk/check"))data="{\"RiskConf\":{\"BanId\":0,\"SessionKey\":\"synthetic-local-session\"}}";
            else if(path.endsWith("video/callback")){if(!job.getString("body").contains("videoSucc=0")||!job.getString("body").contains("appId=1002")||chance[0]!=0)throw new Exception("任务增次错误");taskAdds[0]++;chance[0]++;}
            else if(path.endsWith("checkin/lottery")){if(chance[0]!=1)throw new Exception("无次数抽奖");chance[0]--;draws[0]++;data="{\"Type\":17,\"Count\":2,\"RewardName\":\"合成积分\"}";}
            else throw new Exception("四类福利合成契约出现未知接口");
            return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
        });
        JSONArray tasks=new JSONArray().put("lottery").put("read-points");
        for(int i=0;i<2;i++)if(!engine.run(fields,tasks,()->{}).optBoolean("success"))throw new Exception("四类福利本机执行失败");
        if(score[0]!=6830||claims[0]!=5||taskAdds[0]!=2||draws[0]!=2||chance[0]!=0)throw new Exception("本机四类积分、两次增次抽奖及重复执行核验失败");
    }
}
