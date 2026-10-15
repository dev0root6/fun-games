package com.neonmaze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;

/**
 * Same generator as the terminal version: recursive backtracker, then braided so it has loops.
 * Fully deterministic for a given seed, so every device in a LAN race builds the same maze.
 */
final class Maze {
    final int W, H;
    final byte[] g;
    final int[] originX, originY, par;
    final int exitX, exitY;
    final int[] distExit;
    final HashSet<Integer> gems = new HashSet<>();
    final HashSet<Integer> powers = new HashSet<>();
    final int totalGems;

    Maze(int cw, int ch, long seed, boolean multi) {
        Random rng = new Random(seed);
        W = 2 * cw + 1;
        H = 2 * ch + 1;
        g = new byte[W * H];
        java.util.Arrays.fill(g, (byte) 1);
        generate(cw, ch, rng);

        if (multi) {
            // four corners, exit placed where all corners are as equally far as possible
            originX = new int[]{1, W - 2, 1, W - 2};
            originY = new int[]{1, H - 2, H - 2, 1};
            int[][] d = new int[4][];
            for (int i = 0; i < 4; i++) d[i] = bfs(originX[i], originY[i]);
            int best = -1, bestCost = Integer.MAX_VALUE;
            for (int y = 1; y < H; y += 2) {
                for (int x = 1; x < W; x += 2) {
                    int k = y * W + x, mn = Integer.MAX_VALUE, mx = 0;
                    for (int i = 0; i < 4; i++) {
                        mn = Math.min(mn, d[i][k]);
                        mx = Math.max(mx, d[i][k]);
                    }
                    if (mn <= 0) continue;
                    int cost = (mx - mn) * 4 - mn;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = k;
                    }
                }
            }
            exitX = best % W;
            exitY = best / W;
        } else {
            originX = new int[]{1};
            originY = new int[]{1};
            int[] d = bfs(1, 1);
            int best = 0;
            for (int k = 0; k < d.length; k++) if (d[k] > d[best]) best = k;
            exitX = best % W;
            exitY = best / W;
        }
        distExit = bfs(exitX, exitY);
        par = new int[originX.length];
        for (int i = 0; i < par.length; i++) par[i] = distExit[originY[i] * W + originX[i]];

        ArrayList<Integer> cells = new ArrayList<>();
        for (int y = 1; y < H; y += 2) {
            for (int x = 1; x < W; x += 2) {
                boolean taken = x == exitX && y == exitY;
                for (int i = 0; i < originX.length; i++) taken |= x == originX[i] && y == originY[i];
                if (!taken) cells.add(y * W + x);
            }
        }
        Collections.shuffle(cells, rng);
        int nGems = Math.max(3, cw * ch / 10);
        int nPow = Math.max(1, cw * ch / 60);
        for (int i = 0; i < nGems && i < cells.size(); i++) gems.add(cells.get(i));
        for (int i = nGems; i < nGems + nPow && i < cells.size(); i++) powers.add(cells.get(i));
        totalGems = gems.size();
    }

    private void generate(int cw, int ch, Random rng) {
        g[W + 1] = 0;
        boolean[] seen = new boolean[cw * ch];
        int[] stack = new int[cw * ch];
        int sp = 0;
        stack[sp++] = 0;
        seen[0] = true;
        int[] nbr = new int[4];
        int[] ddx = {1, -1, 0, 0}, ddy = {0, 0, 1, -1};
        while (sp > 0) {
            int cur = stack[sp - 1], cx = cur % cw, cy = cur / cw, n = 0;
            for (int i = 0; i < 4; i++) {
                int nx = cx + ddx[i], ny = cy + ddy[i];
                if (nx >= 0 && nx < cw && ny >= 0 && ny < ch && !seen[ny * cw + nx]) nbr[n++] = i;
            }
            if (n == 0) {
                sp--;
                continue;
            }
            int i = nbr[rng.nextInt(n)];
            int nx = cx + ddx[i], ny = cy + ddy[i];
            g[(2 * cy + 1 + ddy[i]) * W + 2 * cx + 1 + ddx[i]] = 0;
            g[(2 * ny + 1) * W + 2 * nx + 1] = 0;
            seen[ny * cw + nx] = true;
            stack[sp++] = ny * cw + nx;
        }
        for (int i = 0; i < cw * ch / 10; i++) {
            int x = 1 + rng.nextInt(W - 2), y = 1 + rng.nextInt(H - 2);
            if (g[y * W + x] == 1 && (x % 2) != (y % 2)) g[y * W + x] = 0;
        }
    }

    boolean wall(int x, int y) {
        return x < 0 || y < 0 || x >= W || y >= H || g[y * W + x] == 1;
    }

    int[] bfs(int sx, int sy) {
        int[] d = new int[W * H];
        java.util.Arrays.fill(d, -1);
        int[] q = new int[W * H];
        int head = 0, tail = 0;
        d[sy * W + sx] = 0;
        q[tail++] = sy * W + sx;
        while (head < tail) {
            int k = q[head++], x = k % W, y = k / W;
            int[] ns = {k + 1, k - 1, k + W, k - W};
            int[] xs = {x + 1, x - 1, x, x};
            int[] ys = {y, y, y + 1, y - 1};
            for (int i = 0; i < 4; i++) {
                if (!wall(xs[i], ys[i]) && d[ns[i]] < 0) {
                    d[ns[i]] = d[k] + 1;
                    q[tail++] = ns[i];
                }
            }
        }
        return d;
    }
}
