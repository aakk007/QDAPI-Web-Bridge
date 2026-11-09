package com.zxwn.qdapi.cloudbridge;

final class RewardAdPolicy {
    private RewardAdPolicy() {}

    static boolean isRewardActivity(String name) {
        return "com.qq.e.tg.RewardvideoPortraitADActivity".equals(name)
                || "com.qq.e.tg.RewardvideoLandscapeADActivity".equals(name)
                || "com.qq.e.ads.RewardvideoPortraitADActivity".equals(name)
                || "com.qq.e.ads.RewardvideoLandscapeADActivity".equals(name)
                || "com.tencent.klevin.ads.view.RewardAdActivity".equals(name);
    }
}
