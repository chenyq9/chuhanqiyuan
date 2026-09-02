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
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements PieceView.OnPieceMoved {

    private BoardView board;
    private GameLogic game = new GameLogic();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PieceView> pieces = new ArrayList<>();
    private final List<PieceView> capturedRed = new ArrayList<>();
    private final List<PieceView> capturedBlack = new ArrayList<>();
    private TextView tvBubble;
    private LinearLayout capturedArea;
    private HorizontalScrollView capturedScroll;
    private LinearLayout capturedTopArea;
    private HorizontalScrollView capturedTopScroll;
    private final Map<PieceView, TextView> trophyChips = new HashMap<>();
    private PieceView selectedTrophy;
    private EditText etInput;
    private SharedPreferences sp;
    private AiService ai;
    private SoundPool sounds;
    private int sndMove = 0, sndCapture = 0;
    private boolean aiBusy = false;
    private boolean moveAnimating = false;
    private int aiInvalidAttempts = 0;
    private final Map<String, Integer> aiTrophies = new HashMap<>();
    private final Map<String, Integer> myTrophies = new HashMap<>();
    private Runnable pendingAiTurn;
    private PieceView selectedPiece;
    private final Runnable bubbleHider = new Runnable() {
        @Override
        public void run() {
            tvBubble.animate().alpha(0f).setDuration(500).withEndAction(new Runnable() {
                @Override
                public void run() {
                    tvBubble.setVisibility(View.GONE);
                }
            });
        }
    };

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        board = findViewById(R.id.boardView);
        tvBubble = findViewById(R.id.tvBubble);
        capturedArea = findViewById(R.id.capturedArea);
        capturedScroll = findViewById(R.id.capturedScroll);
        capturedTopArea = findViewById(R.id.capturedTopArea);
        capturedTopScroll = findViewById(R.id.capturedTopScroll);
        etInput = findViewById(R.id.etInput);
        sp = getSharedPreferences("chuhan", MODE_PRIVATE);
        setupBoardTap();
        board.setGeometryListener(new BoardView.GeometryListener() {
            @Override
            public void onGeometryChanged() {
                relayoutAll();
            }
        });
        setupSounds();
        setupButtons();
        newGame();
    }

    private void setupSounds() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            sounds = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
            sndMove = sounds.load(this, R.raw.move, 1);
            sndCapture = sounds.load(this, R.raw.capture, 1);
        } catch (Exception e) {
            sounds = null;
        }
    }

    private void setupButtons() {
        ImageView btnNew = findViewById(R.id.btnNew);
        ImageView btnCfg = findViewById(R.id.btnCfg);
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newGame();
            }
        });
        btnCfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        Button send = findViewById(R.id.btnSend);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = etInput.getText().toString().trim();
                if (!text.isEmpty()) {
                    etInput.setText("");
                    showBubble("你", text);
                    requestAi("【棋友说】" + text);
                }
            }
        });
    }

    private void newGame() {
        game.reset();
        boolean fresh = pieces.isEmpty();
        if (fresh) {
            for (String spec : GameLogic.initialSpecs()) {
                String[] p = spec.split(",");
                char kind = p[0].charAt(0);
                boolean red = GameLogic.isRedPiece(kind);
                PieceView pv = new PieceView(this, board, game, kind, red, this);
                pv.setSize(board.getTile());
                pv.setHome(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                board.addView(pv);
                pieces.add(pv);
                pv.setCell(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
        } else {
            // 战利品区的子回归在盘列表，视图不销毁
            pieces.addAll(capturedBlack);
            pieces.addAll(capturedRed);
        }
        capturedRed.clear();
        capturedBlack.clear();
        myTrophies.clear();
        aiTrophies.clear();
        capturedArea.removeAllViews();
        capturedTopArea.removeAllViews();
        trophyChips.clear();
        selectedPiece = null;
        selectedTrophy = null;
        if (!fresh) {
            // 推倒重摆：所有子（含被吃的）一起飞回初始位
            for (PieceView pv : pieces) {
                pv.setEnabled(true);
                pv.setVisibility(View.VISIBLE);
                pv.flyHome(pv.getHomeRow(), pv.getHomeCol());
            }
        } else {
            board.post(new Runnable() {
                @Override
                public void run() {
                    relayoutAll();
                }
            });
        }
        if (ai != null) ai.resetHistory();
        if (pendingAiTurn != null) ui.removeCallbacks(pendingAiTurn);
        aiBusy = false;
        moveAnimating = false;
        aiInvalidAttempts = 0;
    }

    /** 棋盘几何变化/首建：重设尺寸并归位所有棋子。 */
    private void relayoutAll() {
        float t = board.getTile();
        if (t <= 0) return;
        for (PieceView pv : pieces) {
            pv.setSize(t);
            pv.place(pv.getRow(), pv.getCol());
        }
        relayoutTrophies(capturedBlack, true);
        relayoutTrophies(capturedRed, false);
    }

    private void relayoutTrophies(java.util.List<PieceView> side, boolean playerSide) {
        LinearLayout area = playerSide ? capturedArea : capturedTopArea;
        if (area == null) return;
        // 战利品由独立 chip 承载显示，真实 PieceView 隐藏在棋盘容器中。
        // 几何变化只需刷新 chip 尺寸/布局，不再把被吃棋子当作棋盘棋子定位。
        for (PieceView pv : side) {
            TextView chip = trophyChips.get(pv);
            if (chip != null) chip.setText(String.valueOf(pv.kind));
        }
    }

    private void requestAi(String prompt) {
        if (aiBusy) {
            Toast.makeText(this, "对方还在想…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ai == null) {
            ai = new AiService(sp.getString("base", ""), sp.getString("key", ""), sp.getString("model", ""));
        }
        aiBusy = true;
        runAiTurn(prompt + "\n\n" + game.boardText());
    }

    private boolean inputBlocked() {
        return aiBusy || moveAnimating;
    }

    private void lockMoveAnimation(long ms) {
        moveAnimating = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() { moveAnimating = false; }
        }, ms);
    }

    private void retryAi(String reason) {
        if (aiInvalidAttempts >= 2) {
            showBubble("AI", reason);
            return;
        }
        aiInvalidAttempts++;
        requestAi("【系统】" + reason + "\n请重新读取当前棋盘，只使用你自己的红棋，并重新调用 move_piece。 ");
    }

    private void runAiTurn(final String prompt) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String resp = ai.chat(prompt);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            aiBusy = false;
                            handleResponse(resp);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            aiBusy = false;
                            showBubble("系统", "连接失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** 棋盘点按：先处理“从战利品取回”的落点，再处理普通选中棋子。 */
    private void setupBoardTap() {
        board.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                // DOWN 必须先返回 true 抢占手势；否则该视图收不到后续的 UP
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) return true;
                if (e.getActionMasked() != android.view.MotionEvent.ACTION_UP) return false;
                if (inputBlocked()) return true;

                int c = Math.round((e.getX() - board.getOriginX()) / board.getTile());
                int r = Math.round((e.getY() - board.getOriginY()) / board.getTile());
                if (r < 0 || r > 9 || c < 0 || c > 8) return false;

                if (selectedTrophy != null) {
                    if (game.pieceAt(r, c) != 0) {
                        Toast.makeText(MainActivity.this, "这个落点已有棋子", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    returnTrophyToCell(selectedTrophy, r, c);
                    return true;
                }

                if (selectedPiece == null) return false;
                if (game.pieceAt(r, c) != 0) return false; // 有子的格由棋子自己处理
                final PieceView from = selectedPiece;
                from.setSelected(false);
                selectedPiece = null;
                int fr = from.getRow(), fc = from.getCol();
                if (fr == r && fc == c) return true;
                game.move(fr, fc, r, c);
                from.glide(r, c, null);
                playSnd(sndMove);
                lockMoveAnimation(180);
                maybeTriggerAi();
                return true;
            }
        });
    }

    @Override
    public void onPieceTapped(PieceView pv) {
        if (!pv.isEnabled()) return; // 被吃后真实 PieceView 仅作状态对象，不直接参与点击
        if (selectedTrophy != null) {
            selectedTrophy = null;
            return;
        }
        if (selectedPiece == pv) { // 再点一下=取消选中
            pv.setSelected(false);
            selectedPiece = null;
            return;
        }
        if (selectedPiece != null && pv.isRed == selectedPiece.isRed) { // 点己方子=换选
            selectedPiece.setSelected(false);
            selectedPiece = pv;
            pv.setSelected(true);
            return;
        }
        if (selectedPiece != null) { // 点敌方子=吃过去
            final PieceView from = selectedPiece;
            from.setSelected(false);
            selectedPiece = null;
            int fr = from.getRow(), fc = from.getCol(), tr = pv.getRow(), tc = pv.getCol();
            if (fr == tr && fc == tc) return;
            boolean wasCapture = game.pieceAt(tr, tc) != 0;
            game.move(fr, fc, tr, tc);
            from.glide(tr, tc, null);
            if (wasCapture) {
                movePieceToTrophy(pv, from.isRed);
                playSnd(sndCapture);
                showBubble("系统", "吃！");
            } else {
                playSnd(sndMove);
            }
            lockMoveAnimation(180);
            maybeTriggerAi();
            return;
        }
        clearTrophySelection();
        selectedPiece = pv; // 选中
        pv.setSelected(true);
    }

    @Override
    public void onPieceDropped(PieceView pv, int fr, int fc, int tr, int tc) {
        if (inputBlocked()) {
            pv.glide(fr, fc, null);
            return;
        }
        if (selectedPiece != null) {
            selectedPiece.setSelected(false);
            selectedPiece = null;
        }
        PieceView victim = findPieceAt(tr, tc, pv);
        if (victim != null && victim.isRed == pv.isRed) {
            pv.glide(fr, fc, null); // 拖到己方子上：放不下，弹回
            playSnd(sndMove);
            return;
        }
        boolean wasCapture = victim != null;
        game.move(fr, fc, tr, tc);
        pv.glide(tr, tc, null);
        if (wasCapture) {
            movePieceToTrophy(victim, pv.isRed);
            playSnd(sndCapture);
            showBubble("系统", "吃！");
        } else {
            playSnd(sndMove);
        }
        lockMoveAnimation(180);
        maybeTriggerAi();
    }

    private PieceView findPieceAt(int r, int c, PieceView exclude) {
        for (PieceView pv : pieces) {
            if (pv != exclude && pv.getRow() == r && pv.getCol() == c) return pv;
        }
        return null;
    }

    /** 把被吃的子放到吃子方对应的战利品区：红吃黑=下方，黑吃红=上方。 */
    private void movePieceToTrophy(final PieceView victim, boolean captureByRed) {
        pieces.remove(victim);
        final java.util.List<PieceView> side = captureByRed ? capturedBlack : capturedRed;
        side.add(victim);
        selectedTrophy = null;

        // 真正棋子留在 BoardView 中仅用于保存状态，但禁止继续响应棋盘点击。
        victim.animate().cancel();
        victim.setEnabled(false);
        victim.setVisibility(View.INVISIBLE);
        addTrophyChip(captureByRed, victim.kind, victim);
    }

    private void maybeTriggerAi() {
        if (pendingAiTurn != null) ui.removeCallbacks(pendingAiTurn);
        aiInvalidAttempts = 0;
        pendingAiTurn = new Runnable() {
            @Override
            public void run() {
                requestAi("【轮到你】棋盘如上。请走棋（调用 move_piece），也可以顺便说一句话。"
                        + "记住：这是无规则棋盘。像真人下棋一样，你可以把自己的红棋放到任何棋盘格；终点若是朋友的黑棋，就是吃子。");
            }
        };
        ui.postDelayed(pendingAiTurn, 400);
    }

    private void handleResponse(String resp) {
        AiService.Move m = AiService.firstMove(resp);
        String content = AiService.content(resp);
        if (m != null) {
            final PieceView mover = findPieceAt(m.fr, m.fc, null);
            if (mover == null) {
                ai.noteToolMiss(m.from + m.to);
                retryAi("他上一手抓空了：" + m.from + " 上没有可移动的棋子。");
                return;
            }
            if (!mover.isRed) {
                ai.noteToolMiss(m.from + m.to);
                retryAi("他上一手拿的是朋友的黑棋：" + m.from + "。像真人一样，AI 只能拿自己的棋子移动；如果想吃黑棋，应把自己的红棋移动到黑棋所在格。");
                return;
            }
            if (m.tr < 0 || m.tr > 9 || m.tc < 0 || m.tc > 8) {
                ai.noteToolMiss(m.from + m.to);
                retryAi("他上一手目标坐标越界：" + m.to + "。");
                return;
            }
            final char target = game.pieceAt(m.tr, m.tc);
            if (target != 0 && GameLogic.isRedPiece(target)) {
                ai.noteToolMiss(m.from + m.to);
                retryAi("他上一手想把红棋放到已有红棋的格子：" + m.to + "。");
                return;
            }

            final boolean isCapture = target != 0;
            final PieceView victim = isCapture ? findPieceAt(m.tr, m.tc, mover) : null;
            if (isCapture && victim == null) {
                ai.noteToolMiss(m.from + m.to);
                retryAi("棋盘数据和界面不同步：" + m.to + " 有棋子，但界面找不到对应棋子。");
                return;
            }
            game.move(m.fr, m.fc, m.tr, m.tc);
            moveAnimating = true;
            mover.glideForAi(m.tr, m.tc, new Runnable() {
                @Override
                public void run() {
                    moveAnimating = false;
                    if (victim != null) movePieceToTrophy(victim, mover.isRed);
                }
            });
            playSnd(isCapture ? sndCapture : sndMove);
            if (m.say != null && !m.say.isEmpty()) {
                showBubble("AI", m.say);
            } else if (isCapture && (content == null || content.isEmpty())) {
                showBubble("AI", "吃！");
            } else if (content != null && !content.isEmpty()) {
                showBubble("AI", content);
            }
        } else if (content != null && !content.isEmpty()) {
            showBubble("AI", content);
        }
    }

    private void showBubble(String who, String text) {
        tvBubble.setText(who + "：" + text);
        tvBubble.animate().cancel();
        tvBubble.setAlpha(1f);
        tvBubble.setVisibility(View.VISIBLE);
        ui.removeCallbacks(bubbleHider);
        ui.postDelayed(bubbleHider, 4000);
    }

    private void playSnd(int id) {
        if (sounds != null && id != 0) sounds.play(id, 1f, 1f, 1, 0, 1f);
    }

    private void addTrophyChip(final boolean playerSide, char kind, final PieceView victim) {
        final TextView chip = new TextView(this);
        chip.setText(String.valueOf(kind));
        chip.setGravity(Gravity.CENTER);
        int side = dp(40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        chip.setLayoutParams(lp);
        chip.setTextSize(18);
        chip.setTextColor(getResources().getColor(playerSide ? R.color.black_piece : R.color.red_piece));
        chip.setBackgroundResource(R.drawable.bubble_bg);
        trophyChips.put(victim, chip);

        chip.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            float startTx, startTy;
            boolean moved;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startTx = v.getTranslationX();
                        startTy = v.getTranslationY();
                        moved = false;
                        if (selectedTrophy != null && selectedTrophy != victim) clearTrophySelection();
                        selectedTrophy = victim;
                        v.setSelected(true);
                        return true;

                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        v.setTranslationX(startTx + dx);
                        v.setTranslationY(startTy + dy);
                        return true;

                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && moved) {
                            int[] loc = new int[2];
                            board.getLocationOnScreen(loc);
                            float px = e.getRawX() - loc[0];
                            float py = e.getRawY() - loc[1];
                            int tc = Math.round((px - board.getOriginX()) / board.getTile());
                            int tr = Math.round((py - board.getOriginY()) / board.getTile());
                            if (tr >= 0 && tr <= 9 && tc >= 0 && tc <= 8 && game.pieceAt(tr, tc) == 0) {
                                returnTrophyToCell(victim, tr, tc);
                            } else {
                                v.animate().translationX(0).translationY(0).setDuration(120).start();
                                chip.setSelected(true);
                            }
                        } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            selectedTrophy = victim;
                            chip.setSelected(true);
                        }
                        return true;
                }
                return false;
            }
        });

        LinearLayout area = playerSide ? capturedArea : capturedTopArea;
        area.addView(chip);
        if (playerSide) {
            capturedScroll.post(new Runnable() {
                @Override public void run() {
                    capturedScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT);
                }
            });
        } else {
            capturedTopScroll.post(new Runnable() {
                @Override public void run() {
                    capturedTopScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT);
                }
            });
        }
    }

    /** 点击/拖动战利品后，按用户指定的棋盘落点放回，而不是自动找“附近空位”。 */
    private void returnTrophyToCell(final PieceView victim, final int r, final int c) {
        if (victim == null || !isCaptured(victim)) return;
        if (game.pieceAt(r, c) != 0) {
            Toast.makeText(this, "这个落点已有棋子", Toast.LENGTH_SHORT).show();
            return;
        }

        removeTrophyChip(victim);
        if (victim.isRed) capturedRed.remove(victim);
        else capturedBlack.remove(victim);

        game.put(r, c, victim.kind);
        victim.setEnabled(true);
        victim.setVisibility(View.VISIBLE);
        victim.setSelected(false);
        victim.setScaleX(1f);
        victim.setScaleY(1f);
        victim.setAlpha(1f);
        victim.place(r, c);
        if (!pieces.contains(victim)) {
            pieces.add(victim);
        }
        selectedTrophy = null;
        playSnd(sndMove);
        lockMoveAnimation(140);
    }

    private boolean isCaptured(PieceView victim) {
        return capturedRed.contains(victim) || capturedBlack.contains(victim);
    }

    private void removeTrophyChip(PieceView victim) {
        TextView chip = trophyChips.remove(victim);
        if (chip == null) return;
        ViewGroup parent = (ViewGroup) chip.getParent();
        if (parent != null) parent.removeView(chip);
    }

    private void clearTrophySelection() {
        if (selectedTrophy != null) {
            TextView chip = trophyChips.get(selectedTrophy);
            if (chip != null) chip.setSelected(false);
            selectedTrophy = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sounds != null) sounds.release();
    }
}
