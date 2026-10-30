package com.szpu.algohub.demo;

import java.util.List;

/**
 * 示例数据的固定名单与题目池（只给 demo 配置用）。
 * 人名、账号与通过记录全部是虚构的；题号和题名只为让页面像真的，不保证和源站一一对应。
 */
final class DemoCatalog {

    private DemoCatalog() {
    }

    /** 近 7 天首次通过的名次与题数：前 12 名和设计画布一致，陈一鸣是示例主角。 */
    record Ranked(String name, String className, int weekScore) {
    }

    static final String ME = "陈一鸣";

    static final List<Ranked> TOP_2026 = List.of(
            new Ranked("周子航", "计应 1 班", 31),
            new Ranked("林小北", "软件 2 班", 26),
            new Ranked("王思雨", "计应 1 班", 22),
            new Ranked("李想", "人工智能 1 班", 19),
            new Ranked("赵可欣", "计应 2 班", 17),
            new Ranked("孙浩然", "软件 1 班", 15),
            new Ranked("黄诗琪", "计应 1 班", 14),
            new Ranked(ME, "计应 1 班", 12),
            new Ranked("吴俊杰", "大数据 1 班", 11),
            new Ranked("郑雨桐", "软件 2 班", 11),
            new Ranked("何子墨", "计应 2 班", 9),
            new Ranked("刘一凡", "人工智能 1 班", 8));

    static final String[] CLASSES_2026 = {"计应 1 班", "计应 2 班", "软件 1 班", "软件 2 班", "人工智能 1 班", "大数据 1 班"};

    /** 2026 级其余 48 人，近 7 天题数从 7 到 0，每档 6 人。 */
    static final String[] OTHER_2026 = {
            "高子涵", "杨雨萱", "马浩宇", "朱思远", "胡嘉怡", "郭俊熙", "何雨泽", "罗梓萱",
            "梁晨阳", "宋佳琪", "谢子墨", "韩宇航", "唐诗雨", "冯博文", "于欣怡", "董浩然",
            "萧子轩", "程雨桐", "曹宇辰", "袁梦瑶", "邓嘉豪", "许晨曦", "傅子健", "沈佳慧",
            "曾宇轩", "彭思琪", "吕浩天", "苏雨欣", "卢俊杰", "蒋梓涵", "蔡宇航", "贾欣妍",
            "丁子豪", "魏雨婷", "薛晨宇", "叶思涵", "阎浩宇", "余嘉乐", "潘雨晴", "杜子轩",
            "戴佳怡", "夏明哲", "钟雅琪", "汪俊宇", "田思彤", "任博涛", "姜雨菲", "范子睿"};

    /** 关了「参与校内排行」或档案仅自己可见的同届同学：有数据，但不进榜单和动态。 */
    static final String[] HIDDEN_2026 = {"黎明轩", "常雨欣"};

    static final String[] NAMES_2025 = {
            "方宇晨", "石佳宁", "姚子昂", "谭雨涵", "廖俊豪", "邹思琪", "熊浩博", "金雨轩",
            "陆子怡", "郝晨阳", "孔嘉欣", "白宇泽", "崔梓萌", "康浩宇", "毛思远"};

    static final String[] NAMES_2024 = {
            "邱子墨", "秦雨萌", "江俊熙", "史佳怡", "顾宇航", "侯梓轩", "邵雨桐", "孟浩然", "龙思涵", "万子健"};

    static final String[] CLASSES_OLDER = {"计应 1 班", "软件 1 班", "人工智能 1 班", "大数据 1 班"};

    static final String[] CF_TITLES = {
            "Odd One Out", "Two Buttons", "Array Coloring", "Binary Cut", "Lucky Numbers", "Minimize the Difference",
            "Equal Candies", "Short Substrings", "Plus One on the Subset", "Spell Check", "Word Game",
            "Make It Increasing", "Palindrome Basis", "Sum of Round Numbers", "Game with Doors", "Card Game",
            "Robot on the Board", "Division into Groups", "Balanced Remainders", "Three Piles of Candies",
            "Unique Bid Auction", "Collecting Coins", "Frog Jumping", "Maximum GCD", "Different Divisors",
            "Strange Partition", "Mean Inequality", "Red-Blue Shuffle", "Shortest Path with Obstacle",
            "Colorful Stones", "Array Rearrangement", "Boring Apartments", "Favorite Sequence",
            "Last Year's Substring", "Special Permutation", "Distinct Split", "Sum of Odd Integers",
            "Candies and Two Sisters", "Minimal Square", "Honest Coach", "Even Subset Sum Problem",
            "Remove Smallest", "Yes-Yes?", "Cover in Water", "Grasshopper on a Line", "Tricky Template",
            "Walking Master", "Garland", "Matrix Rotation", "Prepend and Append", "Sequence Game",
            "Everyone Loves to Sleep", "Two Elevators", "Line Trip", "Forked!", "Parity Sort", "Target Practice",
            "Morning", "Buttons", "Unit Array", "Blank Space", "Coins", "Desorting", "Round Down the Price",
            "Two Rival Students", "Minutes Before the New Year", "Shifting Stacks", "Replacing Elements",
            "Two Distinct Points", "Strong Password", "Gift Carpet", "Arrow Path", "Brick Wall",
            "Stair, Peak, or Neither?", "Recent Actions", "Array with Odd Sum", "Minimize Distance",
            "Split the Multiset", "Increasing Sequence", "Filling Shapes", "Prefix Flip", "Book Reading",
            "Card Constructions", "Subtract or Divide", "Circle Coloring", "Symmetric Matrix", "Road To Zero",
            "Most Unstable Array", "Hilbert's Hotel", "Anti-Fibonacci Permutation", "Nearest Beautiful Number",
            "Paint the Numbers", "Three Indices", "Maximum Increase", "Queue at the School", "Team Olympiad",
            "Tree Infection", "Subsequence Addition", "Permutation Swap", "Queries for the Array",
            "Balanced Tunnel", "Ball in Berland", "Painting the Ribbon", "Maximum Sum", "Chips on the Board",
            "Swap and Delete", "Sum on Subarrays", "Removal of Unattractive Pairs", "Tree Queries",
            "Two Sets", "Coin Rows", "Dominant Piranha", "Different Neighbours", "Good Array", "Knapsack",
            "Divisibility Problem", "Collecting Packages", "Omkar and Password", "Permutation Chain",
            "Monsters", "Segment Covering", "Longest Divisors Interval", "Tenzing and Books", "Mex Tree",
            "Zero Path", "Rudolf and the Ticket", "Vlad and Candies", "Elections", "Ordinary Numbers",
            "Wonderful Coloring", "Interesting Drink", "Kill the Monster", "Equidistant Letters",
            "Minimum Extraction", "Weird Sum", "Positive Integers", "Array Cloning", "Tokitsukaze and Good 01-String",
            "Reverse Sort", "Cyclic Shifts", "Guess the Maximum", "Shuffle Party", "Binary Imbalance",
            "Deep Down Below", "Make it Beautiful", "Array Fix", "Chess For Three", "Tricky Sum",
            "Common Divisors", "Paint the Tree", "Graph and Queries", "Range Update Point Query"};

    static final String[] ATCODER_TITLES = {
            "Leap Year", "Happy Birthday!", "Count Takahashi", "Buildings", "Past ABCs", "Echo",
            "Last Letter", "Strictly Increasing?", "Water Station", "Glass and Mug", "Ideal Holidays",
            "Arithmetic Progression", "Three Threes", "Integer Division Returns", "Measure", "Uppercase and Lowercase",
            "Many Replacement", "Election 2", "Sanitize Hands", "Cut", "Pairs", "Remove Pairs", "Tile Distance",
            "Sum of Numbers Greater Than Me", "Adjacent Product", "Piano", "Frequency Table", "Takahashi san",
            "Snuke Panic", "Default Price", "Rotate Colored Subsequence", "Standings", "Nearest Taller",
            "Merge the balls", "Minimize Abs", "Sum of Min of Length", "Shortest Path 3", "Grid Walk",
            "AtCoder Magics", "Count Arithmetic Subsequences", "Buy One Carton of Milk", "Divide and Divide",
            "Island Tour", "Complete Binary Tree", "Weighted Sum", "Road Blocked", "Delimiter",
            "Balls and Bag Query", "Couples", "Mex and Update", "Max Ai+Bj", "Keys", "Magical Cookies",
            "Chords", "Colored Ball", "Minimum Width", "Takahashi Gets Lost", "Loong Tracking", "Separated Lunch"};

    /** 洛谷入门题（B 开头的题号），难度 1。 */
    static final String[] LUOGU_B_TITLES = {
            "Hello, World!", "输出第二个整数", "对齐输出", "输出保留 3 位小数的浮点数", "空格分隔输出",
            "计算 (a+b)×c 的值", "带余除法", "计算分数的浮点数值", "甲流疫情死亡率", "计算多项式的值",
            "温度表达转化", "与圆相关的计算", "计算并联电阻的阻值", "计算浮点数相除的余数", "计算球的体积",
            "大象喝水", "苹果和虫子", "奇偶数判断", "正数、负数还是零", "判断数是否为两位数", "收集瓶盖赢大奖",
            "判断一个数能否同时被 3 和 5 整除", "判断能否被 3,5,7 整除", "有一门课不及格的学生", "晶晶赴约会",
            "骑车与走路", "分段函数", "计算邮资", "最大数输出", "三角形判断", "判断闰年", "点和正方形的关系",
            "简单计算器", "求整数的和与均值", "整数序列的元素最大跨度值", "奥运奖牌计数", "鸡尾酒疗法",
            "角谷猜想", "数字反转", "含 k 个 3 的数", "开关灯", "质因数分解", "斐波那契数列", "求阶乘的和",
            "统计满足条件的 4 位数", "药房管理", "人口增长问题", "球弹跳高度的计算", "与 7 无关的数的平方和",
            "金币", "计算书费", "校园运动会", "统计数字字符个数", "找第一个只出现一次的字符", "单词翻转",
            "字符串 p 型编码", "最长最短单词", "单词替换", "矩阵转置", "图像模糊处理"};

    /** 洛谷普通题：题号、题名、难度编号（0～8）、标签（都在 V14 的分类映射里）。 */
    static final String[][] LUOGU_P = {
            {"P1000", "超级玛丽游戏", "1", "模拟"},
            {"P1001", "A+B Problem", "1", "模拟"},
            {"P1002", "[NOIP2002 普及组] 过河卒", "3", "动态规划 DP"},
            {"P1003", "[NOIP2011 提高组] 铺地毯", "2", "模拟"},
            {"P1008", "[NOIP1998 普及组] 三连击", "2", "枚举"},
            {"P1009", "[NOIP1998 普及组] 阶乘之和", "2", "高精度"},
            {"P1028", "[NOIP2001 普及组] 数的计算", "2", "递推"},
            {"P1036", "[NOIP2002 普及组] 选数", "2", "搜索"},
            {"P1047", "[NOIP2005 普及组] 校门外的树", "1", "模拟"},
            {"P1048", "[NOIP2005 普及组] 采药", "2", "背包 DP"},
            {"P1055", "[NOIP2008 普及组] ISBN 号码", "1", "字符串"},
            {"P1059", "[NOIP2006 普及组] 明明的随机数", "1", "排序"},
            {"P1067", "[NOIP2009 普及组] 多项式输出", "2", "模拟"},
            {"P1085", "[NOIP2004 普及组] 不高兴的津津", "1", "模拟"},
            {"P1089", "[NOIP2004 提高组] 津津的储蓄计划", "1", "模拟"},
            {"P1090", "[NOIP2004 提高组] 合并果子", "3", "贪心"},
            {"P1102", "A-B 数对", "3", "二分"},
            {"P1115", "最大子段和", "3", "线性 DP"},
            {"P1177", "【模板】排序", "2", "排序"},
            {"P1179", "[NOIP2010 普及组] 数字统计", "1", "枚举"},
            {"P1216", "[IOI1994] 数字三角形 Number Triangles", "2", "线性 DP"},
            {"P1219", "[USACO1.5] 八皇后 Checker Challenge", "3", "深度优先搜索 DFS"},
            {"P1223", "排队接水", "2", "贪心"},
            {"P1226", "【模板】快速幂", "3", "数学"},
            {"P1255", "数楼梯", "2", "高精度"},
            {"P1271", "【深基9.例1】选举学生会", "1", "排序"},
            {"P1303", "A*B Problem", "2", "高精度"},
            {"P1328", "[NOIP2014 提高组] 生活大爆炸版石头剪刀布", "2", "模拟"},
            {"P1443", "马的遍历", "3", "广度优先搜索 BFS"},
            {"P1464", "Function", "3", "记忆化搜索"},
            {"P1601", "A+B Problem（高精）", "1", "高精度"},
            {"P1605", "迷宫", "2", "深度优先搜索 DFS"},
            {"P1618", "三连击（升级版）", "2", "枚举"},
            {"P1720", "月落乌啼算钱（斐波那契数列）", "1", "递推"},
            {"P1739", "表达式括号匹配", "2", "栈"},
            {"P1873", "[COCI 2011/2012 #5] EKO / 砍树", "3", "二分"},
            {"P1909", "[NOIP2016 普及组] 买铅笔", "1", "模拟"},
            {"P1980", "[NOIP2013 普及组] 计数问题", "1", "枚举"},
            {"P2004", "领地选择", "3", "前缀和"},
            {"P2141", "[NOIP2014 普及组] 珠心算测验", "2", "枚举"},
            {"P2249", "【深基13.例1】查找", "2", "二分"},
            {"P3366", "【模板】最小生成树", "4", "生成树"},
            {"P3367", "【模板】并查集", "4", "并查集"},
            {"P3371", "【模板】单源最短路径（弱化版）", "4", "最短路"},
            {"P3374", "【模板】树状数组 1", "4", "树状数组"},
            {"P3375", "【模板】KMP", "4", "KMP 算法"},
            {"P3378", "【模板】堆", "3", "堆"},
            {"P3954", "[NOIP2017 普及组] 成绩", "1", "模拟"},
            {"P5015", "[NOIP2018 普及组] 标题统计", "1", "字符串"},
            {"P5660", "[CSP-J2019] 数字游戏", "1", "字符串"},
            {"P5707", "【深基2.例12】上学迟到", "1", "模拟"},
            {"P5708", "【深基2.习2】三角形面积", "1", "数学"},
            {"P5710", "【深基3.例2】数的性质", "1", "分支结构"},
            {"P5711", "【深基3.例3】闰年判断", "1", "分支结构"},
            {"P5712", "【深基3.例4】Apples", "1", "分支结构"},
            {"P5713", "【深基3.例5】洛谷团队系统", "1", "分支结构"},
            {"P5714", "【深基3.例7】肥胖问题", "1", "分支结构"},
            {"P5715", "【深基3.例8】三位数排序", "1", "排序"},
            {"P5716", "【深基3.例9】月份天数", "1", "分支结构"},
            {"P5717", "【深基3.习8】三角形分类", "1", "分支结构"},
            {"P5718", "【深基4.例2】找最小值", "1", "循环结构"},
            {"P5719", "【深基4.例3】分类平均", "1", "循环结构"},
            {"P5720", "【深基4.例4】一尺之棰", "1", "循环结构"},
            {"P5721", "【深基4.例6】数字直角三角形", "1", "循环结构"},
            {"P5722", "【深基4.例11】数列求和", "1", "循环结构"},
            {"P5723", "【深基4.例13】质数口袋", "2", "素数判断"},
            {"P7071", "[CSP-J2020] 优秀的拆分", "2", "位运算"},
            {"P8813", "[CSP-J 2022] 乘方", "1", "数学"}};

    /** 力扣题：URL 用的 slug、页面上显示的「题号. 题名」（与适配器的写法一致）。 */
    static final String[][] LEETCODE = {
            {"two-sum", "1. 两数之和"},
            {"longest-substring-without-repeating-characters", "3. 无重复字符的最长子串"},
            {"longest-palindromic-substring", "5. 最长回文子串"},
            {"container-with-most-water", "11. 盛最多水的容器"},
            {"3sum", "15. 三数之和"},
            {"valid-parentheses", "20. 有效的括号"},
            {"merge-two-sorted-lists", "21. 合并两个有序链表"},
            {"permutations", "46. 全排列"},
            {"maximum-subarray", "53. 最大子数组和"},
            {"merge-intervals", "56. 合并区间"},
            {"climbing-stairs", "70. 爬楼梯"},
            {"subsets", "78. 子集"},
            {"binary-tree-inorder-traversal", "94. 二叉树的中序遍历"},
            {"maximum-depth-of-binary-tree", "104. 二叉树的最大深度"},
            {"best-time-to-buy-and-sell-stock", "121. 买卖股票的最佳时机"},
            {"single-number", "136. 只出现一次的数字"},
            {"linked-list-cycle", "141. 环形链表"},
            {"min-stack", "155. 最小栈"},
            {"majority-element", "169. 多数元素"},
            {"number-of-islands", "200. 岛屿数量"},
            {"reverse-linked-list", "206. 反转链表"},
            {"palindrome-linked-list", "234. 回文链表"},
            {"move-zeroes", "283. 移动零"},
            {"longest-increasing-subsequence", "300. 最长递增子序列"},
            {"coin-change", "322. 零钱兑换"},
            {"binary-search", "704. 二分查找"},
            {"daily-temperatures", "739. 每日温度"}};

    static final String[] NOWCODER_TITLES = {
            "小红的字符串", "小苯的数组", "小美的回文串", "游游的排列", "小红的数组构造", "小苯的 ovo",
            "小美的因子", "小红的好数", "牛牛的签到题", "小红的矩阵", "小苯的区间和", "游游的最小公倍数",
            "小红的奇偶排序", "小美的连续子数组", "小苯送礼物", "小红的树", "游游的字母串", "小美的排队",
            "小红的最大公约数", "小苯的计算器", "牛妹的蛋糕", "小红的回文子串", "小美的数组删除", "游游的整数翻转"};

    /** CF 标签按难度分层取，都是 V2 里有分类映射的原始标签。 */
    static final String[] CF_TAGS_EASY = {"implementation", "math", "greedy", "brute force", "strings", "sortings",
            "constructive algorithms"};
    static final String[] CF_TAGS_MID = {"greedy", "math", "binary search", "number theory", "two pointers", "dp",
            "constructive algorithms", "sortings", "bitmasks", "data structures"};
    static final String[] CF_TAGS_HARD = {"dp", "graphs", "data structures", "dfs and similar", "trees",
            "shortest paths", "dsu", "combinatorics", "binary search", "games"};
    /** 故意没有分类映射的标签：管理后台「标签分类」里要有待处理的项。 */
    static final String[] CF_TAGS_UNMAPPED = {"*special", "ternary search", "schedules", "expression parsing"};
}
