package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.app.AlertDialog;

final class UploadConsent {
    static final String TITLE="账号上传与云端托管说明";
    static final String TEXT=
        "1. 本功能为非官方辅助工具，仅可用于本人或已获明确授权的账号，与起点官方无隶属关系。\n\n"+
        "2. 点击“同意并上传”后，将向当前QDAPI云端服务上传执行任务所需的登录凭据、账号标识及设备请求信息（包括QDInfo、SDKSign、ywkey、ywguid、ibex等）。这些信息可用于代表该账号执行所选任务，请勿上传他人账号。\n\n"+
        "3. APP七项在本机执行，无需先上传账号。手动确认上传后，仅在APP关闭或无法继续执行时，由云端接管所选任务；APP前台或后台仍能执行时，云端让路，避免两端重复执行。运行中会回传必要的接口结果与脱敏日志用于核验。兑换可能消耗签到碎片，抽奖消耗已有免费次数，不自动充值或购买。\n\n"+
        "4. 登录凭据仅在手动点击并确认时上传。首次启动、进入页面、刷新状态、自动任务及断线重连均不自动上传或更新登录凭据；需要更新时必须再次手动确认。\n\n"+
        "5. 服务端加密保存登录凭据，账号登录失效后清空活动凭据并保留微信绑定及必要身份记录，便于重新上传恢复。加密不能保证绝对安全，仍存在数据泄露、滥用和服务故障风险。\n\n"+
        "6. 自动化可能遇到平台风控、验证码、账号限制或登录失效。任务资格、奖励和到账以官方结果为准，不承诺持续可用、固定收益或无风险。\n\n"+
        "7. 可在管理台暂停云端任务；解除APP设备连接不等于停止云端托管。如需解除微信绑定或删除服务端数据，应使用管理台对应功能或联系服务维护者处理。本说明不免除依法应承担的责任。";
    static void show(Activity screen,Runnable accepted,Runnable dismissed){
        if(screen==null || screen.isFinishing() || screen.isDestroyed())throw new IllegalStateException("设置页面已关闭");
        new AlertDialog.Builder(screen).setTitle(TITLE).setMessage(TEXT)
            .setNegativeButton("取消",(dialog,which)->dismissed.run())
            .setPositiveButton("我已阅读并同意上传",(dialog,which)->accepted.run())
            .setOnCancelListener(dialog->dismissed.run()).show();
    }
    private UploadConsent(){}
}
