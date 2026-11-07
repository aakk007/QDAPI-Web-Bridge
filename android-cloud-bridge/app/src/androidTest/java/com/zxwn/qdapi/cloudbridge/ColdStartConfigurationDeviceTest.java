package com.zxwn.qdapi.cloudbridge;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

// 不依赖任何宿主页面/已捕获请求，只使用合成账号验证主动配置及账号切换保护。
final class ColdStartConfigurationDeviceTest {
    private static final class Source implements LocalAccountConfiguration.Source {
        int reads,nativeReads;boolean ready=true,linkage,swap,loggedOut,wrongCookie,wrongNative,withIbex,noIbex,noSign;
        public String account(){reads++;return loggedOut?"":swap&&reads>1?"synthetic-b":"synthetic-a";}
        public Map<?,?> h5Headers() throws Exception {
            if(linkage)throw new NoClassDefFoundError("合成：本机组件未加载");
            if(!ready)throw new Exception("合成：模块稍后初始化");
            Map<String,String> m=new HashMap<>();m.put("cookie","ywguid="+(wrongCookie?"synthetic-b":"synthetic-a")+"; ywkey=synthetic-key");m.put("QDInfo","synthetic-info");m.put("user-agent","synthetic-agent");if(withIbex)m.put("IBEX","synthetic-device");return m;
        }
        public Map<?,?> deviceHeaders(){nativeReads++;Map<String,String> m=new HashMap<>();m.put("Cookie","ywguid="+(wrongNative?"synthetic-b":"synthetic-a"));if(!noIbex)m.put("ibex","synthetic-device");return m;}
        public String h5Sign(){return noSign?"":"synthetic-sign";}
    }
    private static void rejected(Source source) throws Exception {
        try{new LocalAccountConfiguration(source).read();}catch(Exception expected){if(expected.getMessage().contains("synthetic-key"))throw new Exception("异常泄露凭据");return;}
        throw new Exception("不完整或混用账号的配置未拒绝");
    }
    static void run(Instrumentation test){Bundle result=new Bundle();try{
        Source source=new Source();JSONObject fields=new LocalAccountConfiguration(source).read();
        if(!"synthetic-a".equals(fields.getString("ywGuid"))||!"synthetic-device".equals(fields.getString("ibex"))||source.nativeReads!=1)throw new Exception("主动SDK配置缺失");
        Source direct=new Source();direct.withIbex=true;new LocalAccountConfiguration(direct).read();if(direct.nativeReads!=0)throw new Exception("H5完整配置仍重复生成设备签名");
        Source late=new Source();late.ready=false;rejected(late);late.ready=true;new LocalAccountConfiguration(late).read();
        Source unloaded=new Source();unloaded.linkage=true;rejected(unloaded);unloaded.linkage=false;new LocalAccountConfiguration(unloaded).read();
        Source swap=new Source();swap.swap=true;rejected(swap);
        Source logout=new Source();logout.loggedOut=true;rejected(logout);if(logout.nativeReads!=0)throw new Exception("未登录仍准备任务请求");
        Source cookie=new Source();cookie.wrongCookie=true;rejected(cookie);
        Source nativeAccount=new Source();nativeAccount.wrongNative=true;rejected(nativeAccount);
        Source noIbex=new Source();noIbex.noIbex=true;rejected(noIbex);
        Source noSign=new Source();noSign.noSign=true;rejected(noSign);
        result.putString("stream","冷启动零捕获配置、主动SDK补齐、完整H5不重复生成、延迟初始化重试、退出/切换账号及字段缺失拒绝均通过；页面打开0、云端连接0、真实官方请求0\n");test.finish(-1,result);
    }catch(Exception error){result.putString("stream","冷启动配置验收失败："+error.getMessage()+"\n");test.finish(0,result);}}
}
