package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.InputStream;
import java.util.*;

// 适配原“日志与缓存→分享日志”静态入口。原文件只读，新建合并文件，不改原保存开关。
final class TaskLogExport {
    private static final Set<ClassLoader> installed=Collections.newSetFromMap(new WeakHashMap<>());
    static synchronized void install(ClassLoader loader) throws Exception {
        if(installed.contains(loader))return;
        Class<?> factory=Class.forName("x.sa3",false,loader),message=Class.forName("x.e0",false,loader);
        XposedHelpers.findAndHookMethod(factory,"catch",message,File.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam hook){
                try {
                    Activity activity=(Activity)XposedHelpers.getObjectField(hook.args[0],"assert");
                    File original=(File)hook.args[1];
                    share(activity,original,loader);
                }catch(Throwable error){
                    android.util.Log.e("QDAPI-Tasks","任务日志导出失败："+error.getClass().getSimpleName()+" "+TaskRuntimeLog.safe(error.getMessage()));
                    if(CloudHook.panel!=null)CloudHook.panel.exportFailure();
                }
                XposedHelpers.callMethod(hook,"setResult",(Object)null); // 编译桩缺此方法，运行时调用标准Xposed API。
            }
        });
        installed.add(loader);
    }
    static void share(Activity activity,File original,ClassLoader loader) throws Exception {
        File parent=original.getCanonicalFile().getParentFile();if(parent==null)throw new Exception("原日志目录无效");
        boolean allowed=false;
        for(File dir:new File[]{activity.getFilesDir(),activity.getCacheDir(),activity.getExternalFilesDir(null),activity.getExternalCacheDir()})if(dir!=null){String base=dir.getCanonicalPath();String target=parent.getCanonicalPath();allowed|=target.equals(base)||target.startsWith(base+File.separator);}
        if(!allowed)throw new Exception("日志目录不在宿主应用范围内");
        if(!parent.isDirectory()&&!parent.mkdirs())throw new Exception("无法创建导出目录");
        // 当前宿主Provider仅开放files-path等目录，不支持旧模块硬编码的/internal URI。
        // 使用宿主已声明的私有files-path，不扩大Provider可访问范围。
        File exports=new File(activity.getFilesDir(),"qdapi-task-exports");
        if(!exports.isDirectory()&&!exports.mkdirs())throw new Exception("无法创建分享目录");
        File output=new File(exports,"qdapi-tasks-"+System.currentTimeMillis()+".txt");
        if(CloudHook.panel==null)throw new Exception("任务日志服务未初始化");
        CloudHook.panel.exportSavedLogs(output,original);
        String authority=activity.getPackageName()+".fileprovider";
        android.content.pm.ProviderInfo provider=activity.getPackageManager().resolveContentProvider(authority,android.content.pm.PackageManager.GET_META_DATA);
        if(provider==null||!provider.grantUriPermissions||!activity.getPackageName().equals(provider.packageName))throw new Exception("宿主文件分享Provider不可用");
        // 从实际Provider元数据读取根名，不沿用旧模块字符串，也不假定固定混淆后的类名。
        android.content.res.XmlResourceParser paths=provider.loadXmlMetaData(activity.getPackageManager(),"android.support.FILE_PROVIDER_PATHS");
        String root=null;
        if(paths!=null)try{int kind;while((kind=paths.next())!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(kind==org.xmlpull.v1.XmlPullParser.START_TAG&&"files-path".equals(paths.getName())){
            String value=paths.getAttributeValue(null,"path");if(".".equals(value)||"".equals(value))root=paths.getAttributeValue(null,"name");
        }}finally{paths.close();}
        if(root==null||root.isEmpty())throw new Exception("宿主未声明可用的私有文件分享目录");
        Uri uri=new Uri.Builder().scheme("content").authority(authority).appendPath(root).appendPath("qdapi-task-exports").appendPath(output.getName()).build();
        if(!"content".equals(uri.getScheme())||!uri.getAuthority().contains(activity.getPackageName()))throw new Exception("原日志分享地址无效");
        // 在打开分享面板前回读Provider实际提供的文件，不能只检测File.length。
        long count=0;try(InputStream input=activity.getContentResolver().openInputStream(uri)){if(input==null)throw new Exception("导出文件不可读取");byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)count+=n;}
        if(count<=0||count!=output.length())throw new Exception("导出文件为空或内容不完整");
        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri("QDAPI任务日志",uri));
        activity.startActivity(Intent.createChooser(send,"分享完整任务日志"));
        Toast.makeText(activity,"任务日志已完整导出："+count+"字节",Toast.LENGTH_LONG).show();
    }
}
