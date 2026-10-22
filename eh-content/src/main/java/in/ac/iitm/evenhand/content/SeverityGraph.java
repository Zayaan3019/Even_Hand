package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.numerics.Cholesky;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Severities from matched pairs: the least-squares answer to "assistant b marked an answer
 * {@code d} points below how assistant a marked an answer saying the same thing".
 *
 * <pre>
 *   minimise  sum_pairs w (d - (s_b - s_a))^2  +  shrinkage * sum_t s_t^2,   sum_t s_t = 0 per group
 * </pre>
 *
 * <p>The assistants are the nodes of a graph and the pairs its edges; only assistants joined
 * by enough pairs are compared, and each connected group is put on a scale of its own, read
 * against its own average, exactly as the marks-only estimator reads a group.
 */
final class SeverityGraph {

    private SeverityGraph() {
    }

    /** Groups of assistants linked, directly or through others, by at least {@code minPairs} pairs. */
    static List<List<Integer>> groups(List<Matching.Pair> pairs, int graders, int minPairs) {
        int[][] count = new int[graders][graders];
        for (Matching.Pair p : pairs) {
            count[p.ga()][p.gb()]++;
            count[p.gb()][p.ga()]++;
        }
        int[] parent = new int[graders];
        for (int g = 0; g < graders; g++) parent[g] = g;
        for (int a = 0; a < graders; a++) {
            for (int b = a + 1; b < graders; b++) {
                if (count[a][b] >= minPairs) union(parent, a, b);
            }
        }
        Map<Integer, List<Integer>> byRoot = new TreeMap<>();
        for (int g = 0; g < graders; g++) byRoot.computeIfAbsent(find(parent, g), r -> new ArrayList<>()).add(g);
        return new ArrayList<>(byRoot.values());
    }

    /**
     * Solves for the severities, each pair's weight multiplied by the product of its two students'
     * {@code multiplier}s (null for the estimate, random for a bootstrap draw).
     */
    static double[] solve(List<Matching.Pair> pairs, List<List<Integer>> groups, int graders,
                          double shrinkage, double[] multiplier) {
        double[][] lap = new double[graders][graders];
        double[] rhs = new double[graders];
        for (Matching.Pair p : pairs) {
            double w = p.weight();
            // A pair is a function of two students, so each carries the pair's whole influence: product
            // weights give a pair statistic its first-order variance; averaging the two would halve it.
            if (multiplier != null) w *= p.sa() == p.sb() ? multiplier[p.sa()] : multiplier[p.sa()] * multiplier[p.sb()];
            int a = p.ga(), b = p.gb();
            lap[a][a] += w;
            lap[b][b] += w;
            lap[a][b] -= w;
            lap[b][a] -= w;
            // d = s_b - s_a: the residual derivative gives +w d to b and -w d to a.
            rhs[b] += w * p.difference();
            rhs[a] -= w * p.difference();
        }
        double[] s = new double[graders];
        for (List<Integer> group : groups) {
            int m = group.size();
            if (m < 2) continue;
            double[][] a = new double[m][m];
            double[] r = new double[m];
            for (int u = 0; u < m; u++) {
                r[u] = rhs[group.get(u)];
                for (int v = 0; v < m; v++) a[u][v] = lap[group.get(u)][group.get(v)] + 1.0;
                a[u][u] += shrinkage;
            }
            double rBar = 0;
            for (double v : r) rBar += v;
            rBar /= m;
            for (int u = 0; u < m; u++) r[u] -= rBar;   // pairs outside the group cannot pull it off centre
            double[] sol = Cholesky.of(a).solve(r);
            double bar = 0;
            for (double v : sol) bar += v;
            bar /= m;
            for (int u = 0; u < m; u++) s[group.get(u)] = sol[u] - bar;
        }
        return s;
    }

    /** Root-mean-square severity within groups of two or more: how much the assistants differ. */
    static double spread(double[] s, List<List<Integer>> groups) {
        double ss = 0;
        int count = 0;
        for (List<Integer> g : groups) {
            if (g.size() < 2) continue;
            for (int j : g) {
                ss += s[j] * s[j];
                count++;
            }
        }
        return count == 0 ? 0 : Math.sqrt(ss / count);
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
