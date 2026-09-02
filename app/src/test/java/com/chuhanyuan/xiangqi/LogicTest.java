package com.chuhanyuan.xiangqi;

/** 纯JVM测试：不带android依赖的两个类直接new。失败即exit(1)。 */
public class LogicTest {
    static int passed = 0;

    static void check(String name, boolean cond) {
        if (!cond) {
            System.out.println("FAIL: " + name);
            System.exit(1);
        }
        passed++;
        System.out.println("PASS: " + name);
    }

    public static void main(String[] args) {
        GameLogic g = new GameLogic();
        g.reset();

        // 初始阵型完整性：32子、将帅各在原位
        int count = 0;
        for (int r = 0; r <= 9; r++)
            for (int c = 0; c <= 8; c++)
                if (g.pieceAt(r, c) != 0) count++;
        check("初始32子", count == 32);
        check("红帅在e0", g.pieceAt(0, 4) == '帥');
        check("黑将在e9", g.pieceAt(9, 4) == '將');
        check("红马列位正确", g.pieceAt(0, 1) == '傌' && g.pieceAt(0, 7) == '傌');

        // 物理移动：起点有子→移动成功，原格清空
        char cap = g.move(0, 4, 5, 4); // 帅直接飞中线（无规则盘：合法）
        check("移动后原格清空", g.pieceAt(0, 4) == 0);
        check("移动后新格有子", g.pieceAt(5, 4) == '帥');
        check("空格移动无吃子", cap == 0);

        // 吃子：走进对方格子，返回被吃子
        char cap2 = g.move(9, 4, 5, 4); // 黑将吃红帅
        check("吃子返回被吃子", cap2 == '帥');
        check("被吃方原格清空", g.pieceAt(9, 4) == 0);
        check("吃子方占格", g.pieceAt(5, 4) == '將');

        // put：战利品拿回
        g.put(9, 4, '帥');
        check("put放回棋盘", g.pieceAt(9, 4) == '帥');

        // 越界保护
        g.put(10, 0, '兵');
        check("put越界不崩溃不改盘", g.pieceAt(10, 0) == 0);
        check("pieceAt越界返回空", g.pieceAt(-1, 0) == 0);

        // 棋盘文字快照：含行号和子
        String bt = g.boardText();
        check("快照含行9", bt.contains("行9"));
        check("快照含黑将", bt.contains("將"));

        // AiService坐标解析：标准工具调用
        String r1 = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"1\",\"type\":\"function\",\"function\":{\"name\":\"move_piece\",\"arguments\":\"{\\\"from\\\":\\\"b2\\\",\\\"to\\\":\\\"e4\\\",\\\"say\\\":\\\"看招\\\"}\"}}]}}]}";
        AiService.Move m1 = AiService.firstMove(r1);
        check("工具调用解析出move", m1 != null);
        check("from=b2", m1 != null && "b2".equals(m1.from));
        check("to=e4", m1 != null && "e4".equals(m1.to));
        check("say含话", m1 != null && "看招".equals(m1.say));
        check("坐标换算", m1 != null && m1.fc == 1 && m1.fr == 2 && m1.tc == 4 && m1.tr == 4);

        // 大写坐标
        String r2 = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"from\\\":\\\"B2\\\",\\\"to\\\":\\\"E4\\\",\\\"say\\\":\\\"\\\"}\"}}]}}]}";
        AiService.Move m2 = AiService.firstMove(r2);
        check("大写坐标归一", m2 != null && "b2".equals(m2.from) && "".equals(m2.say));

        // 非法坐标→null（物理层拦截）
        String r3 = "{\"function\":{\"arguments\":\"{\\\"from\\\":\\\"z9\\\",\\\"to\\\":\\\"e4\\\",\\\"say\\\":\\\"\\\"}\"}}";
        check("越界坐标拒绝", AiService.firstMove(r3) == null);

        // 纯聊天：无tool_calls
        String r4 = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你这步妙啊\"}}]}";
        check("纯聊天无move", AiService.firstMove(r4) == null);
        check("纯聊天取content", "你这步妙啊".equals(AiService.content(r4)));

        // 空盘摆子：初始规格无撞格
        String[] specs = GameLogic.initialSpecs();
        check("初始规格32条", specs.length == 32);
        GameLogic g2 = new GameLogic();
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean noClash = true;
        for (String s : specs) {
            String[] p = s.split(",");
            noClash &= seen.add(p[1] + "," + p[2]);
        }
        check("初始规格无撞格", noClash);

        System.out.println("ALL " + passed + " TESTS PASSED");
    }
}
