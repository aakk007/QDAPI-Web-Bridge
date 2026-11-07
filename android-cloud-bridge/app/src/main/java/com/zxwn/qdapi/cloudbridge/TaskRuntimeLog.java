package com.zxwn.qdapi.cloudbridge;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

// logcat用于即时查看；任务明细另由TaskLogStore逐条持久化，不能依赖易丢失的logcat缓存。
final class TaskRuntimeLog {
    static final String TAG="QDAPI-Tasks";
    interface Sink {void write(int priority,String tag,String message);}
    private final Sink sink;
    private final TaskLogStore store;
    TaskRuntimeLog(){this(null,(priority,tag,message)->Log.println(priority,tag,message));}
    TaskRuntimeLog(android.content.Context context){this(new TaskLogStore(context),(priority,tag,message)->Log.println(priority,tag,message));}
    TaskRuntimeLog(Sink sink){this(null,sink);}
    private TaskRuntimeLog(TaskLogStore store,Sink sink){this.store=store;this.sink=sink;}

    void operation(String name,String status,String message){
        if(store!=null)try{store.operation(name,status,message);}catch(Exception e){sink.write(Log.ERROR,TAG,"本机操作日志保存失败，请检查存储空间；未输出凭据");}
        emit(name,status,message);
    }
    private void emit(String name,String status,String message){
        String state="failed".equals(status)?"失败":"skipped".equals(status)?"跳过":"running".equals(status)?"进行中":"progress".equals(status)?"检查":"成功";
        sink.write("failed".equals(status)?Log.ERROR:Log.INFO,TAG,"[APP自动任务]["+state+"]["+safe(name)+"] "+safe(message));
    }
    void tasks(JSONArray logs){
        if(logs==null)return;
        for(int i=0;i<logs.length();i++){
            JSONObject item=logs.optJSONObject(i);if(item==null)continue;
            String status=item.optString("status");
            if(!java.util.Arrays.asList("running","progress","success","skipped","failed").contains(status))continue;
            String progress=item.optInt("total")>0?" ("+item.optInt("current")+"/"+item.optInt("total")+")":"";
            emit(item.optString("name","任务"),status,item.optString("message")+progress);
        }
    }
    static String safe(String value){
        if(value==null)return "";
        String text=value.replaceAll("(?i)https?://[^\\s]+","[地址已隐藏]")
            .replaceAll("(?i)(ywkey|ywguid|qdinfo|sdksign|ibex|authorization|cookie|token|绑定码)\\s*[:=]\\s*[^\\s,;]+","$1=[已脱敏]")
            .replaceAll("[\\r\\n\\p{Cntrl}]+"," ").trim();
        return text.length()>1600?text.substring(0,1600)+"…":text;
    }
}
