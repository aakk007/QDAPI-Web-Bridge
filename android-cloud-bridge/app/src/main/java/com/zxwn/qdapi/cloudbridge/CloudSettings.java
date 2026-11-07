package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONArray;

// QDReadHook 3.5.3 的原生设置模型适配，不替换页面、不修改原模块 APK。
final class CloudSettings {
    private static final String CATEGORY="qdapi.cloud";
    private static final Set<ClassLoader> installed=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Object,Binding> bindings=new WeakHashMap<>();
    private static final Map<ClassLoader,Object> cloudIcons=new WeakHashMap<>();

    static synchronized void install(Activity activity){
        ClassLoader loader=activity.getClass().getClassLoader();
        if(installed.contains(loader))return;
        try{
            Class<?> screen=Class.forName("cn.xihan.qdds.activity.ModuleSettingActivity",false,loader);
            if(!screen.isInstance(activity))return;
            Class<?> factory=Class.forName("x.sa3",false,loader);
            Method build=null;
            for(Method method:factory.getDeclaredMethods()){
                if(Modifier.isStatic(method.getModifiers()) && method.getReturnType()==ArrayList.class && Arrays.equals(method.getParameterTypes(),new Class<?>[]{screen})){build=method;break;}
            }
            if(build==null)throw new NoSuchMethodException("native settings factory");
            installTaskBindings(loader);
            TaskLogExport.install(loader);
            XposedHelpers.findAndHookMethod(factory,build.getName(),screen,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam hook){
                    if(hook.getThrowable()!=null || !(hook.getResult() instanceof List))return;
                    try{append((Activity)hook.args[0],(List<Object>)hook.getResult(),loader);}
                    catch(Throwable e){Log.w("QDAPI-Cloud","原生云端分类未加载，原设置保持不变",e);}
                }
            });
            installed.add(loader);
        }catch(ClassNotFoundException ignored){
            // 不是模块设置页时不注入任何入口。
        }catch(Throwable e){Log.w("QDAPI-Cloud","当前模块设置版本尚未适配",e);}
    }

    private static void append(Activity activity,List<Object> categories,ClassLoader loader) throws Exception {
        int index=-1;
        for(int i=0;i<categories.size();i++){
            Object category=categories.get(i);
            if(CATEGORY.equals(XposedHelpers.getObjectField(category,"assert")))return;
            if("关于".equals(XposedHelpers.getObjectField(category,"break"))){index=i;}
        }
        if(index<0)return;
        Object icon=cloudIcon(loader);
        Binding binding=new Binding(activity);
        Map<String,Object> state=(Map<String,Object>)construct(loader,"x.zf3");
        CloudHook.panel.observe(state);
        Object status=construct(loader,"x.va1",CATEGORY+".status","云端状态",Collections.emptyList(),function(loader,"kotlin.jvm.functions.Function0",args->state.get("status")));
        List<Object> actions=Arrays.asList(
            action(loader,"sync","一键同步并领取福利","阅读并确认上传说明后同步，APP本机执行所选七项任务",()->binding.sync()),
            action(loader,"refresh","刷新云端状态","查看账号、托管状态和最近同步时间",()->CloudHook.panel.refreshByUser()),
            action(loader,"pause","暂停自动托管","暂停APP自动领取及云端调度，保留账号配置",()->CloudHook.panel.pause()));
        List<Object> connection=Arrays.asList(
            confirmation(loader,"unbind","解除设备绑定","仅解除设备连接，不会停止云端签到。如需停止签到，请先暂停。",()->CloudHook.panel.unbind()),
            confirmation(loader,"reset","仅清除本机连接","云端账号和任务保持不变，下次须手动点击同步并确认上传。",()->CloudHook.panel.reset()));
        List<Object> groups=Arrays.asList(
            construct(loader,"x.u93","云端状态",Collections.singletonList(status)),
            construct(loader,"x.u93","APP自动任务（7项独立开关）",Arrays.asList(
                toggle(loader,"hourly-chest","每小时宝箱","自动执行宝箱任务，领取后回读核验"),
                toggle(loader,"daily-rewards","每日任务","本机免广告执行每日任务，领取后回读核验"),
                toggle(loader,"video-rewards","视频任务","本机免广告执行视频任务，领取后回读核验"),
                toggle(loader,"game-rewards","游戏任务","自动执行游戏进度任务，达标后领取并回读"),
                toggle(loader,"checkin","自动签到","检查今日签到状态，未签到时自动签到"),
                toggle(loader,"exchange","自动兑换章节卡","周日开放且碎片足够时兑换，回读扣减及兑换记录"),
                toggle(loader,"lottery","免费抽奖","完成官方免广告加次后自动抽奖，回读次数与奖品，不打开结果弹窗"))),
            construct(loader,"x.u93","额外可选任务",Collections.singletonList(
                toggle(loader,"read-points","达标积分自动领取","额外选项：日读、每周800/1200分钟、更新提醒及PK奖励"))),
            construct(loader,"x.u93","同步与运行",actions),
            construct(loader,"x.u93","任务日志",Arrays.asList(
                construct(loader,"x.va1",CATEGORY+".logs.status","本机日志与待传状态",Collections.emptyList(),function(loader,"kotlin.jvm.functions.Function0",args->CloudHook.panel.logStatus())),
                action(loader,"logs.retry","立即补传任务日志","仅重传已保存日志，不重做任务、不上传登录凭据",()->CloudHook.panel.retryLogs(true)))),
            construct(loader,"x.u93","设备连接",connection),
            construct(loader,"x.u93","项目来源与许可",Collections.singletonList(
                action(loader,"credits","来源、作者与许可","pzx521521 / xihan123 原项目、集成说明及许可边界",()->binding.credits()))));
        Object category=construct(loader,"x.t93",CATEGORY,"云端托管","七项本机优先；手动上传后才启用云端兜底",icon,groups);
        bindings.put(category,binding);
        categories.add(index,category);
        CloudHook.panel.entered();
    }

    private static Object toggle(ClassLoader loader,String id,String title,String description) throws Exception {
        return construct(loader,"x.el3",CATEGORY+".task."+id,title,description,null,Collections.emptyList(),24);
    }

    private static void installTaskBindings(ClassLoader loader) throws Exception {
        Class<?> model=Class.forName("x.v93",false,loader);
        Class<?> mmkv=Class.forName("com.tencent.mmkv.MMKV",false,loader);
        // 原生开关由v93的SnapshotStateMap驱动；只初始化和监听我们自己的键。
        XposedHelpers.class.getMethod("findAndHookConstructor",Class.class,Object[].class).invoke(null,model,new Object[]{mmkv,List.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam hook){
                Map<String,Object> values=(Map<String,Object>)XposedHelpers.getObjectField(hook.thisObject,"case");
                for(String id:CloudStorage.APP_TASKS)values.put(CATEGORY+".task."+id,CloudHook.panel.taskEnabled(id));
            }
        }});
        XposedHelpers.findAndHookMethod(Class.forName("x.s11",false,loader),"invoke",Object.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam hook){
                Object setting=XposedHelpers.getObjectField(hook.thisObject,"default");
                String key=String.valueOf(XposedHelpers.getObjectField(setting,"assert"));
                String prefix=CATEGORY+".task.";
                if(key.startsWith(prefix) && hook.args[0] instanceof Boolean)CloudHook.panel.setTask(key.substring(prefix.length()),(Boolean)hook.args[0]);
            }
        });
    }

    private static Object cloudIcon(ClassLoader loader) throws Exception {
        Object cached=cloudIcons.get(loader);if(cached!=null)return cached;
        // 复用原模块的 24dp Filled ImageVector、单色填充和主题着色，箭头以反向路径镂空。
        Object builder=construct(loader,"x.c91","Filled.CloudUpload",24f,24f,24f,24f,0L,0,false,96);
        Object path=construct(loader,"x.yw0",(byte)2);
        vectorPath(path,"false",6f,19f);
        vectorPath(path,"class",3.2f,19f,1f,16.8f,1f,14f);
        vectorPath(path,"class",1f,11.5f,2.8f,9.4f,5.2f,9f);
        vectorPath(path,"class",6.2f,6f,8.9f,4f,12f,4f);
        vectorPath(path,"class",15.7f,4f,18.8f,6.8f,19.2f,10.4f);
        vectorPath(path,"class",21.4f,10.5f,23f,12.3f,23f,14.5f);
        vectorPath(path,"class",23f,17f,21f,19f,18.5f,19f);
        vectorPath(path,"catch");
        vectorPath(path,"false",11f,16f);
        for(float[] point:new float[][]{{13f,16f},{13f,12f},{16f,12f},{12f,8f},{8f,12f},{11f,12f}})vectorPath(path,"else",point);
        vectorPath(path,"catch");
        java.lang.reflect.Field black=Class.forName("x.tu",false,loader).getDeclaredField("break");black.setAccessible(true);
        Object brush=construct(loader,"x.ig3",black.getLong(null));
        Method add=builder.getClass().getDeclaredMethod("assert",builder.getClass(),ArrayList.class,brush.getClass());add.setAccessible(true);
        add.invoke(null,builder,XposedHelpers.getObjectField(path,"continue"),brush);
        Object icon=XposedHelpers.callMethod(builder,"break");
        cloudIcons.put(loader,icon);return icon;
    }
    private static void vectorPath(Object path,String command,float... coordinates){
        Object[] values=new Object[coordinates.length];for(int i=0;i<coordinates.length;i++)values[i]=coordinates[i];
        XposedHelpers.callMethod(path,command,values);
    }

    private static Object action(ClassLoader loader,String id,String title,String description,Runnable action) throws Exception {
        return construct(loader,"x.d0",CATEGORY+"."+id,title,description,null,function(loader,"kotlin.jvm.functions.Function1",args->{if(args!=null && args.length>0)CloudHook.panel.messageTarget(args[0]);action.run();return unit(loader);}),8);
    }
    private static Object confirmation(ClassLoader loader,String id,String title,String description,Runnable action) throws Exception {
        return construct(loader,"x.l00",CATEGORY+"."+id,title,description,Collections.emptyList(),title,description,function(loader,"kotlin.jvm.functions.Function1",args->{action.run();return unit(loader);}),0);
    }
    private interface Callback {Object invoke(Object[] args) throws Exception;}
    private static Object function(ClassLoader loader,String name,Callback callback) throws Exception {
        Class<?> type=Class.forName(name,false,loader);
        return Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args)->{
            if("invoke".equals(method.getName()))return callback.invoke(args);
            if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
            if("equals".equals(method.getName()))return proxy==args[0];
            if("toString".equals(method.getName()))return "QDAPI native settings action";
            return null;
        });
    }
    private static Object unit(ClassLoader loader) throws Exception {return Class.forName("kotlin.Unit",false,loader).getField("INSTANCE").get(null);}
    private static Object construct(ClassLoader loader,String name,Object... args) throws Exception {
        for(Constructor<?> constructor:Class.forName(name,false,loader).getDeclaredConstructors()){
            if(constructor.getParameterTypes().length==args.length){constructor.setAccessible(true);return constructor.newInstance(args);}
        }
        throw new NoSuchMethodException(name);
    }
    private static final class Binding {
        private final WeakReference<Activity> activity;
        Binding(Activity activity){this.activity=new WeakReference<>(activity);}
        void sync(){
            Activity screen=activity.get();
            // 代理设置页对象可能先被释放；同步无需页面实例，不能因此静默丢弃用户点击。
            if(screen!=null && (screen.isFinishing() || screen.isDestroyed()))screen=null;
            CloudHook.panel.startSync(screen,new JSONArray());
        }
        void credits(){ProjectCredits.show(activity.get());}
    }
}
