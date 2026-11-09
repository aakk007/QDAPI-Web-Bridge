package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class CloudHook implements IXposedHookLoadPackage {
    static final String TARGET="com.qidian.QDReader";
    private static boolean attached;
    private static volatile LocalAccountConfiguration configuration;
    private static volatile TaskRuntimeLog runtimeLog;
    private static final AtomicBoolean configurationLogged=new AtomicBoolean();
    static volatile CloudPanel panel;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if(!TARGET.equals(param.packageName))return;
        SilentRewardAd.watchModule(param.classLoader);
        XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam hook){
                synchronized(CloudHook.class){if(attached)return;attached=true;}
                Application app=(Application)hook.thisObject;
                if(android.os.Build.VERSION.SDK_INT>=28 && !TARGET.equals(Application.getProcessName()))return;
                configuration=LocalAccountConfiguration.forHost(app.getClassLoader(),app);runtimeLog=new TaskRuntimeLog(app);
                panel=new CloudPanel(app);
                panel.enableLocalExecution(true);
                CheckInRewardAd.install(app.getClassLoader());
                app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
                    public void onActivityPreCreated(Activity a,Bundle b){SilentRewardAd.created(a);}
                    public void onActivityResumed(Activity activity){SilentRewardAd.created(activity);CheckInRewardAd.resumed(activity);CloudSettings.install(activity);panel.rememberActivity(activity);}
                    public void onActivityCreated(Activity a,Bundle b){SilentRewardAd.created(a);CloudSettings.install(a);}
                    public void onActivityStarted(Activity a){}
                    public void onActivityPaused(Activity a){SilentRewardAd.paused(a);CheckInRewardAd.paused(a);}
                    public void onActivityStopped(Activity a){}
                    public void onActivitySaveInstanceState(Activity a,Bundle b){}
                    public void onActivityDestroyed(Activity a){SilentRewardAd.paused(a);}
                });
            }
        });
    }

    static JSONObject configuration() throws Exception {
        LocalAccountConfiguration source=configuration;
        if(source==null)throw new Exception("起点本机模块尚未初始化，将自动重试；未上传数据");
        JSONObject fields=source.read();
        if(configurationLogged.compareAndSet(false,true))runtimeLog.operation("本机配置准备","success","已主动取得当前本机账号与签名；无需进入福利中心，未上传登录凭据");
        return fields;
    }
}
