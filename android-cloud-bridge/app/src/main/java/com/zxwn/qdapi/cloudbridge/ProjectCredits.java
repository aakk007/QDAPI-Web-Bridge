package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.widget.ScrollView;
import android.widget.TextView;

/** 本集成层的来源说明，不替换原模块的关于页面或宣称拥有上游版权。 */
final class ProjectCredits {
    static String text(){return
        "QDAPI Web + APP 集成\n\n"+
        "Go / Web任务基础\n原作者：吾爱破解-pzx521521\n作者主页：https://www.52pojie.cn/?771356\n原项目地址：https://github.com/pzx521521/qdapi\n原地址目前可能不可访问。底层基于原作者代码实现；Go源码不在公开仓库，原作者权利保留。维护者声明已取得私下授权，授权材料不公开，不因此向第三方扩张授权。\n\n"+
        "APP原项目：QDReadHook\n原作者：xihan123\n项目与官方下载：https://github.com/Xposed-Modules-Repo/cn.xihan.qdds\n原项目说明声明GPL-3.0；具体版本许可及使用说明以原项目为准。本集成是独立桥接模块，不包含、不替换原QDReadHook APK，不代表原作者背书。\n\n"+
        "本集成维护者：aakk007\n公开仓库：https://github.com/aakk007/QDAPI-Web-Bridge\n本项目不是两个原项目的原创作者。集成与修改由维护者借助ChatGPT / Codex完成；AI辅助不改变原作者版权，不代表OpenAI或起点官方参与、审核或担保。\n\n"+
        "许可边界\n公开的桥接Java源码与本项目新增文档/脚本采用MIT许可，完整许可见仓库LICENSE。该许可不覆盖Go核心、Go源码、发布包中的核心二进制、QDReadHook、起点APP、商标及第三方依赖。公开仓库不等于所有组成部分均开源或可任意再分发。\n\n"+
        "账号与云端\n仅用于本人或已获授权账号。七项及额外积分在本机可执行时优先本机；只有手动确认上传后才启用云端兜底。首次启动、刷新、自动任务不上传登录凭据。云端凭据、设备令牌和日志存在泄露及滥用风险；实际部署者负责告知、访问控制、数据保留与删除。\n\n"+
        "使用与责任\n非官方辅助项目，与起点、阅文、原项目作者及OpenAI无隶属或担保关系。自动化可能引发风控、验证码、登录失效、账号限制、设备异常或数据损失；不承诺奖励、收益、持续可用或绝对安全。请遵守适用法律、平台协议和第三方许可。免责声明不能替代授权，也不免除依法不得免除的责任。详细说明见仓库DISCLAIMER.md及PRIVACY.md。\n\n"+
        "MIT License（仅适用上述公开新增部分）\nCopyright (c) 2026 aakk007 and contributors\n\n"+
        "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\n"+
        "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.\n\n"+
        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.";
    }
    static void show(Activity activity){
        if(activity==null || activity.isFinishing() || activity.isDestroyed())return;
        TextView content=new TextView(activity);content.setText(text());content.setTextSize(15);
        int padding=(int)(20*activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding,padding,padding,padding);content.setTextIsSelectable(true);
        Linkify.addLinks(content,Linkify.WEB_URLS);content.setMovementMethod(LinkMovementMethod.getInstance());
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("来源、作者与许可").setView(scroll).setPositiveButton("关闭",null).show();
    }
}
