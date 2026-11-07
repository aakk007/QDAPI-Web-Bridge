package com.zxwn.qdapi.cloudbridge;

import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

// 在独立测试包进程中执行；只允许合成凭据/合成响应，真实官方请求和上传凭据均为0。
final class DurableLogDeviceTest {
    static void run(Instrumentation test,Bundle args){
        Bundle output=new Bundle();
        try {
            File root=new File(test.getTargetContext().getFilesDir(),"durable-log-device-qa");
            TaskLogStore store=new TaskLogStore(root);
            String phase=args.getString("durableLogTest");
            JSONArray tasks=new JSONArray();for(String t:CloudStorage.APP_TASKS)tasks.put(t);
            if("seed".equals(phase)){
                TaskLogStore.Run interrupted=store.begin(tasks,"qa-association");
                interrupted.event(new JSONObject().put("name","每小时宝箱").put("status","running").put("message","中断前已落盘"));
                TaskLogStore.Run overnight=store.begin(tasks,"qa-association");
                File oldFile=new File(root,overnight.id()+".json");JSONObject old=new JSONObject(new String(read(oldFile),StandardCharsets.UTF_8));
                old.put("startedAt",java.time.Instant.now().minusSeconds(21600).toString()).put("lastRecordedAt",java.time.Instant.now().minusSeconds(21599).toString());
                try(FileOutputStream out=new FileOutputStream(oldFile)){out.write(old.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
                output.putString("stream","已持久保存未完成轮，等待独立进程恢复测试\n");test.finish(-1,output);return;
            }
            store.recoverInterrupted();
            String recovered=new String(store.exportBytes(),StandardCharsets.UTF_8);
            if(!recovered.contains("上次APP/本机进程中断"))throw new Exception("跨进程中断日志未恢复");
            for(File file:root.listFiles((dir,name)->name.endsWith(".json"))){JSONObject item=new JSONObject(new String(read(file),StandardCharsets.UTF_8));if(item.has("recoveredAt")&&java.time.Duration.between(java.time.Instant.parse(item.getString("startedAt")),java.time.Instant.parse(item.getString("finishedAt"))).toHours()>=2)throw new Exception("隔夜恢复时间冒充旧任务执行结束时间");}
            JSONObject fields=new JSONObject(new String(Base64.decode(args.getString("fields"),Base64.DEFAULT),StandardCharsets.UTF_8));
            if(!"synthetic-phone-auto-connect".equals(fields.optString("ywGuid")))throw new Exception("仅允许合成账号");
            final int[] requests={0};
            LocalEngineRunner engine=new LocalEngineRunner(test.getTargetContext(),job->{
                requests[0]++;String path=new java.net.URL(job.getString("url")).getPath(),data="{}";
                if(path.endsWith("mainPage"))data="{}";
                else if(path.endsWith("getcurrentweekcheckininfo"))data="{\"IsSunday\":0,\"LotteryInfo\":{\"LotteryCount\":0,\"HasVideoUrge\":0}}";
                else if(path.endsWith("checkinexchangepage"))data="{\"Balance\":7,\"Goods\":[]}";
                else if(path.endsWith("readpage"))data="{\"ReadInfo\":{\"ReadScore\":100},\"TodayRead\":{\"TotalReadTime\":0,\"RewardList\":[]},\"WeekRead\":{\"TotalReadTime\":0,\"RewardList\":[{\"PackageId\":10,\"Time\":48000,\"ScoreAmount\":500,\"HasPickUp\":0},{\"PackageId\":11,\"Time\":72000,\"ScoreAmount\":1000,\"HasPickUp\":0}]},\"TaskModule\":{\"TaskList\":[{\"TaskId\":100000000062,\"Type\":8004,\"Status\":0,\"Reward\":200}]},\"ReadPK\":{\"PKStatus\":1,\"WinScore\":0}}";
                else if(!path.endsWith("checkin/checkin"))throw new Exception("范围外合成接口");
                return new JSONObject().put("status",200).put("body","{\"Result\":0,\"Data\":"+data+"}");
            });
            TaskLogStore.Run run=store.begin(tasks,"qa-association");
            JSONObject result=engine.run(fields,tasks,()->{},event->{run.event(event);
                if(!new String(new TaskLogStore(root).exportBytes(),StandardCharsets.UTF_8).contains(event.getString("name")))throw new Exception("下一步动作前明细尚未落盘");
            });run.finish(result);
            if(!result.optBoolean("success"))throw new Exception("本机七项测试执行失败");
            int officialBeforeRetry=requests[0];List<String> ids=new ArrayList<>();
            try {store.flush("qa-association",body->{ids.add(body.getString("id"));throw new IOException("合成：服务端已保存但回执丢失");});throw new Exception("失败未上抛");}catch(IOException expected){}
            if(ids.size()!=1)throw new Exception("失败被静默丢弃");String first=ids.get(0);
            TaskLogStore reloaded=new TaskLogStore(root);final int[] attempts={0};
            reloaded.flush("different-account",body->{throw new Exception("日志串到另一个账号");});
            reloaded.flush("qa-association",body->{attempts[0]++;if(attempts[0]==1&&!first.equals(body.getString("id")))throw new Exception("重试执行ID变化");return new JSONObject().put("saved",true).put("duplicate",true).put("runId",123+attempts[0]);});
            reloaded.flush("qa-association",body->{throw new Exception("已确认记录重复上传");});
            if(requests[0]!=officialBeforeRetry)throw new Exception("补传重做了官方任务");
            TaskLogStore.Run local=reloaded.begin(tasks,"");local.event(new JSONObject().put("name","脱敏检查").put("status","skipped").put("message","ywkey=qa-secret https://example.invalid/?token=qa-secret"));local.finish(new JSONObject().put("success",true).put("message","仅本机"));
            reloaded.flush("qa-association",body->{throw new Exception("未授权本机记录被上传");});
            File original=new File(test.getTargetContext().getCacheDir(),"original-log-qa.txt"),export=new File(test.getTargetContext().getCacheDir(),"durable-log-export-qa.txt");
            try(FileOutputStream out=new FileOutputStream(original)){out.write("original-log-retained\n".getBytes(StandardCharsets.UTF_8));}
            reloaded.writeExport(export,original);String exported=new String(read(export),StandardCharsets.UTF_8);
            for(String task:CloudStorage.APP_TASKS)if(!exported.contains(TaskLogStore.taskName(task)))throw new Exception("导出缺少任务："+task);
            for(String s:new String[]{"每周阅读800分钟资格","每周阅读1200分钟资格","作品更新提醒积分资格","阅读PK积分资格","original-log-retained"})if(!exported.contains(s))throw new Exception("明细导出缺失："+s);
            if(exported.contains("qa-secret")||export.length()==0||!new String(read(original),StandardCharsets.UTF_8).equals("original-log-retained\n"))throw new Exception("导出为空/未脱敏/覆盖原日志");
            File broken=new File(test.getTargetContext().getCacheDir(),"not-a-log-directory");try(FileOutputStream out=new FileOutputStream(broken)){out.write(1);}
            try{new TaskLogStore(broken).begin(tasks,"");throw new Exception("存储不可用仍开始执行");}catch(IOException expected){}
            output.putString("stream","跨进程恢复、逐事件落盘、七项及阅读五类资格完整导出、断网稳定ID重传、跨账号/未授权不传、回执幂等、原日志保留、脱敏及非空导出通过；导出"+export.length()+"字节；真实官方请求0\n");test.finish(-1,output);
        }catch(Exception e){output.putString("stream","持久日志验收失败："+e.getClass().getSimpleName()+" "+e.getMessage()+"\n");test.finish(0,output);}
    }
    private static byte[] read(File f)throws Exception {try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
}
