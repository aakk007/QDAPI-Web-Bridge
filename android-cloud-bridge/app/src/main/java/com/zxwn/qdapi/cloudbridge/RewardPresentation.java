package com.zxwn.qdapi.cloudbridge;

import android.view.Window;
import android.view.WindowManager;

// 仅由已关联广告会话持有；失败时恢复原窗口属性，不改变系统音量。
final class RewardPresentation {
    private final Window window;
    private final float alpha, dim;
    private final int animations, dimFlag;

    RewardPresentation(Window window) {
        this.window = window;
        WindowManager.LayoutParams attrs = window.getAttributes();
        alpha = attrs.alpha;
        dim = attrs.dimAmount;
        animations = attrs.windowAnimations;
        dimFlag = attrs.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND;
    }

    void hide() {
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = 0f;
        attrs.dimAmount = 0f;
        attrs.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        attrs.windowAnimations = 0;
        window.setAttributes(attrs);
    }

    void restore() {
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = alpha;
        attrs.dimAmount = dim;
        attrs.flags = (attrs.flags & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND) | dimFlag;
        attrs.windowAnimations = animations;
        window.setAttributes(attrs);
    }
}
