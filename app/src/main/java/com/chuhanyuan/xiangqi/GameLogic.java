package com.chuhanyuan.xiangqi;

/**
 * 棋盘状态。只记录"哪个格子上有什么子"，不含任何象棋规则。
 * 坐标：列0-8（对应a-i），行0-9。行0在棋盘文字显示的顶部（AI红方一侧），
 * 行9在底部（玩家黑方一侧）。
 */
public class GameLogic {

    public static final String RED = "帥仕相俥傌炮兵";
    public static final String BLACK = "將士象車馬砲卒";

    private final char[] b = new char[90];

    public static boolean isRedPiece(char c) {
        return RED.indexOf(c) >= 0;
    }

    public char pieceAt(int row, int col) {
        if (row < 0 || row > 9 || col < 0 || col > 8) return 0;
        return b[row * 9 + col];
    }

    /** 执行移动（物理层面：拿起点子放进终点格）。返回被吃掉的子（0=没吃）。 */
    public char move(int fr, int fc, int tr, int tc) {
        char cap = b[tr * 9 + tc];
        b[tr * 9 + tc] = b[fr * 9 + fc];
        b[fr * 9 + fc] = 0;
        return cap;
    }

    public void reset() {
        java.util.Arrays.fill(b, (char) 0);
        for (String s : initialSpecs()) {
            String[] p = s.split(",");
            b[Integer.parseInt(p[1]) * 9 + Integer.parseInt(p[2])] = p[0].charAt(0);
        }
    }

    /** 直接放置一枚子（从战利品区拿回时用）。 */
    public void put(int r, int c, char piece) {
        if (r < 0 || r > 9 || c < 0 || c > 8) return;
        b[r * 9 + c] = piece;
    }

    /** 初始阵型："子,行,列"。红(AI)在行0-3一侧，黑(玩家)在行6-9一侧。 */
    public static String[] initialSpecs() {
        return new String[]{
                "帥,0,4", "仕,0,3", "仕,0,5", "相,0,2", "相,0,6",
                "俥,0,0", "俥,0,8", "傌,0,1", "傌,0,7",
                "炮,2,1", "炮,2,7",
                "兵,3,0", "兵,3,2", "兵,3,4", "兵,3,6", "兵,3,8",
                "將,9,4", "士,9,3", "士,9,5", "象,9,2", "象,9,6",
                "車,9,0", "車,9,8", "馬,9,1", "馬,9,7",
                "砲,7,1", "砲,7,7",
                "卒,6,0", "卒,6,2", "卒,6,4", "卒,6,6", "卒,6,8"
        };
    }

    /** 棋盘文字快照，随每轮请求发给AI。 */
    public String boardText() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前棋盘（每行格式：行号｜九个格子，从列a到列i；'．'为空格）：\n");
        sb.append("列   a b c d e f g h i\n");
        for (int r = 0; r <= 9; r++) {
            sb.append("行").append(r).append("｜");
            for (int c = 0; c <= 8; c++) {
                char ch = b[r * 9 + c];
                sb.append(ch == 0 ? '．' : ch);
                if (c < 8) sb.append(' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}