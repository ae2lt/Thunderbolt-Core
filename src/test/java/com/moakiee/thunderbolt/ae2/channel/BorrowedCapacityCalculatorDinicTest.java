package com.moakiee.thunderbolt.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class BorrowedCapacityCalculatorDinicTest {

    @Test
    void solvesKnownNetworkWithSeveralAugmentingPaths() {
        var graph = new BorrowedCapacityCalculator.Dinic(6);
        graph.addEdge(0, 1, 16);
        graph.addEdge(0, 2, 13);
        graph.addEdge(1, 2, 10);
        graph.addEdge(2, 1, 4);
        graph.addEdge(1, 3, 12);
        graph.addEdge(3, 2, 9);
        graph.addEdge(2, 4, 14);
        graph.addEdge(4, 3, 7);
        graph.addEdge(3, 5, 20);
        graph.addEdge(4, 5, 4);

        assertEquals(23, graph.maxFlow(0, 5, Integer.MAX_VALUE));
    }

    @Test
    void randomizedGraphsMatchIndependentEdmondsKarpReference() {
        var random = new Random(0x5448554E444552L);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            int size = 2 + random.nextInt(10);
            int[][] capacities = new int[size][size];
            var graph = new BorrowedCapacityCalculator.Dinic(size);

            for (int from = 0; from < size; from++) {
                for (int to = 0; to < size; to++) {
                    if (from == to || random.nextInt(4) != 0) continue;
                    int capacity = 1 + random.nextInt(32);
                    capacities[from][to] += capacity;
                    graph.addEdge(from, to, capacity);
                }
            }

            int demandCap = 1 + random.nextInt(128);
            int expected = Math.min(demandCap, edmondsKarp(capacities, 0, size - 1));
            assertEquals(expected, graph.maxFlow(0, size - 1, demandCap),
                    "random graph iteration " + iteration);
        }
    }

    @Test
    void deepChannelChainDoesNotUseTheJvmCallStack() {
        int size = 100_000;
        var graph = new BorrowedCapacityCalculator.Dinic(size);
        for (int node = 0; node < size - 1; node++) {
            graph.addEdge(node, node + 1, 1);
        }

        assertEquals(1, graph.maxFlow(0, size - 1, 1));
    }

    private static int edmondsKarp(int[][] original, int source, int sink) {
        int size = original.length;
        int[][] residual = new int[size][size];
        for (int i = 0; i < size; i++) {
            residual[i] = Arrays.copyOf(original[i], size);
        }

        int total = 0;
        int[] parent = new int[size];
        while (true) {
            Arrays.fill(parent, -1);
            parent[source] = source;
            var queue = new ArrayDeque<Integer>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] < 0) {
                int from = queue.removeFirst();
                for (int to = 0; to < size; to++) {
                    if (parent[to] < 0 && residual[from][to] > 0) {
                        parent[to] = from;
                        queue.addLast(to);
                    }
                }
            }
            if (parent[sink] < 0) return total;

            int pushed = Integer.MAX_VALUE;
            for (int to = sink; to != source; to = parent[to]) {
                pushed = Math.min(pushed, residual[parent[to]][to]);
            }
            for (int to = sink; to != source; to = parent[to]) {
                int from = parent[to];
                residual[from][to] -= pushed;
                residual[to][from] += pushed;
            }
            total += pushed;
        }
    }
}
