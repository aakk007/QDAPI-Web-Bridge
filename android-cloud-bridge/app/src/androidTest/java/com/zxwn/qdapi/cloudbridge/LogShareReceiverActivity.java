package com.zxwn.qdapi.cloudbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

// 仅存在于测试APK：用独立UID读取真实分享URI，不向任何外部服务发送日志。
public final class LogShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView view=new TextView(this);setContentView(view);
        try{
            Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("未收到文件URI");
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            try(InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new IOException("无法打开分享文件");byte[] b=new byte[8192];int n;
                while((n=input.read(b))!=-1){if(data.size()+n>24*1024*1024)throw new IOException("分享文件超出验收上限");data.write(b,0,n);}
            }
            byte[] bytes=data.toByteArray();String text=new String(bytes,StandardCharsets.UTF_8);
            String[] names={"每小时宝箱","每日任务","视频任务","游戏任务","每日签到","签到碎片兑换","免费抽奖","阅读积分领取","每日阅读积分资格","每周阅读800分钟资格","每周阅读1200分钟资格","作品更新提醒积分资格","阅读PK积分资格"};
            JSONObject result=new JSONObject().put("bytes",bytes.length).put("contentUriReadable",true);
            for(String name:names){boolean found=text.contains("\"name\":\""+name+"\"");result.put(name,found);if(!found)throw new IOException("缺少任务明细："+name);}
            StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));result.put("sha256",hash.toString());
            try(FileOutputStream output=openFileOutput("share-receipt.json",MODE_PRIVATE)){output.write(result.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            view.setText("真实分享验收通过\n独立UID已读取"+bytes.length+"字节\n七项及阅读积分明细齐全");
        }catch(Exception error){view.setText("真实分享验收失败："+error.getClass().getSimpleName()+" "+error.getMessage());}
    }
}
