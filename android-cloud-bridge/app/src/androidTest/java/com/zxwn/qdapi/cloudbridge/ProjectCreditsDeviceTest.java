package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** 只验证来源对话框，不点击上传、任务开关或外部链接。 */
final class ProjectCreditsDeviceTest {
    static void runLinksOnly(Instrumentation test){
        Bundle result=new Bundle();
        try{
            android.text.SpannableString content=new android.text.SpannableString(ProjectCredits.text());
            android.text.util.Linkify.addLinks(content,android.text.util.Linkify.WEB_URLS);
            String expected="https://www.52pojie.cn/home.php?mod=space&uid=771356";
            boolean found=false;
            for(android.text.style.URLSpan link:content.getSpans(0,content.length(),android.text.style.URLSpan.class))if(expected.equals(link.getURL()))found=true;
            if(!found || content.toString().contains("/?771356"))throw new Exception("完整主页链接被截断或仍有短链接");
            result.putString("stream","Android链接识别通过：完整home.php?mod=space&uid=771356可点击，ampersand参数未截断；未打开网页或触发任务\n");test.finish(-1,result);
        }catch(Exception e){result.putString("stream","作者主页链接验证失败："+e.getMessage()+"\n");test.finish(0,result);}
    }
    static void run(Instrumentation test){
        Bundle result=new Bundle();
        try{
            android.app.KeyguardManager keyguard=(android.app.KeyguardManager)test.getTargetContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
            if(keyguard!=null && keyguard.isKeyguardLocked())throw new Exception("请先解锁手机再运行来源界面测试，不修改锁屏设置");
            Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.runOnMainSync(()->ProjectCredits.show(activity));test.waitForIdleSync();
            String text="";long deadline=SystemClock.elapsedRealtime()+5000;
            while(SystemClock.elapsedRealtime()<deadline){text=collect(test.getUiAutomation().getRootInActiveWindow());if(text.contains("原作者：吾爱破解-pzx521521"))break;SystemClock.sleep(100);}
            for(String expected:new String[]{"来源、作者与许可","吾爱破解-pzx521521","https://www.52pojie.cn/home.php?mod=space&uid=771356","xihan123","https://github.com/Xposed-Modules-Repo/cn.xihan.qdds","https://github.com/aakk007/QDAPI-Web-Bridge","ChatGPT / Codex","Go源码不在公开仓库","MIT License","依法不得免除的责任"}){
                if(!text.contains(expected))throw new Exception("实机对话框缺少声明："+expected);
            }
            android.graphics.Bitmap bitmap=test.getUiAutomation().takeScreenshot();
            if(bitmap==null)throw new Exception("来源界面截图失败");
            java.io.File proof=new java.io.File(test.getTargetContext().getExternalFilesDir(null),"credits-proof.png");
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(proof)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
            finally{bitmap.recycle();}
            test.runOnMainSync(activity::finish);
            result.putString("stream","实机来源/原作者/项目链接/AI辅助/MIT许可范围及责任声明可见；未触发上传或任务动作\n");test.finish(-1,result);
        }catch(Exception e){result.putString("stream","来源展示验收失败："+e.getMessage()+"\n");test.finish(0,result);}
    }
    private static String collect(AccessibilityNodeInfo node){
        if(node==null)return "";StringBuilder text=new StringBuilder();if(node.getText()!=null)text.append(node.getText()).append('\n');
        for(int index=0;index<node.getChildCount();index++)text.append(collect(node.getChild(index)));return text.toString();
    }
}
