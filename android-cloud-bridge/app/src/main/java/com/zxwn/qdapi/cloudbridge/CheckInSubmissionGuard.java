package com.zxwn.qdapi.cloudbridge;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

// 请求发出前持久预留；崩溃或超时后同账号/同签到时间只回读，不再提交。
final class CheckInSubmissionGuard {
    static synchronized void check(Context context, String account, String stamp) throws Exception {
        if (context.getSharedPreferences("qdapi_checkin_submissions", Context.MODE_PRIVATE).contains(key(account, stamp)))
            throw new Exception("此签到奖励已提交或结果未知，不再次启动广告");
    }

    private static String key(String account, String stamp) throws Exception {
        if (account == null || account.isEmpty() || stamp == null || !stamp.matches("[0-9]{13}"))
            throw new Exception("签到提交缺少账号或时间");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(account.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hash + ":" + stamp;
    }

    static synchronized void reserve(Context context, String account, JSONObject job) throws Exception {
        if (!"POST".equals(job.optString("method"))
                || !"/argus/api/v2/checkin/adreport".equals(new java.net.URL(job.getString("url")).getPath())) return;
        String stamp = null;
        for (String pair : job.optString("body").split("&")) {
            String[] parts = pair.split("=", 2);
            if ("checkInDayTime".equals(URLDecoder.decode(parts[0], "UTF-8"))) {
                if (stamp != null || parts.length != 2) throw new Exception("签到时间参数无效");
                stamp = URLDecoder.decode(parts[1], "UTF-8");
            }
        }
        if (account == null || account.isEmpty() || stamp == null || !stamp.matches("[0-9]{13}"))
            throw new Exception("签到提交缺少账号或时间");
        long time = Long.parseLong(stamp), now = System.currentTimeMillis();
        if (time > now || now - time > 86400000L) throw new Exception("签到提交时间已失效");
        SharedPreferences prefs = context.getSharedPreferences("qdapi_checkin_submissions", Context.MODE_PRIVATE);
        String key = key(account, stamp);
        if (prefs.contains(key)) throw new Exception("此签到奖励已提交或结果未知，仅回读状态，不重复提交");
        SharedPreferences.Editor edit = prefs.edit();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Long && now - (Long) value > 7L * 86400000) edit.remove(entry.getKey());
        }
        if (!edit.putLong(key, now).commit()) throw new Exception("签到防重复记录未持久保存，未发出领取请求");
    }
}
