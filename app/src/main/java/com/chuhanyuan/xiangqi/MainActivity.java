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
    private EditText etInput;
    private SharedPreferences sp;
    private AiService ai;
    private SoundPool sounds;
    private int sndMove = 0, sndCapture = 0;
    private boolean aiBusy = false;
    private final Map<String, Integer> aiTrophies = new HashMap<>();
    private final Map<String, Integer> myTrophies = new HashMap<>();
    private Runnable pendingAiTurn;
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
        etInput = findViewById(R.id.etInput);
        sp = getSharedPreferences("chuhan", MODE_PRIVATE);
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
        for (PieceView pv : pieces) board.removeView(pv);
        pieces.clear();
        capturedRed.clear();
        capturedBlack.clear();
        myTrophies.clear();
        aiTrophies.clear();
        capturedArea.removeAllViews();
        for (String spec : GameLogic.initialSpecs()) {
            String[] p = spec.split(",");
            char kind = p[0].charAt(0);
            boolean red = GameLogic.isRedPiece(kind);
            PieceView pv = new PieceView(this, board, game, kind, red, this);
            pv.setSize(board.getTile());
            board.addView(pv);
            pieces.add(pv);
            pv.setCell(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        board.post(new Runnable() {
            @Override
            public void run() {
                for (PieceView pv : pieces) {
                    pv.setSize(board.getTile());
                    pv.place(pv.getRow(), pv.getCol());
                }
            }
        });
        if (ai != null) ai.resetHistory();
        if (pendingAiTurn != null) ui.removeCallbacks(pendingAiTurn);
        aiBusy = false;
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
        runAiTurn(prompt);
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

    @Override
    public void onPieceDropped(PieceView pv, int fr, int fc, int tr, int tc) {
        boolean wasCapture = game.pieceAt(tr, tc) != 0;
        game.move(fr, fc, tr, tc);
        pv.glide(tr, tc, null);
        if (wasCapture) {
            PieceView victim = findPieceAt(tr, tc, pv);
            if (victim != null) {
                movePieceToTrophy(victim, !pv.isRed);
                playSnd(sndCapture);
                showBubble("系统", "吃！");
            } else {
                playSnd(sndMove);
            }
        } else {
            playSnd(sndMove);
            maybeTriggerAi();
        }
    }

    private PieceView findPieceAt(int r, int c, PieceView exclude) {
        for (PieceView pv : pieces) {
            if (pv != exclude && pv.getRow() == r && pv.getCol() == c) return pv;
        }
        return null;
    }

    /** 把被吃的子摆到对应一方的战利品区（0=玩家侧，1=AI侧）。 */
    private void movePieceToTrophy(final PieceView victim, boolean toPlayerSide) {
        pieces.remove(victim);
        float tx = trophyX(toPlayerSide ? myTrophies : aiTrophies, victim);
        float ty = toPlayerSide
                ? (float) (board.getHeight() + dp(28))
                : board.getOriginY() - dp(14);
        victim.flyToShrink(tx, ty, null);
        if (toPlayerSide) capturedBlack.add(victim);
        else capturedRed.add(victim);
        addTrophyChip(toPlayerSide, victim.kind, victim);
    }

    private float trophyX(Map<String, Integer> trophies, PieceView victim) {
        String key = String.valueOf(victim.kind);
        Integer n = trophies.get(key);
        n = (n == null ? 0 : n);
        trophies.put(key, n + 1);
        return board.getOriginX() + board.getTile() * 0.5f + n * board.getTile() * 0.42f;
    }

    private void maybeTriggerAi() {
        if (pendingAiTurn != null) ui.removeCallbacks(pendingAiTurn);
        pendingAiTurn = new Runnable() {
            @Override
            public void run() {
                requestAi("【轮到你】棋盘如上。请走棋（调用 move_piece），也可以顺便说一句话。"
                        + "记住：这是无规则棋盘，只要起点上有你方棋子且终点在棋盘内即可，任何走法都合法。");
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
                String msg = "（他伸手抓了个空——" + m.from + " 上没有子，棋盘没动）";
                String say = (m.say != null && !m.say.isEmpty()) ? m.say + " " + msg : msg;
                showBubble("AI", say);
                return;
            }
            final boolean isCapture = game.pieceAt(m.tr, m.tc) != 0;
            game.move(m.fr, m.fc, m.tr, m.tc);
            if (isCapture) {
                final PieceView victim = findPieceAt(m.tr, m.tc, mover);
                mover.glideForAi(m.tr, m.tc, new Runnable() {
                    @Override
                    public void run() {
                        if (victim != null) movePieceToTrophy(victim, victim.isRed);
                    }
                });
                playSnd(sndCapture);
            } else {
                mover.glideForAi(m.tr, m.tc, null);
                playSnd(sndMove);
            }
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

    private void addTrophyChip(boolean playerSide, char kind, final PieceView victim) {
        TextView chip = new TextView(this);
        chip.setText(String.valueOf(kind));
        chip.setGravity(Gravity.CENTER);
        int side = dp(30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(dp(3), 0, dp(3), 0);
        chip.setLayoutParams(lp);
        chip.setTextSize(14);
        chip.setTextColor(getResources().getColor(playerSide ? R.color.black_piece : R.color.red_piece));
        chip.setBackgroundResource(R.drawable.bubble_bg);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnVictim(victim);
            }
        });
        capturedArea.addView(chip, playerSide ? capturedArea.getChildCount() : 0);
        if (playerSide) capturedScroll.post(new Runnable() {
            @Override
            public void run() {
                capturedScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT);
            }
        });
    }

    /** 点战利品：拿回棋盘（放回初始格附近空位）。 */
    private void returnVictim(PieceView victim) {
        int[] rc = nearestFreeCell(victim.isRed ? 9 : 0);
        if (rc == null) {
            Toast.makeText(this, "底线上没有空格了", Toast.LENGTH_SHORT).show();
            return;
        }
        int idx = capturedBlack.indexOf(victim);
        if (idx < 0) idx = capturedRed.indexOf(victim);
        removeTrophyChipAt(idx);
        if (victim.isRed) capturedRed.remove(victim);
        else capturedBlack.remove(victim);
        game.put(rc[0], rc[1], victim.kind);
        victim.restoreTo(rc[0], rc[1]);
        board.addView(victim);
        pieces.add(victim);
        playSnd(sndMove);
    }

    private void removeTrophyChipAt(int index) {
        if (index >= 0 && index < capturedArea.getChildCount()) {
            capturedArea.removeViewAt(index);
        }
    }

    private int[] nearestFreeCell(int prefRow) {
        int[] order = {4, 3, 5, 2, 6, 1, 7, 0, 8};
        for (int d : order) {
            int r = prefRow + (d % 2 == 0 ? d / 2 : -(d / 2) - 1);
            if (r < 0 || r > 9) continue;
            for (int c = 4, off = 0; c >= 0 && c <= 8; off++) {
                if (game.pieceAt(r, c) == 0) return new int[]{r, c};
                c += (off % 2 == 0 ? off / 2 + 1 : -(off / 2 + 1));
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sounds != null) sounds.release();
    }
}
