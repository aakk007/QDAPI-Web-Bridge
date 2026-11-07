package com.zxwn.qdapi.cloudbridge;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

// 仅保存脱敏任务事件和回执，不保存账号凭据、请求头/请求体、设备令牌或官方原始响应。
final class TaskLogStore {
    private static final Object LOCK=new Object();
    private static final String PROCESS=UUID.randomUUID().toString();
    private static final long RETENTION=30L*24*60*60*1000;
    private final File root;
    TaskLogStore(Context context){this(new File(context.getFilesDir(),"qdapi-task-logs"));}
    TaskLogStore(File root){this.root=root;}
    File directory(){return root;}
    private void ensure() throws IOException {if(!root.isDirectory() && !root.mkdirs())throw new IOException("无法创建任务日志目录，本轮未开始执行");}
    private File path(String id) throws Exception {UUID.fromString(id);return new File(root,id+".json");}
    private JSONObject read(File file) throws Exception {
        try(InputStream in=new AtomicFile(file).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("任务日志超出读取上限");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));
        }
    }
    private void write(JSONObject value) throws Exception {
        ensure();AtomicFile file=new AtomicFile(path(value.getString("id")));FileOutputStream out=null;
        String marker=value.optString("association").isEmpty()?".local":".pending";
        if(!"uploaded".equals(value.optString("upload"))){File flag=new File(root,value.getString("id")+marker);if(!flag.exists())try(FileOutputStream flagOut=new FileOutputStream(flag)){flagOut.getFD().sync();}}
        try {out=file.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    private List<File> files() throws IOException {
        ensure();File[] list=root.listFiles((dir,name)->name.matches("[0-9a-fA-F-]{36}\\.json"));
        if(list==null)throw new IOException("无法读取任务日志目录");Set<File> unique=new HashSet<>(Arrays.asList(list));
        File[] backups=root.listFiles((dir,name)->name.matches("[0-9a-fA-F-]{36}\\.json\\.bak"));if(backups==null)throw new IOException("无法读取任务日志备份");for(File backup:backups)unique.add(new File(root,backup.getName().substring(0,backup.getName().length()-4)));
        List<File> result=new ArrayList<>(unique);result.sort(Comparator.comparing(File::getName));return result;
    }
    Run begin(JSONArray tasks,String association) throws Exception {
        synchronized(LOCK){
            prune();
            JSONObject value=new JSONObject().put("id",UUID.randomUUID().toString()).put("startedAt",Instant.now().toString()).put("tasks",new JSONArray(tasks.toString())).put("logs",new JSONArray()).put("state","running").put("association",association).put("upload","not_ready").put("version",BuildConfig.VERSION_NAME).put("process",PROCESS);
            write(value);return new Run(value);
        }
    }
    final class Run {
        private final JSONObject record;
        Run(JSONObject record){this.record=record;}
        String id(){return record.optString("id");}
        void event(JSONObject source) throws Exception {
            synchronized(LOCK){JSONArray logs=record.getJSONArray("logs");if(logs.length()>=190)throw new IOException("任务事件超过回传上限，停止本轮并保留已有明细");logs.put(cleanEvent(source));record.put("lastRecordedAt",Instant.now().toString());write(record);}
        }
        void finish(JSONObject result) throws Exception {
            synchronized(LOCK){
                JSONArray complete=result.optJSONArray("logs"),saved=record.getJSONArray("logs");
                if(complete!=null){
                    // 最终包用于核对实时流的完整性，不能把同一批事件再追加一次。
                    if(saved.length()>complete.length())throw new IOException("本机日志事件数量不一致");
                    for(int i=0;i<saved.length();i++)if(!saved.getJSONObject(i).toString().equals(cleanEvent(complete.getJSONObject(i)).toString()))throw new IOException("本机实时明细与最终结果不一致");
                    for(int i=saved.length();i<complete.length();i++)saved.put(cleanEvent(complete.getJSONObject(i)));
                }
                record.put("success",result.optBoolean("success")).put("message",TaskRuntimeLog.safe(result.optString("message"))).put("finishedAt",record.optString("interruptedAt",Instant.now().toString())).put("state","complete").put("upload",record.optString("association").isEmpty()?"local_only":"pending");
                write(record);
            }
        }
        void failed(String message) throws Exception {
            synchronized(LOCK){
                JSONArray logs=record.getJSONArray("logs"),tasks=record.getJSONArray("tasks");
                for(int i=0;i<tasks.length();i++){
                    String name=taskName(tasks.optString(i));boolean terminal=false;
                    for(int j=0;j<logs.length();j++){JSONObject e=logs.getJSONObject(j);terminal|=name.equals(e.optString("name"))&&Arrays.asList("success","skipped","failed").contains(e.optString("status"));}
                    if(!terminal)logs.put(cleanEvent(new JSONObject().put("category","benefits").put("name",name).put("status","failed").put("message","执行中断或未开始，未确认本项完成："+message).put("createdAt",Instant.now().toString())));
                }
                finish(new JSONObject().put("success",false).put("message",message));
            }
        }
    }
    static JSONObject cleanEvent(JSONObject event) throws Exception {
        JSONObject out=new JSONObject();for(String key:new String[]{"category","taskId","name","status","message","createdAt"})out.put(key,TaskRuntimeLog.safe(event.optString(key)));
        out.put("current",event.optInt("current")).put("total",event.optInt("total"));return out;
    }
    static String taskName(String task){
        switch(task){case "hourly-chest":return "每小时宝箱";case "daily-rewards":return "每日任务";case "video-rewards":return "视频任务";case "game-rewards":return "游戏任务";case "checkin":return "每日签到";case "exchange":return "签到碎片兑换";case "lottery":return "免费抽奖";case "read-points":return "阅读积分领取";default:return "未知任务";}
    }
    void recoverInterrupted() throws Exception {
        synchronized(LOCK){for(File file:files()){JSONObject value=read(file);if("running".equals(value.optString("state"))&&!PROCESS.equals(value.optString("process"))){
            // 下次启动可能已相隔数天，不能把恢复时间冒充旧任务持续执行的结束时间。
            value.put("interruptedAt",value.optString("lastRecordedAt",value.getString("startedAt"))).put("recoveredAt",Instant.now().toString());
            new Run(value).failed("上次APP/本机进程中断，结束时间取最后已落盘时间，实际中断时刻不可确认；保留事件，不自动重做官方动作");
        }}}
    }
    interface Sender {JSONObject send(JSONObject payload) throws Exception;}
    int flush(String association,Sender sender) throws Exception {
        if(association==null||association.isEmpty())return 0;int sent=0;
        File[] flags;synchronized(LOCK){ensure();flags=root.listFiles((dir,name)->name.matches("[0-9a-fA-F-]{36}\\.pending"));if(flags==null)throw new IOException("待传队列不可读");Arrays.sort(flags,Comparator.comparingLong(File::lastModified));}
        for(File flag:flags){
            if(sent>=3)break;
            JSONObject r;synchronized(LOCK){File file=path(flag.getName().substring(0,36));if(!file.exists()&&!new File(file+".bak").exists())continue;r=read(file);}
            if("uploaded".equals(r.optString("upload"))){if(!flag.delete()&&flag.exists())throw new IOException("已回传标记清理失败");continue;}
            if(!association.equals(r.optString("association"))||!"pending".equals(r.optString("upload")))continue;
            if(Instant.parse(r.getString("startedAt")).isBefore(Instant.now().minusSeconds(30L*86400)))continue;
            JSONObject payload=new JSONObject().put("id",r.getString("id")).put("startedAt",r.getString("startedAt")).put("finishedAt",r.getString("finishedAt")).put("success",r.optBoolean("success")).put("message",r.optString("message")).put("logs",r.getJSONArray("logs"));
            JSONObject answer=sender.send(payload);
            if(!answer.optBoolean("saved")||answer.optLong("runId")<=0)throw new IOException("云端未确认日志入库，本机保留待传记录");
            synchronized(LOCK){JSONObject current=read(path(r.getString("id")));current.put("upload","uploaded").put("serverRunId",answer.getLong("runId")).put("uploadedAt",Instant.now().toString());write(current);if(!flag.delete()&&flag.exists())throw new IOException("已回传标记清理失败");}sent++;
        }
        return sent;
    }
    String status() throws Exception {
        synchronized(LOCK){int total=0,pending=0,local=0;for(File f:files()){String id=f.getName().substring(0,36);total++;if(new File(root,id+".pending").exists())pending++;if(new File(root,id+".local").exists())local++;}return "本机已保存"+total+"轮，待回传/执行中"+pending+"轮，仅本机"+local+"轮；常规记录保留30天，待传不自动删除";}
    }
    void operation(String name,String status,String message) throws Exception {
        synchronized(LOCK){ensure();File file=new File(root,"operations-"+java.time.LocalDate.now()+".jsonl");
            JSONObject item=cleanEvent(new JSONObject().put("name",name).put("status",status).put("message",message).put("createdAt",Instant.now().toString()));
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write((item.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }
    }
    byte[] exportBytes() throws Exception {
        synchronized(LOCK){
            StringBuilder text=new StringBuilder("QDAPI APP任务日志（七项及额外阅读积分）\n导出时间："+Instant.now()+"\n"+status()+"\n说明：检查/跳过不等于领奖成功；只重传日志，不重做任务。\n\n");
            List<JSONObject> records=new ArrayList<>();for(File f:files())records.add(read(f));records.sort(Comparator.comparing(x->x.optString("startedAt")));
            if(records.isEmpty())text.append("暂无任务执行记录；本文件不是空文件。\n");
            for(JSONObject r:records){text.append("执行ID：").append(r.optString("id")).append(" 开始：").append(r.optString("startedAt")).append(" 结束：").append(r.optString("finishedAt","未结束")).append(" 结果：").append(r.optString("state")).append(" 回传：").append(r.optString("upload")).append("\n");
                JSONArray events=r.getJSONArray("logs");for(int i=0;i<events.length();i++)text.append(events.getJSONObject(i).toString()).append('\n');text.append("摘要：").append(r.optString("message")).append("\n\n");}
            File[] operations=root.listFiles((dir,name)->name.matches("operations-\\d{4}-\\d{2}-\\d{2}\\.jsonl"));
            if(operations==null)throw new IOException("无法读取操作日志");Arrays.sort(operations,Comparator.comparing(File::getName));
            for(File file:operations){text.append("操作日志：").append(file.getName()).append('\n');try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null)text.append(line).append('\n');}}
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
    void writeExport(File target,File original) throws Exception {
        byte[] task=exportBytes();AtomicFile out=new AtomicFile(target);FileOutputStream stream=null;
        try {stream=out.startWrite();
            if(original!=null&&original.isFile()&&original.length()>0){if(original.length()>20*1024*1024)throw new IOException("原调试日志超过20MB，请先单独保存后重试");stream.write("原模块调试日志\n".getBytes(StandardCharsets.UTF_8));try(InputStream in=new FileInputStream(original)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)stream.write(b,0,n);}stream.write("\n\n追加：持久化任务日志\n".getBytes(StandardCharsets.UTF_8));}
            stream.write(task);out.finishWrite(stream);if(target.length()<task.length)throw new IOException("导出文件未完整写入");
        }catch(Exception e){if(stream!=null)out.failWrite(stream);throw e;}
    }
    private void prune() throws Exception {
        for(File f:files()){if(f.lastModified()>=System.currentTimeMillis()-RETENTION)continue;JSONObject r=read(f);String upload=r.optString("upload");if(("uploaded".equals(upload)||"local_only".equals(upload))&&Instant.parse(r.getString("startedAt")).toEpochMilli()<System.currentTimeMillis()-RETENTION){new AtomicFile(f).delete();new File(root,r.getString("id")+".local").delete();new File(root,r.getString("id")+".pending").delete();}}
        File[] operations=root.listFiles((dir,name)->name.matches("operations-\\d{4}-\\d{2}-\\d{2}\\.jsonl"));if(operations==null)throw new IOException("无法读取操作日志目录");
        for(File file:operations)if(file.lastModified()<System.currentTimeMillis()-RETENTION&&!file.delete())throw new IOException("旧操作日志轮转未完成");
    }
}
