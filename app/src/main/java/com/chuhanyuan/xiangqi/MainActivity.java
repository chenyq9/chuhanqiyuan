package com.chuhanyuan.xiangqi;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Toast;
import android.app.AlertDialog;
import android.graphics.Color;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements PieceView.OnPieceMoved {
    private BoardView board;
    private GameLogic game=new GameLogic();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final List<PieceView> pieces=new ArrayList<>();
    private final List<PieceView> capturedRed=new ArrayList<>(); // 被黑吃掉的红子
    private final List<PieceView> capturedBlack=new ArrayList<>(); // 被红吃掉的黑子
    private TextView tvBubble,tvEat,tvTurnStatus;
    private LinearLayout capturedArea,capturedTopArea,bottomPanel;
    private HorizontalScrollView capturedScroll,capturedTopScroll;
    private final Map<PieceView,TextView> trophyChips=new HashMap<>();
    private final List<String> chatLines=new ArrayList<>();
    private PieceView selectedTrophy,selectedPiece,lastAiPiece;
    private TextView dragGhost;
    private EditText etInput;
    private SharedPreferences sp;
    private AiService ai;
    private SoundPool sounds;
    private int sndMove=0,sndCapture=0;
    private boolean aiBusy=false,moveAnimating=false,freeMode=false;
    private boolean sendFullBoardNext=true;
    private int movesSinceSnapshot=0;
    private int aiInvalidAttempts=0;
    private Runnable pendingAiTurn;
    private final Runnable bubbleHider=()->{ if(tvBubble==null)return; tvBubble.animate().alpha(0f).setDuration(450).withEndAction(()->tvBubble.setVisibility(View.GONE)); };

    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);setContentView(R.layout.activity_main);
        board=findViewById(R.id.boardView);tvBubble=findViewById(R.id.tvBubble);tvEat=findViewById(R.id.tvEat);
        tvTurnStatus=findViewById(R.id.tvTurnStatus);bottomPanel=findViewById(R.id.bottomPanel);
        capturedArea=findViewById(R.id.capturedArea);capturedScroll=findViewById(R.id.capturedScroll);
        capturedTopArea=findViewById(R.id.capturedTopArea);capturedTopScroll=findViewById(R.id.capturedTopScroll);etInput=findViewById(R.id.etInput);
        sp=getSharedPreferences("chuhan",MODE_PRIVATE);
        setupKeyboardBehavior();setupBoardTap();
        board.setGeometryListener(()->relayoutAll());setupSounds();setupButtons();newGame();
    }

    private void setupKeyboardBehavior(){
        // 键盘弹起时不重新布局棋盘，只把底部聊天/输入区抬上去。
        final View root=findViewById(R.id.rootContainer);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int ime=0;
            if(android.os.Build.VERSION.SDK_INT>=30) ime=insets.getInsets(WindowInsets.Type.ime()).bottom;
            if(ime>0) bottomPanel.setTranslationY(-ime); else if(android.os.Build.VERSION.SDK_INT>=30) bottomPanel.setTranslationY(0);
            return insets;
        });
        // Android 7~10 没有 Type.ime，用可见区域检测键盘高度。
        if(android.os.Build.VERSION.SDK_INT<30){
            root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
                android.graphics.Rect rect=new android.graphics.Rect();root.getWindowVisibleDisplayFrame(rect);
                int diff=root.getRootView().getHeight()-rect.bottom;
                int keyboard=diff>dp(80)?diff:0;
                bottomPanel.setTranslationY(-keyboard);
            });
        }
        root.requestApplyInsets();
    }

    private void setupSounds(){
        try{
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            sounds=new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();sndMove=sounds.load(this,R.raw.move,1);sndCapture=sounds.load(this,R.raw.capture,1);
        }catch(Exception e){sounds=null;}
    }

    private void setupButtons(){
        ImageView btnNew=findViewById(R.id.btnNew),btnCfg=findViewById(R.id.btnCfg),btnChat=findViewById(R.id.btnChat);
        Button btnFree=findViewById(R.id.btnFree);
        btnNew.setOnClickListener(v->newGame());
        btnCfg.setOnClickListener(v->startActivity(new Intent(MainActivity.this,SettingsActivity.class)));
        btnChat.setOnClickListener(v->showChatDialog());
        btnFree.setOnClickListener(v->{
            if(aiBusy){Toast.makeText(this,"对方正在思考，先等这一手结束",Toast.LENGTH_SHORT).show();return;}
            freeMode=!freeMode;
            if(freeMode){
                if(pendingAiTurn!=null)ui.removeCallbacks(pendingAiTurn);
                btnFree.setText("自由✓");
                btnFree.setSelected(true);
                showBubble("系统","自由操作已打开：你可以随意移动任意一方的棋子，暂时不会发给 AI。");
            }else{
                btnFree.setText("自由");
                btnFree.setSelected(false);
                sendFullBoardNext=true; movesSinceSnapshot=0; aiInvalidAttempts=0;
                showBubble("系统","自由操作结束，对面现在才看到这张棋盘。");
                requestAi("【自由操作结束】朋友刚刚完成了一轮自由摆放。现在请只根据眼前最终棋盘，像真人一样回应并决定是否行动。",true);
            }
            updateTurnStatus();
        });
        Button send=findViewById(R.id.btnSend);
        send.setOnClickListener(v->{
            String text=etInput.getText().toString().trim();if(text.isEmpty())return;
            etInput.setText("");addChat("你",text);showBubble("你",text);
            if(freeMode)return;
            requestAi("【棋友说】"+text,false);
        });
        btnFree.setText(freeMode?"自由✓":"自由");
    }

    private void newGame(){
        game.reset();boolean fresh=pieces.isEmpty();
        if(fresh){
            for(String spec:GameLogic.initialSpecs()){
                String[] p=spec.split(",");char kind=p[0].charAt(0);boolean red=GameLogic.isRedPiece(kind);
                PieceView pv=new PieceView(this,board,game,kind,red,this);pv.setSize(board.getTile());pv.setHome(Integer.parseInt(p[1]),Integer.parseInt(p[2]));board.addView(pv);pieces.add(pv);pv.setCell(Integer.parseInt(p[1]),Integer.parseInt(p[2]));
            }
        }else pieces.addAll(capturedBlack); // 顺便下面再加入capturedRed
        if(!fresh){pieces.addAll(capturedRed);}
        for(PieceView pv:pieces){pv.setLastMoveHighlighted(false);pv.setEnabled(true);pv.setVisibility(View.VISIBLE);}
        capturedRed.clear();capturedBlack.clear();capturedArea.removeAllViews();capturedTopArea.removeAllViews();trophyChips.clear();
        selectedPiece=null;selectedTrophy=null;clearLastAiMove();aiInvalidAttempts=0;moveAnimating=false;aiBusy=false;freeMode=false;sendFullBoardNext=true;movesSinceSnapshot=0;
        Button btnFree=findViewById(R.id.btnFree);if(btnFree!=null){btnFree.setText("自由");btnFree.setSelected(false);}
        clearChat();updateTurnStatus();
        if(!fresh)for(PieceView pv:pieces)pv.flyHome(pv.getHomeRow(),pv.getHomeCol()); else board.post(this::relayoutAll);
        if(ai!=null)ai.resetHistory();if(pendingAiTurn!=null)ui.removeCallbacks(pendingAiTurn);
        showBubble("系统","新局开始");
    }

    private void relayoutAll(){float t=board.getTile();if(t<=0)return;for(PieceView pv:pieces){pv.setSize(t);pv.place(pv.getRow(),pv.getCol());}relayoutTrophies(capturedBlack,true);relayoutTrophies(capturedRed,false);}
    private void relayoutTrophies(List<PieceView> side,boolean playerSide){for(PieceView pv:side){TextView chip=trophyChips.get(pv);if(chip!=null)chip.setText(String.valueOf(pv.kind));}}

    private void updateTurnStatus(){
        if(tvTurnStatus==null)return;
        if(aiBusy){tvTurnStatus.setVisibility(View.VISIBLE);tvTurnStatus.setText("● 对方正在思考…");}
        else if(freeMode){tvTurnStatus.setVisibility(View.VISIBLE);tvTurnStatus.setText("● 自由操作中");}
        else tvTurnStatus.setVisibility(View.GONE);
    }
    private void addChat(String who,String text){
        if(text==null||text.trim().isEmpty())return;
        chatLines.add(who+"："+text);
        while(chatLines.size()>200)chatLines.remove(0);
    }
    private void clearChat(){chatLines.clear();}
    private void showChatDialog(){
        StringBuilder sb=new StringBuilder();
        if(chatLines.isEmpty())sb.append("这局还没有聊天。\n");
        else for(String line:chatLines)sb.append(line).append('\n').append('\n');
        TextView tv=new TextView(this);tv.setText(sb.toString().trim());tv.setTextSize(15);tv.setTextColor(Color.DKGRAY);tv.setPadding(dp(18),dp(12),dp(18),dp(18));
        ScrollView sv=new ScrollView(this);sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("棋友聊天记录").setView(sv).setPositiveButton("关闭",null).show();
        sv.post(()->sv.fullScroll(View.FOCUS_DOWN));
    }

    private String boardContext(){
        StringBuilder sb=new StringBuilder(game.boardText());
        sb.append("\n当前战利品区（已经被吃、离开棋盘的棋子）：\n");
        appendCaptured(sb,"红棋（被黑吃）",capturedRed);appendCaptured(sb,"黑棋（被红吃）",capturedBlack);
        return sb.toString();
    }
    private void appendCaptured(StringBuilder sb,String title,List<PieceView> list){
        sb.append(title).append("方战利品：");
        if(list.isEmpty()){sb.append("无\n");return;}
        for(int i=0;i<list.size();i++){if(i>0)sb.append("、");sb.append(i+1).append("号").append(list.get(i).kind);}
        sb.append("\n");
    }

    private void requestAi(String prompt,boolean includeFullBoard){
        if(aiBusy){Toast.makeText(this,"对方还在想…",Toast.LENGTH_SHORT).show();return;}
        if(ai==null)ai=new AiService(sp.getString("base",""),sp.getString("key",""),sp.getString("model",""));
        aiBusy=true;updateTurnStatus();
        String payload=prompt;
        if(includeFullBoard||sendFullBoardNext){payload+="\n\n【当前完整棋盘】\n"+boardContext();sendFullBoardNext=false;movesSinceSnapshot=0;}
        runAiTurn(payload);
    }

    private boolean inputBlocked(){return aiBusy||moveAnimating;}
    private void lockMoveAnimation(long ms){moveAnimating=true;ui.postDelayed(()->{moveAnimating=false;},ms);}
    private void clearLastAiMove(){if(lastAiPiece!=null){lastAiPiece.setLastMoveHighlighted(false);lastAiPiece=null;}}
    private void markAiPiece(PieceView pv){clearLastAiMove();lastAiPiece=pv;pv.setLastMoveHighlighted(true);pv.startLastMovePulse();}

    private void retryAi(String reason){
        if(aiInvalidAttempts>=2){aiBusy=false;updateTurnStatus();showBubble("AI",reason);addChat("AI",reason);return;}
        aiInvalidAttempts++;requestAi("【系统】"+reason+"\n请重新读取刚才的棋盘。不要猜坐标，只做一次符合现场情况的物理操作。",true);
    }

    private void runAiTurn(final String prompt){
        new Thread(()->{try{final String resp=ai.chat(prompt);ui.post(()->{aiBusy=false;updateTurnStatus();handleResponse(resp);});}catch(final Exception e){ui.post(()->{aiBusy=false;updateTurnStatus();String msg="连接失败："+(e.getMessage()==null?"未知错误":e.getMessage());showBubble("系统",msg);addChat("系统",msg);});}}).start();
    }

    private void setupBoardTap(){
        board.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)return true;
            if(e.getActionMasked()!=MotionEvent.ACTION_UP)return true;
            if(inputBlocked())return true;
            int c=Math.round((e.getX()-board.getOriginX())/board.getTile()),r=Math.round((e.getY()-board.getOriginY())/board.getTile());
            if(r<0||r>9||c<0||c>8)return true;
            if(selectedTrophy!=null){if(game.pieceAt(r,c)!=0){Toast.makeText(this,"这个位置已经有棋子",Toast.LENGTH_SHORT).show();return true;}returnTrophyToCell(selectedTrophy,r,c);return true;}
            if(selectedPiece==null)return true;
            if(game.pieceAt(r,c)!=0)return true;
            PieceView from=selectedPiece;int fr=from.getRow(),fc=from.getCol();if(fr==r&&fc==c)return true;
            clearLastAiMove();from.setSelected(false);selectedPiece=null;game.move(fr,fc,r,c);from.glide(r,c,null);playSnd(sndMove);lockMoveAnimation(180);afterPlayerMove((from.isRed?"红":"黑")+"棋从"+toCoord(fr,fc)+"到"+toCoord(r,c));return true;
        });
    }

    @Override public void onPieceTapped(PieceView pv){
        if(!pv.isEnabled()||inputBlocked())return;
        if(selectedTrophy!=null){clearTrophySelection();}
        if(selectedPiece==pv){pv.setSelected(false);selectedPiece=null;return;}
        if(selectedPiece!=null&&pv.isRed==selectedPiece.isRed){selectedPiece.setSelected(false);selectedPiece=pv;pv.setSelected(true);return;}
        if(selectedPiece!=null){
            PieceView from=selectedPiece;int fr=from.getRow(),fc=from.getCol(),tr=pv.getRow(),tc=pv.getCol();
            clearLastAiMove();from.setSelected(false);selectedPiece=null;game.move(fr,fc,tr,tc);from.glide(tr,tc,null);
            movePieceToTrophy(pv,from.isRed);playSnd(sndCapture);showEatEffect();addChat("系统","吃！");lockMoveAnimation(180);afterPlayerMove((from.isRed?"红":"黑")+"棋吃掉"+pv.kind+"并从"+toCoord(fr,fc)+"到"+toCoord(tr,tc));return;
        }
        selectedPiece=pv;pv.setSelected(true);
    }

    @Override public void onPieceDropped(PieceView pv,int fr,int fc,int tr,int tc){
        if(inputBlocked()){pv.glide(fr,fc,null);return;}
        if(selectedPiece!=null){selectedPiece.setSelected(false);selectedPiece=null;}
        PieceView victim=findPieceAt(tr,tc,pv);
        if(victim!=null&&victim.isRed==pv.isRed){pv.glide(fr,fc,null);return;}
        clearLastAiMove();boolean wasCapture=victim!=null;game.move(fr,fc,tr,tc);pv.glide(tr,tc,null);
        if(wasCapture){movePieceToTrophy(victim,pv.isRed);playSnd(sndCapture);showEatEffect();addChat("系统","吃！");}else playSnd(sndMove);
        lockMoveAnimation(180);afterPlayerMove((pv.isRed?"红":"黑")+(wasCapture?"棋吃掉"+victim.kind:"棋从")+" " + toCoord(fr,fc)+"到"+toCoord(tr,tc));
    }

    @Override public void onPieceDroppedOutside(PieceView pv,float rawX,float rawY){
        if(inputBlocked()){pv.glide(pv.getRow(),pv.getCol(),null);return;}
        // 像现实里把棋子拿到桌边的“被吃子区”：松手就停在那里，不弹文字提示。
        if(isInsideView(capturedScroll,rawX,rawY)){
            parkPieceInBottomArea(pv);
            return;
        }
        pv.glide(pv.getRow(),pv.getCol(),null);
    }

    private boolean isInsideView(View v,float rawX,float rawY){
        if(v==null||v.getVisibility()!=View.VISIBLE)return false;
        int[] loc=new int[2];v.getLocationOnScreen(loc);
        return rawX>=loc[0]&&rawX<=loc[0]+v.getWidth()
                &&rawY>=loc[1]&&rawY<=loc[1]+v.getHeight();
    }

    private void parkPieceInBottomArea(PieceView pv){
        if(pv==null||!pieces.contains(pv))return;
        int r=pv.getRow(),c=pv.getCol();
        game.put(r,c,0);
        pieces.remove(pv);
        if(pv.isRed)capturedRed.add(pv);else capturedBlack.add(pv);
        pv.animate().cancel();pv.setSelected(false);pv.setEnabled(false);pv.setVisibility(View.INVISIBLE);
        addTrophyChip(true,pv.kind,pv);
        playSnd(sndMove);
        lockMoveAnimation(120);
        afterPlayerMove("把"+pv.kind+"放到了下方被吃子区");
    }

    private PieceView findPieceAt(int r,int c,PieceView exclude){for(PieceView pv:pieces)if(pv!=exclude&&pv.getRow()==r&&pv.getCol()==c&&pv.isEnabled())return pv;return null;}

    /** 谁吃子决定进哪边：红吃黑→下方，黑吃红→上方。 */
    private void movePieceToTrophy(PieceView victim,boolean captureByRed){
        pieces.remove(victim);(captureByRed?capturedBlack:capturedRed).add(victim);selectedTrophy=null;
        victim.animate().cancel();victim.setLastMoveHighlighted(false);victim.setEnabled(false);victim.setVisibility(View.INVISIBLE);addTrophyChip(captureByRed,victim.kind,victim);
    }

    private void afterPlayerMove(String delta){
        if(freeMode)return;
        movesSinceSnapshot++;
        boolean full=sendFullBoardNext||movesSinceSnapshot>=4;
        if(full){sendFullBoardNext=true;}
        maybeTriggerAi(delta,full);
    }
    private void maybeTriggerAi(String delta,boolean full){
        clearLastAiMove();aiInvalidAttempts=0;if(pendingAiTurn!=null)ui.removeCallbacks(pendingAiTurn);
        pendingAiTurn=()->requestAi("【朋友刚完成一次棋盘操作】"+delta+"。现在看看对面的棋盘，像真人一样决定是否回应。默认如果要下棋，就使用 move_piece；一次只做一个实际动作，也可以顺便说一句话。",full);
        ui.postDelayed(pendingAiTurn,550);
    }
    private String toCoord(int r,int c){return String.valueOf((char)('a'+c))+r;}

    private void handleResponse(String resp){
        AiService.Move m=AiService.firstMove(resp);String content=AiService.content(resp);
        if(m==null){if(content!=null&&!content.isEmpty()){showBubble("AI",content);addChat("AI",content);}updateTurnStatus();return;}
        if(m.say!=null&&!m.say.isEmpty()){showBubble("AI",m.say);addChat("AI",m.say);}else if(content!=null&&!content.isEmpty()){showBubble("AI",content);addChat("AI",content);}
        switch(m.action){
            case "move_piece": executeAiMove(m,true); break;
            case "move_friend_piece": executeAiMove(m,false); break;
            case "return_captured_piece": executeAiReturnCaptured(m); break;
            default: retryAi("他说要做一个软件还不认识的动作："+m.action); break;
        }
    }

    private void executeAiMove(AiService.Move m,boolean selfIsRed){
        PieceView mover=findPieceAt(m.fr,m.fc,null);
        if(mover==null){ai.noteToolMiss(m.from+m.to);retryAi("他从"+m.from+"拿了一个空位置。请重新看棋盘。");return;}
        if(mover.isRed!=selfIsRed){ai.noteToolMiss(m.from+m.to);retryAi("他选错了棋子。这个工具对应的是"+(selfIsRed?"AI自己的红棋":"朋友的黑棋")+"。");return;}
        if(m.tr<0||m.tr>9||m.tc<0||m.tc>8){ai.noteToolMiss(m.from+m.to);retryAi("目标"+m.to+"超出棋盘。");return;}
        PieceView victim=findPieceAt(m.tr,m.tc,mover);
        if(victim!=null&&victim.isRed==mover.isRed){ai.noteToolMiss(m.from+m.to);retryAi("目标"+m.to+"已经有自己这一方的棋子，现实棋盘放不下两颗。");return;}
        game.move(m.fr,m.fc,m.tr,m.tc);moveAnimating=true;final boolean capture=victim!=null;
        tvTurnStatus.setText("● 对方正在落子…");
        mover.glideForAi(m.tr,m.tc,()->{moveAnimating=false;markAiPiece(mover);updateTurnStatus();});
        playSnd(capture?sndCapture:sndMove);
        if(capture){movePieceToTrophy(victim,mover.isRed);showEatEffect();addChat("系统","吃！");}
        aiInvalidAttempts=0;
    }

    private void executeAiReturnCaptured(AiService.Move m){
        if(m.capturedSide==null||m.capturedSide.isEmpty()){retryAi("返还棋子工具缺少 capturedSide。");return;}
        List<PieceView> list="red".equalsIgnoreCase(m.capturedSide)?capturedRed:capturedBlack;
        PieceView victim=findNthCaptured(list,m.pieceKind,m.occurrence);
        if(victim==null){retryAi("战利品区里没有他指定的那颗棋子。");return;}
        if(game.pieceAt(m.tr,m.tc)!=0){retryAi("他想把战利品放到"+m.to+"，但那里已经有棋子。");return;}
        removeTrophyChip(victim);list.remove(victim);game.put(m.tr,m.tc,victim.kind);pieces.add(victim);
        victim.setEnabled(true);victim.setVisibility(View.VISIBLE);victim.restoreTo(m.tr,m.tc);playSnd(sndMove);markAiPiece(victim);addChat("系统","AI把被吃的"+victim.kind+"放回了"+m.to);
        updateTurnStatus();
    }

    private PieceView findNthCaptured(List<PieceView> list,char kind,int occurrence){int n=0;for(PieceView pv:list)if(kind==0||pv.kind==kind){n++;if(n==Math.max(1,occurrence))return pv;}return null;}

    private void showEatEffect(){
        tvEat.animate().cancel();tvEat.setText("吃");tvEat.setVisibility(View.VISIBLE);tvEat.setAlpha(0f);tvEat.setScaleX(0.65f);tvEat.setScaleY(0.65f);
        tvEat.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction(()->tvEat.animate().alpha(0f).scaleX(1.3f).scaleY(1.3f).setDuration(360).withEndAction(()->tvEat.setVisibility(View.GONE)).start()).start();
    }
    private void showBubble(String who,String text){tvBubble.setText(who+"："+text);tvBubble.animate().cancel();tvBubble.setAlpha(1f);tvBubble.setVisibility(View.VISIBLE);ui.removeCallbacks(bubbleHider);ui.postDelayed(bubbleHider,4000);}
    private void playSnd(int id){if(sounds!=null&&id!=0)sounds.play(id,1f,1f,1,0,1f);}

    private void addTrophyChip(boolean playerSide,char kind,final PieceView victim){
        final TextView chip=new TextView(this);chip.setText(String.valueOf(kind));chip.setGravity(Gravity.CENTER);int side=dp(42);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(side,side);lp.setMargins(dp(3),dp(4),dp(3),dp(4));chip.setLayoutParams(lp);chip.setTextSize(18);chip.setTextColor(getResources().getColor(victim.isRed?R.color.red_piece:R.color.black_piece));chip.setBackgroundResource(R.drawable.bubble_bg);trophyChips.put(victim,chip);
        chip.setOnTouchListener(new View.OnTouchListener(){float downX,downY;boolean moved;
            @Override public boolean onTouch(View v,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);downX=e.getRawX();downY=e.getRawY();moved=false;
                        if(selectedTrophy!=null&&selectedTrophy!=victim)clearTrophySelection();selectedTrophy=victim;v.setSelected(true);v.animate().cancel();v.setScaleX(1.1f);v.setScaleY(1.1f);showBubble("系统","想放回哪？把它拖到想去的棋盘格，或点它后再点棋盘。" );return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                        if(Math.abs(dx)>dp(6)||Math.abs(dy)>dp(6)){moved=true;if(dragGhost==null){startTrophyGhost(kind,playerSide,e.getRawX(),e.getRawY());v.setAlpha(0.15f);}else moveTrophyGhost(e.getRawX(),e.getRawY());}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if(e.getActionMasked()==MotionEvent.ACTION_UP&&moved){
                            removeTrophyGhost();int[] loc=new int[2];board.getLocationOnScreen(loc);float px=e.getRawX()-loc[0],py=e.getRawY()-loc[1];
                            int tc=Math.round((px-board.getOriginX())/board.getTile()),tr=Math.round((py-board.getOriginY())/board.getTile());
                            if(tr>=0&&tr<=9&&tc>=0&&tc<=8&&game.pieceAt(tr,tc)==0)returnTrophyToCell(victim,tr,tc);else{chip.setAlpha(1f);chip.setSelected(true);chip.setScaleX(1.1f);chip.setScaleY(1.1f);}
                        }else{removeTrophyGhost();chip.setAlpha(1f);chip.setSelected(true);chip.setScaleX(1.1f);chip.setScaleY(1.1f);}
                        return true;
                }return false;
            }
        });
        (playerSide?capturedArea:capturedTopArea).addView(chip);
        (playerSide?capturedScroll:capturedTopScroll).post(()->(playerSide?capturedScroll:capturedTopScroll).fullScroll(HorizontalScrollView.FOCUS_RIGHT));
    }

    private void startTrophyGhost(char kind,boolean playerSide,float rawX,float rawY){
        FrameLayout root=findViewById(R.id.rootContainer);int side=dp(46);
        dragGhost=new TextView(this);dragGhost.setText(String.valueOf(kind));dragGhost.setGravity(Gravity.CENTER);dragGhost.setTextSize(18);dragGhost.setTextColor(getResources().getColor(playerSide?R.color.black_piece:R.color.red_piece));dragGhost.setBackgroundResource(R.drawable.bubble_bg);dragGhost.setAlpha(0.92f);dragGhost.setClickable(false);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(side,side);root.addView(dragGhost,lp);moveTrophyGhost(rawX,rawY);
    }
    private void moveTrophyGhost(float rawX,float rawY){
        if(dragGhost==null)return;int[] loc=new int[2];View root=findViewById(R.id.rootContainer);root.getLocationOnScreen(loc);float side=dragGhost.getLayoutParams().width;dragGhost.setX(rawX-loc[0]-side/2f);dragGhost.setY(rawY-loc[1]-side/2f);
    }
    private void removeTrophyGhost(){if(dragGhost!=null){ViewParent p=dragGhost.getParent();if(p instanceof ViewGroup)((ViewGroup)p).removeView(dragGhost);dragGhost=null;}}

    private void returnTrophyToCell(PieceView victim,int r,int c){
        if(victim==null||!isCaptured(victim)||game.pieceAt(r,c)!=0)return;
        removeTrophyChip(victim);if(victim.isRed)capturedRed.remove(victim);else capturedBlack.remove(victim);game.put(r,c,victim.kind);if(!pieces.contains(victim))pieces.add(victim);victim.setEnabled(true);victim.setVisibility(View.VISIBLE);victim.setSelected(false);victim.restoreTo(r,c);selectedTrophy=null;playSnd(sndMove);lockMoveAnimation(140);updateTurnStatus();afterPlayerMove("把战利品区的"+victim.kind+"放回"+toCoord(r,c));
    }
    private boolean isCaptured(PieceView v){return capturedRed.contains(v)||capturedBlack.contains(v);}
    private void removeTrophyChip(PieceView victim){TextView chip=trophyChips.remove(victim);if(chip==null)return;ViewGroup parent=(ViewGroup)chip.getParent();if(parent!=null)parent.removeView(chip);}
    private void clearTrophySelection(){if(selectedTrophy!=null){TextView chip=trophyChips.get(selectedTrophy);if(chip!=null){chip.setSelected(false);chip.setTranslationX(0);chip.setTranslationY(0);chip.setScaleX(1f);chip.setScaleY(1f);}selectedTrophy=null;}}
    @Override protected void onDestroy(){removeTrophyGhost();super.onDestroy();if(sounds!=null)sounds.release();}
}
