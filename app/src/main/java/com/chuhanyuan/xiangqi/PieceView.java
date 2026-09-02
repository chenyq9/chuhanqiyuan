package com.chuhanyuan.xiangqi;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

/** 一枚可拖动的棋子。规则无关：拖到哪格落哪格，拖回原格=悔棋。定位用边距+平移，抗布局重置。 */
public class PieceView extends AppCompatTextView {

    public interface OnPieceMoved {
        void onPieceDropped(PieceView pv, int fromRow, int fromCol, int toRow, int toCol);
        void onPieceTapped(PieceView pv);
    }

    public final boolean isRed;
    public final char kind;
    private final BoardView board;
    private final GameLogic game;
    private final OnPieceMoved listener;

    private int row, col;
    private int homeRow, homeCol; // 初始位（重开局飞回用）
    private int downRow, downCol;
    private float startX, startY, lastRX, lastRY;
    private boolean dragging, moved, selected;
    private int colStroke, colGold;

    public PieceView(Context c, BoardView board, GameLogic game, char kind, boolean isRed, OnPieceMoved listener) {
        super(c);
        this.board = board;
        this.game = game;
        this.kind = kind;
        this.isRed = isRed;
        this.listener = listener;
        setText(String.valueOf(kind));
        setGravity(android.view.Gravity.CENTER);
        setTypeface(Typeface.DEFAULT_BOLD);
        colStroke = ContextCompat.getColor(c, isRed ? R.color.red_piece : R.color.black_piece);
        colGold = ContextCompat.getColor(c, R.color.gold_accent);
        setTextColor(colStroke);
        updateBg();
        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return touch(e);
            }
        });
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void updateBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ContextCompat.getColor(getContext(), R.color.piece_face));
        g.setStroke(dp(dragging || selected ? 3 : 2), dragging || selected ? colGold : colStroke);
        setBackground(g);
        setElevation(dp(dragging ? 10 : selected ? 6 : 4));
    }

    public void setSize(float tile) {
        int sz = (int) (tile * 0.9f);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tile * 0.48f);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(sz, sz);
        } else {
            lp.width = sz;
            lp.height = sz;
        }
        setLayoutParams(lp);
        updateBg();
    }

    private float targetX(int c) { return board.x(c) - getLayoutParams().width / 2f; }
    private float targetY(int r) { return board.y(r) - getLayoutParams().height / 2f; }

    /** 用边距把棋子钉在格子中心（布局过后也稳）。 */
    public void place(int r, int c) {
        row = r;
        col = c;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.leftMargin = Math.round(targetX(c));
        lp.topMargin = Math.round(targetY(r));
        setLayoutParams(lp);
        setTranslationX(0);
        setTranslationY(0);
    }

    public int getRow() { return row; }
    public int getCol() { return col; }

    public void setCell(int r, int c) {
        row = r;
        col = c;
    }

    public void setSelected(boolean s) {
        selected = s;
        updateBg();
    }

    public void setHome(int r, int c) {
        homeRow = r;
        homeCol = c;
    }

    public int getHomeRow() { return homeRow; }
    public int getHomeCol() { return homeCol; }

    /** 从战利品区飞回某格并恢复大小（重开局/取回）。 */
    public void flyHome(int r, int c) {
        setEnabled(true);
        setVisibility(View.VISIBLE);
        animate().cancel();
        animate().x(targetX(c)).y(targetY(r)).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(400).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        place(r, c);
                    }
                });
    }

    private boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                moved = false;
                downRow = row;
                downCol = col;
                startX = getX();
                startY = getY();
                lastRX = e.getRawX();
                lastRY = e.getRawY();
                animate().cancel();
                updateBg();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float ddx = e.getRawX() - lastRX;
                float ddy = e.getRawY() - lastRY;
                lastRX = e.getRawX();
                lastRY = e.getRawY();
                setX(getX() + ddx);
                setY(getY() + ddy);
                if (Math.abs(getX() - startX) > board.getTile() * 0.12f
                        || Math.abs(getY() - startY) > board.getTile() * 0.12f) {
                    moved = true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                updateBg();
                if (moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    float cx = getX() + getLayoutParams().width / 2f;
                    float cy = getY() + getLayoutParams().height / 2f;
                    int tc = clamp(Math.round((cx - board.getOriginX()) / board.getTile()), 0, 8);
                    int tr = clamp(Math.round((cy - board.getOriginY()) / board.getTile()), 0, 9);
                    boolean sameCell = (tr == downRow && tc == downCol);
                    if (sameCell) {
                        glide(downRow, downCol, null);
                    } else if (listener != null) {
                        listener.onPieceDropped(PieceView.this, downRow, downCol, tr, tc);
                    } else {
                        glide(tr, tc, null);
                    }
                } else {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                        listener.onPieceTapped(PieceView.this); // 原地点按=选中/吃子
                    }
                    glide(downRow, downCol, null);
                }
                return true;
            }
        }
        return false;
    }

    private int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 平滑滑到某格中心；结束后把边距钉死并回调。 */
    public void glide(final int r, final int c, final Runnable end) {
        animate().x(targetX(c)).y(targetY(r)).setDuration(140).withEndAction(new Runnable() {
            @Override
            public void run() {
                place(r, c);
                if (end != null) end.run();
            }
        });
    }

    /** AI走子：飞到目标格（调用方已更新逻辑盘）。 */
    public void glideForAi(final int r, final int c, final Runnable done) {
        animate().x(targetX(c)).y(targetY(r)).setDuration(320).withEndAction(new Runnable() {
            @Override
            public void run() {
                place(r, c);
                if (done != null) done.run();
            }
        });
    }

    /** 被吃：飞向战利品区并缩小（逻辑盘调用方更新）。 */
    public void flyToShrink(float tx, float ty, final Runnable done) {
        animate().x(tx - getLayoutParams().width / 2f).y(ty - getLayoutParams().height / 2f)
                .scaleX(0.5f).scaleY(0.5f).setDuration(300).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (done != null) done.run();
            }
        });
    }

    /** 从战利品区拿回棋盘格。 */
    public void restoreTo(int r, int c) {
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(1f);
        place(r, c);
    }
}
