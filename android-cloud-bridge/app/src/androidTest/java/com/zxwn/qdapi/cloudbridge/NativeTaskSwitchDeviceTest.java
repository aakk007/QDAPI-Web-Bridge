package com.zxwn.qdapi.cloudbridge;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

// 对已打开的真实原生任务页做一次语义点击，不打开页面、不读取账号凭据。
@android.annotation.TargetApi(30)
public final class NativeTaskSwitchDeviceTest {
    static void run(Instrumentation test,Bundle args){
        String label=args.getString("nativeLabel","");
        Boolean expected=args.containsKey("expected")?Boolean.valueOf(args.getString("expected")):null;
        boolean action=Boolean.parseBoolean(args.getString("nativeAction","false"));
        Bundle result=new Bundle();
        try{
            if(label.isEmpty())throw new Exception("必须指定任务开关名称");
            if(android.os.Build.VERSION.SDK_INT<30)throw new Exception("此项原生界面测试需要Android 11及以上");
            UiAutomation ui=test.getUiAutomation();
            AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(info);
            if(Boolean.parseBoolean(args.getString("nativeScroll","false"))){
                AccessibilityNodeInfo scroll=scrollable(ui.getRootInActiveWindow());
                if(scroll==null || !scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))throw new Exception("当前页面不可向下滚动");
                result.putString("stream","原生页面已向下滚动\n");test.finish(-1,result);return;
            }
            AccessibilityNodeInfo target=null;long ready=SystemClock.elapsedRealtime()+8000;
            while(target==null && SystemClock.elapsedRealtime()<ready){target=findSwitch(ui,label,action);if(target==null)SystemClock.sleep(150);}
            if(target==null)throw new Exception("当前页面未显示指定原生开关");
            target.refresh();
            if(action){if(!target.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Exception("原生按钮拒绝语义点击");result.putString("stream",label+" 原生按钮已点击\n");test.finish(-1,result);return;}
            boolean before=target.isChecked();
            if(expected!=null && before!=expected && !target.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Exception("原生开关拒绝语义点击");
            boolean after=before;long deadline=SystemClock.elapsedRealtime()+10000;
            do{SystemClock.sleep(100);target=findSwitch(ui,label,false);if(target!=null){target.refresh();after=target.isChecked();}}while(expected!=null && after!=expected && SystemClock.elapsedRealtime()<deadline);
            if(expected!=null && after!=expected)throw new Exception("点击后开关未改变");
            result.putString("stream",label+" 原生状态 "+before+" → "+after+"\n");test.finish(-1,result);
        }catch(Exception e){result.putString("stream","原生开关验收失败："+e.getMessage()+"\n");test.finish(0,result);}
    }
    private static AccessibilityNodeInfo findSwitch(UiAutomation ui,String label,boolean action){
        AccessibilityNodeInfo active=ui.getRootInActiveWindow();
        AccessibilityNodeInfo current=findRootTarget(active,label,action);if(current!=null)return current;
        SparseArray<List<AccessibilityWindowInfo>> displays=ui.getWindowsOnAllDisplays();
        for(int i=0;i<displays.size();i++)for(AccessibilityWindowInfo window:displays.valueAt(i)){
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            AccessibilityNodeInfo found=findRootTarget(root,label,action);if(found!=null)return found;
        }
        return null;
    }
    private static AccessibilityNodeInfo findRootTarget(AccessibilityNodeInfo root,String label,boolean action){
            if(root==null)return null;
            java.util.ArrayList<AccessibilityNodeInfo> matches=new java.util.ArrayList<>();findText(root,label,matches);
            for(AccessibilityNodeInfo text:matches){
                AccessibilityNodeInfo parent=text;
                for(int level=0;parent!=null && level<4;level++,parent=parent.getParent()){
                    if(action && parent.isClickable() && parent.isEnabled())return parent;
                    AccessibilityNodeInfo found=onlySwitch(parent);if(found!=null)return found;
                }
            }
        return null;
    }
    private static void findText(AccessibilityNodeInfo node,String text,List<AccessibilityNodeInfo> matches){
        if((node.getText()!=null && text.contentEquals(node.getText())) || text.equals(node.getViewIdResourceName()))matches.add(node);
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)findText(child,text,matches);}
    }
    private static AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node){
        if(node==null)return null;if(node.isScrollable() && node.isEnabled())return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=scrollable(node.getChild(i));if(found!=null)return found;}return null;
    }
    private static AccessibilityNodeInfo onlySwitch(AccessibilityNodeInfo node){
        if(node.isCheckable() && node.isEnabled())return node;
        AccessibilityNodeInfo result=null;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            AccessibilityNodeInfo found=onlySwitch(child);if(found!=null){if(result!=null)return null;result=found;}
        }
        return result;
    }
}
