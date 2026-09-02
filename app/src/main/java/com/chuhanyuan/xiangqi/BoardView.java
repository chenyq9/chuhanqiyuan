package com.chuhanyuan.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** 木质棋盘：9×10线、楚河汉界、九宫斜线、炮兵位标记。不含任何规则。 */
public class BoardView extends FrameLayout {

    private Paint line, fill, river, text;
    private float tile = 0f, originX = 0f, originY = 0f, boardW = 0f, boardH = 0f;

    public BoardView(Context c) { super(c); init(); }
    public BoardView(Context c, AttributeSet a) { super(c, a); init(); }
    public BoardView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false); // 容器类默认不调onDraw，必须显式打开

        line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(color(R.color.board_line));
        line.setStrokeWidth(dp(1.4f));
        line.setStyle(Paint.Style.STROKE);

        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color(R.color.board_wood));
        fill.setStyle(Paint.Style.FILL);

        river = new Paint(Paint.ANTI_ALIAS_FLAG);
        river.setColor(color(R.color.river_bg));
        river.setStyle(Paint.Style.FILL);

        text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(color(R.color.board_line));
        text.setStyle(Paint.Style.FILL);
    }

    private int color(int id) {
        return androidx.core.content.ContextCompat.getColor(getContext(), id);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        tile = Math.min(w / 9.0f, h / 10.0f);
        boardW = 9 * tile;
        boardH = 10 * tile;
        originX = (w - boardW) / 2f;
        originY = (h - boardH) / 2f;
        invalidate();
    }

    public float getTile() { return tile; }
    public float getOriginX() { return originX; }
    public float getOriginY() { return originY; }
    public float x(int col) { return originX + col * tile; }
    public float y(int row) { return originY + row * tile; }

    private void mark(Canvas cv, int col, int row) {
        float px = x(col), py = y(row);
        float g = tile * 0.07f, l = tile * 0.14f;
        boolean left = col > 0, right = col < 8;
        if (left) {
            cv.drawLine(px - g, py - g, px - g - l, py - g, line);
            cv.drawLine(px - g, py + g, px - g - l, py + g, line);
        }
        if (right) {
            cv.drawLine(px + g, py - g, px + g + l, py - g, line);
            cv.drawLine(px + g, py + g, px + g + l, py + g, line);
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (tile <= 0) return;
        float pad = dp(8);
        float pad2 = dp(4);
        // 底板
        cv.drawRoundRect(-pad, originY - pad, boardW + pad, originY + boardH + pad,
                dp(10), dp(10), fill);
        // 楚河汉界底色
        cv.drawRect(0, y(4), boardW, y(5), river);
        // 外框
        Paint thick = new Paint(line);
        thick.setStrokeWidth(dp(2.5f));
        cv.drawRect(-pad2, originY - pad2, boardW + pad2, originY + boardH + pad2, thick);
        // 横线
        for (int r = 0; r <= 9; r++) cv.drawLine(x(0), y(r), x(8), y(r), line);
        // 竖线（中间各列在楚河处断开）
        for (int c = 0; c <= 8; c++) {
            if (c == 0 || c == 8) {
                cv.drawLine(x(c), y(0), x(c), y(9), line);
            } else {
                cv.drawLine(x(c), y(0), x(c), y(4), line);
                cv.drawLine(x(c), y(5), x(c), y(9), line);
            }
        }
        // 九宫斜线
        cv.drawLine(x(3), y(0), x(5), y(2), line);
        cv.drawLine(x(5), y(0), x(3), y(2), line);
        cv.drawLine(x(3), y(7), x(5), y(9), line);
        cv.drawLine(x(5), y(7), x(3), y(9), line);
        // 炮位、兵位标记
        int[] spotRows = {2, 3, 6, 7};
        for (int r : spotRows) {
            if (r == 2 || r == 7) {
                mark(cv, 1, r); mark(cv, 3, r); mark(cv, 5, r); mark(cv, 7, r);
            } else {
                for (int c = 0; c <= 8; c += 2) mark(cv, c, r);
            }
        }
        // 楚河 / 汉界
        text.setTextSize(tile * 0.52f);
        text.setTextAlign(Paint.Align.CENTER);
        int alpha = text.getAlpha();
        text.setAlpha(170);
        float midY = (y(4) + y(5)) / 2f + text.getTextSize() * 0.35f;
        cv.drawText("楚 河", boardW * 0.25f, midY, text);
        cv.drawText("汉 界", boardW * 0.75f, midY, text);
        // 桌面小字（棋子会自然盖在上面）
        text.setTextSize(tile * 0.4f);
        text.setAlpha(140);
        float topY = y(0) + tile * 0.78f;
        float botY = y(9) - tile * 0.78f;
        cv.drawText("观棋不语", boardW * 0.28f, topY, text);
        cv.drawText("真君子", boardW * 0.72f, topY, text);
        cv.drawText("摸子动子", boardW * 0.28f, botY, text);
        cv.drawText("落子无悔", boardW * 0.72f, botY, text);
        text.setAlpha(alpha);
    }
}