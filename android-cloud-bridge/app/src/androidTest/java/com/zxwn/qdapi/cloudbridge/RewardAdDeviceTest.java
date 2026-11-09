package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

// 检查持久防重、接口白名单及真实窗口隐藏/恢复；不产生官方请求。
final class RewardAdDeviceTest {
    static void run(Instrumentation instrumentation) {
        Bundle result = new Bundle();
        try {
            for (String name : new String[]{"com.qq.e.tg.ADActivity", "com.qq.e.ads.PortraitADActivity",
                    "other.vendor.RewardAdActivity", "other.vendor.RewardvideoActivity", null}) {
                require(!RewardAdPolicy.isRewardActivity(name), "误关闭普通或未适配广告");
            }
            require(RewardAdPolicy.isRewardActivity("com.tencent.klevin.ads.view.RewardAdActivity"), "漏识别已适配激励视频");
            require(RewardAdPolicy.isRewardActivity("com.qq.e.tg.RewardvideoPortraitADActivity"), "漏识别已适配激励视频");
            String stamp=String.valueOf(System.currentTimeMillis()-1000);
            String account="synthetic-guard-"+System.nanoTime();
            org.json.JSONObject job=new org.json.JSONObject().put("url","https://h5.if.qidian.com/argus/api/v2/checkin/adreport")
                    .put("method","POST").put("body","checkInDayTime="+stamp);
            CheckInSubmissionGuard.check(instrumentation.getTargetContext(),account,stamp);
            CheckInSubmissionGuard.reserve(instrumentation.getTargetContext(),account,job);
            boolean refused=false;
            try {CheckInSubmissionGuard.reserve(instrumentation.getTargetContext(),account,job);}
            catch(Exception expected){refused=true;}
            require(refused,"同账号/签到时间重复提交未拒绝");
            refused=false;
            try {CheckInSubmissionGuard.check(instrumentation.getTargetContext(),account,stamp);}
            catch(Exception expected){refused=true;}
            require(refused,"结果未知后重复启动广告未拒绝");
            CheckInSubmissionGuard.check(instrumentation.getTargetContext(),account+"-other",stamp);
            require(instrumentation.getTargetContext().getSharedPreferences("qdapi_checkin_submissions",0)
                    .getAll().keySet().stream().noneMatch(k->k.contains(account)),"防重日志包含账号原文");
            LocalBenefitsRunner.validate(job);
            LocalBenefitsRunner.validate(new org.json.JSONObject().put("url","https://h5.if.qidian.com/argus/api/v2/checkin/getweekcheckininfo").put("method","GET"));
            refused=false;
            try {LocalBenefitsRunner.validate(new org.json.JSONObject().put("url",job.getString("url")).put("method","GET"));}
            catch(Exception expected){refused=true;}
            require(refused,"广告报告放行了错误方法");
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            instrumentation.runOnMainSync(()->{
                try {
                    android.app.Dialog dialog=new android.app.Dialog(instrumentation.getTargetContext());
                    android.view.Window window=dialog.getWindow();
                    android.view.WindowManager.LayoutParams attrs=window.getAttributes();
                    attrs.alpha=.8f;attrs.dimAmount=.4f;attrs.flags|=android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                    window.setAttributes(attrs);
                    RewardPresentation surface=new RewardPresentation(window);surface.hide();
                    require(window.getAttributes().alpha==0f,"启动隐藏未生效");
                    require((window.getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)==0,"隐藏后仍遮暗宿主");
                    surface.hide();surface.restore();
                    require(window.getAttributes().alpha==.8f && window.getAttributes().dimAmount==.4f,"失败恢复丢失原窗口属性");
                } catch(Throwable error){failure.set(error);}
            });
            if(failure.get()!=null)throw failure.get();
            result.putString("stream", "SDK activity boundaries, persistent submission guard/account isolation, native API allowlist, early window hide and restoration passed; real official requests=0\n");
            instrumentation.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "RewardAdPolicy failed: " + error + "\n");
            instrumentation.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
