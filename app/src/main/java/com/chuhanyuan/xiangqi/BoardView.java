package com.chuhanyuan.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** 木质棋盘：9×10交叉点。只模拟真实棋盘外观，不强制象棋规则。 */
public class BoardView extends FrameLayout {

    public interface GeometryListener { void onGeometryChanged(); }
    private GeometryListener geometryListener;
    private Paint line, fill, river, text;
    private float tile = 0f, originX = 0f, originY = 0f, boardW = 0f, boardH = 0f;

    public BoardView(Context c) { super(c); init(); }
    public BoardView(Context c, AttributeSet a) { super(c, a); init(); }
    public BoardView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
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

    private int color(int id) { return androidx.core.content.ContextCompat.getColor(getContext(), id); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        // 9个点只有8个横向间隔，10个点只有9个纵向间隔。
        // 旧算法把宽度除以9，会把棋盘缩窄，导致视觉上偏移、边缘不好点。
        float safeW = Math.max(1, w - dp(16));
        float safeH = Math.max(1, h - dp(8));
        tile = Math.min(safeW / 8f, safeH / 9f);
        boardW = 8f * tile;
        boardH = 9f * tile;
        originX = (w - boardW) / 2f;
        originY = (h - boardH) / 2f;
        invalidate();
        if (geometryListener != null) geometryListener.onGeometryChanged();
    }

    public void setGeometryListener(GeometryListener l) { geometryListener = l; }
    public float getTile() { return tile; }
    public float getOriginX() { return originX; }
    public float getOriginY() { return originY; }
    public float x(int col) { return originX + col * tile; }
    public float y(int row) { return originY + row * tile; }

    /** 标记只出现在传统炮位与兵卒位，不在九宫上层两个角落重复画。 */
    private void mark(Canvas cv, int col, int row) {
        float px=x(col), py=y(row);
        float g=tile*0.07f, l=tile*0.14f;
        if (col > 0 && !(row == 2 && col == 0) && !(row == 7 && col == 0)) {
            cv.drawLine(px-g,py-g,px-g-l,py-g,line);
            cv.drawLine(px-g,py+g,px-g-l,py+g,line);
        }
        if (col < 8) {
            cv.drawLine(px+g,py-g,px+g+l,py-g,line);
            cv.drawLine(px+g,py+g,px+g+l,py+g,line);
        }
    }

    @Override protected void onDraw(Canvas cv) {
        if (tile <= 0) return;
        float pad=dp(8), pad2=dp(4);
        cv.drawRoundRect(originX-pad, originY-pad, originX+boardW+pad, originY+boardH+pad,
                dp(10),dp(10),fill);
        cv.drawRect(originX, y(4), originX+boardW, y(5), river);

        Paint thick=new Paint(line);
        thick.setStrokeWidth(dp(2.5f));
        cv.drawRect(originX-pad2, originY-pad2, originX+boardW+pad2, originY+boardH+pad2, thick);

        for(int r=0;r<=9;r++) cv.drawLine(x(0),y(r),x(8),y(r),line);
        for(int c=0;c<=8;c++) {
            if(c==0 || c==8) cv.drawLine(x(c),y(0),x(c),y(9),line);
            else {
                cv.drawLine(x(c),y(0),x(c),y(4),line);
                cv.drawLine(x(c),y(5),x(c),y(9),line);
            }
        }

        cv.drawLine(x(3),y(0),x(5),y(2),line);
        cv.drawLine(x(5),y(0),x(3),y(2),line);
        cv.drawLine(x(3),y(7),x(5),y(9),line);
        cv.drawLine(x(5),y(7),x(3),y(9),line);

        // 标准位置：炮在(2,1)(2,7)(7,1)(7,7)，兵卒在(3/6,偶数列)。
        mark(cv,1,2); mark(cv,7,2); mark(cv,1,7); mark(cv,7,7);
        for(int c=0;c<=8;c+=2){ mark(cv,c,3); mark(cv,c,6); }

        int alpha=text.getAlpha();
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(tile*0.52f);
        text.setAlpha(170);
        float midY=(y(4)+y(5))/2f+text.getTextSize()*0.35f;
        cv.drawText("楚 河", originX+boardW*0.25f, midY, text);
        cv.drawText("汉 界", originX+boardW*0.75f, midY, text);

        text.setTextSize(tile*0.4f);
        text.setAlpha(140);
        float topY=y(0)+tile*0.78f, botY=y(9)-tile*0.78f;
        cv.drawText("观棋不语", originX+boardW*0.28f, topY, text);
        cv.drawText("真君子", originX+boardW*0.72f, topY, text);
        cv.drawText("摸子动子", originX+boardW*0.28f, botY, text);
        cv.drawText("落子无悔", originX+boardW*0.72f, botY, text);
        text.setAlpha(alpha);
    }
}
