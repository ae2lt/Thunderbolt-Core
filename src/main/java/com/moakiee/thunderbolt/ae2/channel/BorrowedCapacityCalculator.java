package com.moakiee.thunderbolt.ae2.channel;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BorrowedCapacityCalculator {
   private static final Logger LOG = LoggerFactory.getLogger("ae2lt-maxflow");
   private static final int INF = 1073741823;
   public static volatile Reference2IntOpenHashMap<IGridNode> activeNodeFlow;
   public static volatile Set<IGridNode> activeNetworkNodes;
   public static volatile Reference2IntOpenHashMap<GridConnection> activeConnectionFlow;

   private BorrowedCapacityCalculator() {
   }

   public static void clearActiveData() {
      activeNodeFlow = null;
      activeNetworkNodes = null;
      activeConnectionFlow = null;
   }

   public static BorrowedCapacityCalculator.Result assignChannels(IGrid grid, List<IGridNode> overloadedControllers) {
      ChannelMode channelMode = grid.getPathingService().getChannelMode();
      if (channelMode == ChannelMode.INFINITE) {
         return null;
      } else {
         Set<IGridNode> network = discoverNetwork(grid, overloadedControllers);
         return solve(grid, overloadedControllers, network, channelMode);
      }
   }

   private static Set<IGridNode> discoverNetwork(IGrid grid, List<IGridNode> overloadedControllers) {
      Set<IGridNode> nodes = new ReferenceOpenHashSet();
      Queue<IGridNode> q = new ArrayDeque<>();

      for (IGridNode oc : overloadedControllers) {
         nodes.add(oc);
         q.add(oc);
      }

      for (IGridNode vc : OverloadedChannelOwnerHelper.getAllControllerNodes(grid)) {
         if (!ChannelProviderRegistry.isChannelProvider(vc.getOwner())) {
            for (IGridConnection c : vc.getConnections()) {
               if (c instanceof GridConnection) {
                  GridConnection gc = (GridConnection)c;
                  IGridNode other = gc.getOtherSide(vc);
                  if (!(other.getOwner() instanceof ControllerBlockEntity)) {
                     tryEnqueue(other, nodes, q);
                  }
               }
            }
         }
      }

      while (!q.isEmpty()) {
         IGridNode cur = q.poll();

         for (IGridConnection cx : cur.getConnections()) {
            if (cx instanceof GridConnection gc) {
               IGridNode other = gc.getOtherSide(cur);
               tryEnqueue(other, nodes, q);
            }
         }
      }

      return nodes;
   }

   private static void tryEnqueue(IGridNode other, Set<IGridNode> nodes, Queue<IGridNode> q) {
      if (!nodes.contains(other)) {
         if (other.getOwner() instanceof ControllerBlockEntity) {
            if (ChannelProviderRegistry.isChannelProvider(other.getOwner())) {
               nodes.add(other);
               q.add(other);
            }
         } else {
            if (other instanceof GridNode gn && gn.hasFlag(GridFlags.CANNOT_CARRY)) {
               if (gn.hasFlag(GridFlags.REQUIRE_CHANNEL)) {
                  nodes.add(other);
               }

               return;
            }

            nodes.add(other);
            q.add(other);
         }
      }
   }

   private static BorrowedCapacityCalculator.Result solve(IGrid grid, List<IGridNode> overloadedControllers, Set<IGridNode> network, ChannelMode mode) {
      Reference2IntOpenHashMap<IGridNode> idx = new Reference2IntOpenHashMap();
      idx.defaultReturnValue(-1);
      int i = 0;

      for (IGridNode n : network) {
         idx.put(n, i++);
      }

      int total = network.size();
      int S = 2 * total;
      int T = 2 * total + 1;
      BorrowedCapacityCalculator.Dinic dinic = new BorrowedCapacityCalculator.Dinic(2 * total + 2);
      int[] splitEdge = new int[total];

      for (IGridNode n : network) {
         int ci = idx.getInt(n);
         int cap = nodeCap(n, mode);
         splitEdge[ci] = dinic.edgeCount();
         dinic.addEdge(2 * ci, 2 * ci + 1, cap);
      }

      record ConnEdge(GridConnection gc, int edgeAB, int edgeBA, int cap) {
      }

      List<ConnEdge> connEdges = new ArrayList<>();
      Set<GridConnection> seen = new ReferenceOpenHashSet();

      for (IGridNode n : network) {
         int ci = idx.getInt(n);

         for (IGridConnection c : n.getConnections()) {
            if (c instanceof GridConnection) {
               GridConnection gc = (GridConnection)c;
               IGridNode other = gc.getOtherSide(n);
               int oi = idx.getInt(other);
               if (oi >= 0 && seen.add(gc)) {
                  int edgeCap = getConnectionCap(gc, n, other, mode);
                  int eAB = dinic.edgeCount();
                  dinic.addEdge(2 * ci + 1, 2 * oi, edgeCap);
                  int eBA = dinic.edgeCount();
                  dinic.addEdge(2 * oi + 1, 2 * ci, edgeCap);
                  connEdges.add(new ConnEdge(gc, eAB, eBA, edgeCap));
               }
            }
         }
      }

      int supply = OverloadedChannelOwnerHelper.supplyPerController(mode.getCableCapacityFactor());

      for (IGridNode oc : overloadedControllers) {
         int ci = idx.getInt(oc);
         dinic.addEdge(S, 2 * ci, supply);
      }

      int faceCap = 32 * mode.getCableCapacityFactor();

      record FaceEdge(GridConnection gc, int edgeIdx, int cap) {
      }

      List<FaceEdge> faceEdges = new ArrayList<>();

      for (IGridNode node : OverloadedChannelOwnerHelper.getAllControllerNodes(grid)) {
         if (!ChannelProviderRegistry.isChannelProvider(node.getOwner())) {
            for (IGridConnection cx : node.getConnections()) {
               if (cx instanceof GridConnection) {
                  GridConnection gc = (GridConnection)cx;
                  IGridNode other = gc.getOtherSide(node);
                  if (!(other.getOwner() instanceof ControllerBlockEntity)) {
                     int oi = idx.getInt(other);
                     if (oi >= 0) {
                        int feIdx = dinic.edgeCount();
                        dinic.addEdge(S, 2 * oi, faceCap);
                        faceEdges.add(new FaceEdge(gc, feIdx, faceCap));
                     }
                  }
               }
            }
         }
      }

      Set<IGridNode> multiblockSkip = new ReferenceOpenHashSet();

      for (IGridNode n : network) {
         if (n instanceof GridNode) {
            GridNode gn = (GridNode)n;
            if (gn.hasFlag(GridFlags.REQUIRE_CHANNEL) && gn.hasFlag(GridFlags.MULTIBLOCK) && !multiblockSkip.contains(n)) {
               IGridMultiblock multiblock = (IGridMultiblock)n.getService(IGridMultiblock.class);
               if (multiblock != null) {
                  Iterator<IGridNode> siblings = multiblock.getMultiblockNodes();

                  while (siblings.hasNext()) {
                     IGridNode sibling = siblings.next();
                     if (sibling != n && idx.getInt(sibling) >= 0) {
                        multiblockSkip.add(sibling);
                     }
                  }
               }
            }
         }
      }

      List<IGridNode> sinkNodes = new ArrayList<>();
      IntList sinkEdgeIndices = new IntArrayList();

      for (IGridNode nx : network) {
         if (nx instanceof GridNode) {
            GridNode gn = (GridNode)nx;
            if (gn.hasFlag(GridFlags.REQUIRE_CHANNEL) && !multiblockSkip.contains(nx)) {
               int ci = idx.getInt(nx);
               sinkEdgeIndices.add(dinic.edgeCount());
               dinic.addEdge(2 * ci + 1, T, 1);
               sinkNodes.add(nx);
            }
         }
      }

      int maxFlow = dinic.maxFlow(S, T, sinkNodes.size());
      LOG.debug(
         "maxFlow={}, network={}, sinks={}, overloadedCtrl={}, supply/ctrl={}",
         new Object[]{maxFlow, network.size(), sinkNodes.size(), overloadedControllers.size(), supply}
      );
      Set<GridNode> winners = new ReferenceOpenHashSet();

      for (int j = 0; j < sinkNodes.size(); j++) {
         int edgeIdx = sinkEdgeIndices.getInt(j);
         if (dinic.residual(edgeIdx) == 0) {
            winners.add((GridNode)sinkNodes.get(j));
         }
      }

      Reference2IntOpenHashMap<IGridNode> nodeFlow = new Reference2IntOpenHashMap();
      nodeFlow.defaultReturnValue(0);

      for (IGridNode nxx : network) {
         int ci = idx.getInt(nxx);
         int originalCap = nodeCap(nxx, mode);
         int flowThrough = originalCap - dinic.residual(splitEdge[ci]);
         if (flowThrough > 0) {
            nodeFlow.put(nxx, flowThrough);
         }
      }

      Reference2IntOpenHashMap<GridConnection> connectionFlow = new Reference2IntOpenHashMap();
      connectionFlow.defaultReturnValue(0);

      for (ConnEdge ce : connEdges) {
         int flowAB = ce.cap - dinic.residual(ce.edgeAB);
         int flowBA = ce.cap - dinic.residual(ce.edgeBA);
         int netFlow = Math.abs(flowAB - flowBA);
         if (netFlow > 0) {
            connectionFlow.put(ce.gc, netFlow);
         }
      }

      for (FaceEdge fe : faceEdges) {
         int flow = fe.cap - dinic.residual(fe.edgeIdx);
         if (flow > 0) {
            connectionFlow.mergeInt(fe.gc, flow, Integer::sum);
         }
      }

      return new BorrowedCapacityCalculator.Result(winners, network, nodeFlow, connectionFlow);
   }

   private static int nodeCap(IGridNode node, ChannelMode mode) {
      if (OverloadedChannelOwnerHelper.is128ChannelOwner(node.getOwner())) {
         return 1073741823;
      } else if (node instanceof GridNode gn) {
         if (gn.hasFlag(GridFlags.CANNOT_CARRY)) {
            return gn.hasFlag(GridFlags.REQUIRE_CHANNEL) ? 1 : 0;
         } else {
            return gn.getMaxChannels();
         }
      } else {
         return 8 * mode.getCableCapacityFactor();
      }
   }

   private static int getConnectionCap(GridConnection gc, IGridNode a, IGridNode b, ChannelMode mode) {
      if (gc.getDirection(a) != null) {
         return 1073741823;
      } else {
         int capA = a.getOwner() instanceof WirelessConnectionCapProvider pA ? pA.getWirelessChannelCap(mode) : 1073741823;
         int capB = b.getOwner() instanceof WirelessConnectionCapProvider pB ? pB.getWirelessChannelCap(mode) : 1073741823;
         return Math.min(capA, capB);
      }
   }

   private static final class Dinic {
      private final int size;
      private final int[] head;
      private int[] to;
      private int[] cap;
      private int[] nxt;
      private int cnt;
      private final int[] level;
      private final int[] cur;

      Dinic(int n) {
         this.size = n;
         this.head = new int[n];
         Arrays.fill(this.head, -1);
         int init = Math.max(n * 6, 64);
         this.to = new int[init];
         this.cap = new int[init];
         this.nxt = new int[init];
         this.level = new int[n];
         this.cur = new int[n];
      }

      int edgeCount() {
         return this.cnt;
      }

      int residual(int edgeIdx) {
         return this.cap[edgeIdx];
      }

      void addEdge(int u, int v, int c) {
         this.grow(this.cnt + 2);
         this.link(u, v, c);
         this.link(v, u, 0);
      }

      private void link(int u, int v, int c) {
         this.to[this.cnt] = v;
         this.cap[this.cnt] = c;
         this.nxt[this.cnt] = this.head[u];
         this.head[u] = this.cnt++;
      }

      private void grow(int need) {
         if (need > this.to.length) {
            int len = Math.max(this.to.length * 2, need);
            this.to = Arrays.copyOf(this.to, len);
            this.cap = Arrays.copyOf(this.cap, len);
            this.nxt = Arrays.copyOf(this.nxt, len);
         }
      }

      private boolean bfs(int s, int t) {
         Arrays.fill(this.level, -1);
         this.level[s] = 0;
         Queue<Integer> q = new ArrayDeque<>();
         q.add(s);

         while (!q.isEmpty()) {
            int v = q.poll();

            for (int e = this.head[v]; e != -1; e = this.nxt[e]) {
               if (this.cap[e] > 0 && this.level[this.to[e]] < 0) {
                  this.level[this.to[e]] = this.level[v] + 1;
                  q.add(this.to[e]);
               }
            }
         }

         return this.level[t] >= 0;
      }

      private int dfs(int v, int t, int pushed) {
         if (v == t) {
            return pushed;
         } else {
            while (this.cur[v] != -1) {
               int e = this.cur[v];
               if (this.cap[e] > 0 && this.level[this.to[e]] == this.level[v] + 1) {
                  int d = this.dfs(this.to[e], t, Math.min(pushed, this.cap[e]));
                  if (d > 0) {
                     this.cap[e] = this.cap[e] - d;
                     this.cap[e ^ 1] = this.cap[e ^ 1] + d;
                     return d;
                  }
               }

               this.cur[v] = this.nxt[this.cur[v]];
            }

            return 0;
         }
      }

      int maxFlow(int s, int t, int demandCap) {
         int flow = 0;

         while (flow < demandCap && this.bfs(s, t)) {
            System.arraycopy(this.head, 0, this.cur, 0, this.size);

            int d;
            while ((d = this.dfs(s, t, demandCap - flow)) > 0) {
               flow += d;
               if (flow >= demandCap) {
                  break;
               }
            }
         }

         return flow;
      }
   }

   public static record Result(
      Set<GridNode> channelNodes,
      Set<IGridNode> networkNodes,
      Reference2IntOpenHashMap<IGridNode> nodeFlow,
      Reference2IntOpenHashMap<GridConnection> connectionFlow
   ) {
   }
}
