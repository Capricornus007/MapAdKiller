package cn.eni.mapadkiller;

import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * ViewKiller — 通用视图层兜底：在 Activity onResume 后遍历 DecorView，
 * 按「类名正则」或「资源名正则」命中即 GONE。
 * 语义 hook 漏掉的广告容器，这里补刀。
 */
public final class ViewKiller {

    private final Pattern classPat;
    private final Pattern resPat;
    private final String tag;

    public ViewKiller(String tag, String classRegex, String resRegex) {
        this.tag = tag;
        this.classPat = classRegex == null ? null : Pattern.compile(classRegex);
        this.resPat = resRegex == null ? null : Pattern.compile(resRegex);
    }

    public void sweep(Activity act) {
        if (act == null) return;
        try {
            View decor = act.getWindow().getDecorView();
            sweep(decor, act.getResources());
        } catch (Throwable t) {
            MainHook.log(tag + " sweep err " + t);
        }
    }

    private void sweep(View v, Resources res) {
        boolean gone = false;
        try {
            if (classPat != null && classPat.matcher(v.getClass().getName()).find()) gone = true;
            if (!gone && resPat != null && v.getId() != View.NO_ID && v.getId() != 0) {
                String name = res.getResourceName(v.getId()); // e.g. com.tencent.map:id/view_stub_home_banner_view
                if (name != null && resPat.matcher(name).find()) gone = true;
            }
        } catch (Throwable ignored) {
            // 非法 id / 资源不存在时跳过
        }
        if (gone && v.getVisibility() != View.GONE) {
            v.setVisibility(View.GONE);
            if (v instanceof ViewGroup) {
                ((ViewGroup) v).removeAllViews();
            }
            MainHook.log(tag + " KILLED view: " + v.getClass().getName() + " id=" + safeName(v, res));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sweep(g.getChildAt(i), res);
            }
        }
    }

    private static String safeName(View v, Resources res) {
        try {
            return v.getId() > 0 ? res.getResourceName(v.getId()) : "-";
        } catch (Throwable t) {
            return "?";
        }
    }
}
