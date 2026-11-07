package com.zxwn.qdapi.cloudbridge;

import android.content.Context;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

// 仅在内存使用宿主H5同源配置/签名接口，不构造Call、不打开页面、不发送或上传配置。
final class LocalAccountConfiguration {
    static final String PROBE="https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage";
    interface Source {
        String account() throws Exception;
        Map<?,?> h5Headers() throws Exception;
        Map<?,?> deviceHeaders() throws Exception;
        String h5Sign() throws Exception;
    }
    private final Source source;
    LocalAccountConfiguration(Source source){this.source=source;}
    JSONObject read() throws Exception {
        try {
        String account=source.account();
        if(empty(account)||"0".equals(account))throw new Exception("起点尚未登录或账号尚未初始化，本机任务等待重试；未上传数据");
        Map<?,?> h5=source.h5Headers();
        String cookies=value(h5,"Cookie"),guid=cookie(cookies,"ywguid"),key=cookie(cookies,"ywkey");
        if(!account.equals(guid)||empty(key))throw new Exception("起点账号配置初始化或切换中，本轮等待重试；未混用旧账号");
        String info=value(h5,"QDInfo");if(empty(info))info=cookie(cookies,"QDInfo");
        String ibex=value(h5,"ibex"),agent=value(h5,"User-Agent");
        if(empty(ibex)||empty(agent)){
            Map<?,?> nativeHeaders=source.deviceHeaders();
            String nativeGuid=cookie(value(nativeHeaders,"Cookie"),"ywguid");
            if(!empty(nativeGuid)&&!account.equals(nativeGuid))throw new Exception("起点账号已切换，本轮停止准备配置；未混用旧账号");
            if(empty(ibex))ibex=value(nativeHeaders,"ibex");
            if(empty(agent))agent=value(nativeHeaders,"User-Agent");
        }
        String sdk=source.h5Sign();
        if(empty(info)||empty(ibex)||empty(agent)||empty(sdk))throw new Exception("起点本机签名未就绪（账号信息="+!empty(info)+"，设备签名="+!empty(ibex)+"，设备类型="+!empty(agent)+"，H5签名="+!empty(sdk)+"），将自动重试；未上传数据");
        if(!account.equals(source.account()))throw new Exception("起点账号已切换，本轮停止；未上传新账号");
        return new JSONObject().put("sdkSign",sdk).put("ibex",ibex).put("userAgent",agent).put("qdInfo",info).put("ywKey",key).put("ywGuid",guid);
        }catch(LinkageError unready){throw new Exception("起点本机组件仍在加载，将自动重试；无需进入福利中心，未上传数据");}
    }
    private static boolean empty(String text){return text==null||text.isEmpty()||"null".equals(text);}
    private static String value(Map<?,?> map,String key){if(map!=null)for(Map.Entry<?,?> entry:map.entrySet())if(key.equalsIgnoreCase(String.valueOf(entry.getKey()))&&entry.getValue()!=null)return String.valueOf(entry.getValue());return "";}
    private static String cookie(String text,String key){for(String part:text.split(";")){int i=part.indexOf('=');if(i>0&&key.equalsIgnoreCase(part.substring(0,i).trim()))return part.substring(i+1).trim();}return "";}

    static LocalAccountConfiguration forHost(ClassLoader loader,Context context){return new LocalAccountConfiguration(new Source(){
        private Object fock() throws Exception {Class<?> type=Class.forName("com.qidian.QDReader.component.util.FockUtil",false,loader);return type.getField("INSTANCE").get(null);}
        public String account() throws Exception {
            try{Class<?> type=Class.forName("com.qidian.QDReader.component.user.QDUserManager",false,loader);Object user=type.getMethod("getInstance").invoke(null);Object guid=type.getMethod("t").invoke(user);return guid==null?"":String.valueOf(guid);}
            catch(Exception error){throw new Exception("起点登录模块尚未初始化，将自动重试；无需打开福利中心");}
        }
        public Map<?,?> h5Headers() throws Exception {
            try{Object utility=fock();Object result=utility.getClass().getMethod("getH",String.class,String.class,Class.forName("okhttp3.RequestBody",false,loader)).invoke(utility,PROBE,"GET",null);if(result instanceof Map)return (Map<?,?>)result;}
            catch(Exception ignored){}
            throw new Exception("起点请求配置尚未初始化，将自动重试；无需打开福利中心");
        }
        public Map<?,?> deviceHeaders() throws Exception {
            try{
                Object utility=fock();Object key=utility.getClass().getMethod("getNibKey").invoke(utility);
                if(!"ibex".equals(key))throw new Exception("当前设备签名字段尚未适配");
                // 与AppJsPlugin.riskControlInfo完全相同的本机SDK入口，无需WebView或福利页面。
                Object signature=Class.forName("com.yuewen.nib.search",false,loader).getMethod("cihai",Context.class).invoke(null,context);
                Map<String,String> headers=new HashMap<>();if(signature!=null)headers.put("ibex",String.valueOf(signature));
                return headers;
            }catch(Exception error){throw new Exception("起点本机设备签名尚未就绪，将自动重试；无需打开福利中心");}
        }
        public String h5Sign() throws Exception {
            try{
                Object clock=Class.forName("pi.judian",false,loader).getDeclaredMethod("e").invoke(null);
                Object stamp=clock.getClass().getMethod("f").invoke(clock);long now=stamp instanceof Number?((Number)stamp).longValue():System.currentTimeMillis();
                Method signer=Class.forName("u6.d",false,loader).getDeclaredMethod("judian",JSONObject.class,int.class,long.class);signer.setAccessible(true);
                Object pair=signer.invoke(null,new JSONObject(),2,now);Object result=pair.getClass().getField("first").get(pair);return result==null?"":String.valueOf(result);
            }catch(Exception error){throw new Exception("起点本机H5签名尚未就绪，将自动重试；无需打开福利中心");}
        }
    });}
}
