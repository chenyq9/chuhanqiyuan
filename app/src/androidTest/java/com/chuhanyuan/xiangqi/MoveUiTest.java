package com.chuhanyuan.xiangqi;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 模拟器UI测试：点帥选中 → 点空格 → 断言帥真的移动了。 */
public class MoveUiTest {

    private UiDevice device;

    @Before
    public void setup() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // 预填配置，让启动页直接进棋盘（不触发真AI请求：base是假地址）
        ctx.getSharedPreferences("chuhan", Context.MODE_PRIVATE).edit()
                .putString("base", "https://api.invalid/v1")
                .putString("key", "test-key")
                .putString("model", "test-model")
                .commit();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        device.wait(Until.hasObject(By.text("帥")), 15000);
    }

    @Test
    public void tapPieceThenEmptyCellMovesIt() throws Exception {
        UiObject2 shuai = device.wait(Until.findObject(By.text("帥")), 8000);
        assertNotNull("红帥没找到", shuai);
        Rect r = shuai.getVisibleBounds();
        int cx = r.centerX();
        int cy = r.centerY();
        int h = r.height();

        device.click(cx, cy);            // 点棋子=选中
        Thread.sleep(500);
        device.click(cx, cy + h * 222 / 100); // 点下方两格的空位（初始必空）
        Thread.sleep(1200);              // 等走子动画结束

        UiObject2 after = device.wait(Until.findObject(By.text("帥")), 8000);
        assertNotNull("点完空格后帥消失了", after);
        int dy = after.getVisibleBounds().centerY() - cy;
        assertTrue("帥没有移动到目标格，dy=" + dy, Math.abs(dy) > h * 3 / 2);
    }
}
