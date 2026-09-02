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

/** 一枚棋子：像现实中拿起、拖动、放下。代码层不应用象棋规则。 */
public class PieceView extends AppCompatTextView {
    public interface OnPieceMoved {
        void onPieceDropped(PieceView pv,int fromRow,int fromCol,int toRow,int toCol);
        void onPieceTapped(PieceView pv);
    }

    public final boolean isRed;
    public final char kind;
    private final BoardView board;
    private final GameLogic game;
    private final OnPieceMoved listener;
    private int row,col,homeRow,homeCol,downRow,downCol;
    private float startX,startY;
    private boolean dragging,moved,selected,lastMove;
    private int colStroke,colGold;

    public PieceView(Context c,BoardView board,GameLogic game,char kind,boolean isRed,OnPieceMoved listener){
        super(c); this.board=board; this.game=game; this.kind=kind; this.isRed=isRed; this.listener=listener;
        setText(String.valueOf(kind)); setGravity(android.view.Gravity.CENTER); setTypeface(Typeface.DEFAULT_BOLD);
        colStroke=ContextCompat.getColor(c,isRed?R.color.red_piece:R.color.black_piece);
        colGold=ContextCompat.getColor(c,R.color.gold_accent); setTextColor(colStroke); updateBg();
        setOnTouchListener((v,e)->touch(e));
    }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    private void updateBg(){
        GradientDrawable g=new GradientDrawable(); g.setShape(GradientDrawable.OVAL);
        int strokeColor=(selected||lastMove)?colGold:colStroke;
        int strokeWidth=selected?3:(lastMove?3:2);
        g.setColor(ContextCompat.getColor(getContext(),R.color.piece_face)); g.setStroke(dp(strokeWidth),strokeColor); setBackground(g);
        setElevation(dp(dragging?10:(selected?6:(lastMove?7:4))));
    }
    public void setSize(float tile){
        int sz=(int)(tile*0.9f); setTextSize(TypedValue.COMPLEX_UNIT_PX,tile*0.48f);
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)getLayoutParams(); if(lp==null) lp=new FrameLayout.LayoutParams(sz,sz); else {lp.width=sz;lp.height=sz;}
        setLayoutParams(lp); updateBg();
    }
    private float targetX(int c){return board.x(c)-getLayoutParams().width/2f;}
    private float targetY(int r){return board.y(r)-getLayoutParams().height/2f;}
    public void place(int r,int c){
        row=r;col=c;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)getLayoutParams();
        if(lp==null)return;lp.leftMargin=Math.round(targetX(c));lp.topMargin=Math.round(targetY(r));setLayoutParams(lp);setTranslationX(0);setTranslationY(0);
    }
    public int getRow(){return row;} public int getCol(){return col;} public void setCell(int r,int c){row=r;col=c;}
    public void setSelected(boolean s){selected=s;updateBg();}
    public void setHome(int r,int c){homeRow=r;homeCol=c;} public int getHomeRow(){return homeRow;} public int getHomeCol(){return homeCol;}

    public void setLastMoveHighlighted(boolean v){
        lastMove=v; if(!v){animate().cancel();setScaleX(1f);setScaleY(1f);} updateBg();
    }
    public void startLastMovePulse(){
        if(!lastMove)return;
        animate().cancel();setScaleX(1f);setScaleY(1f);
        animate().scaleX(1.07f).scaleY(1.07f).setDuration(420).withEndAction(()->{
            if(lastMove) animate().scaleX(1f).scaleY(1f).setDuration(420).withEndAction(()->startLastMovePulse()).start();
        }).start();
    }

    public void flyHome(int r,int c){
        setEnabled(true);setVisibility(View.VISIBLE);animate().cancel();setScaleX(1f);setScaleY(1f);setAlpha(1f);
        animate().x(targetX(c)).y(targetY(r)).scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).withEndAction(()->place(r,c));
    }

    private boolean touch(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                dragging=true;moved=false;downRow=row;downCol=col;startX=e.getRawX();startY=e.getRawY();updateBg();return true;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getRawX()-startX,dy=e.getRawY()-startY;
                if(Math.abs(dx)>dp(6)||Math.abs(dy)>dp(6))moved=true;
                if(moved){setTranslationX(dx);setTranslationY(dy);}return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging=false;updateBg();
                if(moved&&e.getActionMasked()==MotionEvent.ACTION_UP){
                    float cx=getX()+getTranslationX()+getLayoutParams().width/2f;
                    float cy=getY()+getTranslationY()+getLayoutParams().height/2f;
                    int tc=clamp(Math.round((cx-board.getOriginX())/board.getTile()),0,8);
                    int tr=clamp(Math.round((cy-board.getOriginY())/board.getTile()),0,9);
                    if(tr==downRow&&tc==downCol)glide(downRow,downCol,null);else if(listener!=null)listener.onPieceDropped(this,downRow,downCol,tr,tc);else glide(tr,tc,null);
                } else {
                    if(e.getActionMasked()==MotionEvent.ACTION_UP&&listener!=null)listener.onPieceTapped(this);
                    glide(downRow,downCol,null);
                }
                return true;
        }
        return false;
    }
    private int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
    public void glide(final int r,final int c,final Runnable end){animate().x(targetX(c)).y(targetY(r)).setDuration(140).withEndAction(()->{place(r,c);if(end!=null)end.run();});}
    public void glideForAi(final int r,final int c,final Runnable done){animate().x(targetX(c)).y(targetY(r)).setDuration(420).withEndAction(()->{place(r,c);if(done!=null)done.run();});}
    public void restoreTo(int r,int c){animate().cancel();setScaleX(1f);setScaleY(1f);setAlpha(1f);place(r,c);}
}
