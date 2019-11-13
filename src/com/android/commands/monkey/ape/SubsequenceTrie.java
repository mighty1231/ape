package com.android.commands.monkey.ape;

import java.util.HashMap;
import java.util.Map;

import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.utils.Config;

public class SubsequenceTrie {
    static class SubsequenceTrieNode { 
        private StateTransition transition;
        private State state;
        private HashMap<StateTransition, SubsequenceTrieNode> children;
        private int count;

        public SubsequenceTrieNode(StateTransition tr) {
            transition = tr;
            if (tr == null)
                state = null;
            else
                state = tr.getTarget();
            children = new HashMap<>();
            count = 0;
        }

        public HashMap<StateTransition, SubsequenceTrieNode> getChildren() { return children; } 
        public StateTransition getTransition() { return transition; } 
        public State getState() { return state; } 
        public int getCount() { return count; }
        public void incCount() { count += 1; }

        // debug
        public void print(int curDepth, int maxDepth, SubsequenceTrieNode curNode) {
            for (int i=0; i<curDepth; i++)
                System.out.print("  ");
            if (curNode == this)
                System.out.println(String.format("- %s[cnt=%d][#children=%d] <- CURRENT", transition.toShortString(), count, children.size()));
            else if (transition == null)
                System.out.println(String.format("- null[cnt=%d][#children=%d]", count, children.size()));
            else
                System.out.println(String.format("- %s[cnt=%d][#children=%d]", transition.toShortString(), count, children.size()));
            if (curDepth >= maxDepth) {
                for (int i=0; i<curDepth; i++)
                    System.out.print("  ");
                System.out.println("  ...");
                return;
            }
            for (Map.Entry<StateTransition, SubsequenceTrieNode> elem: children.entrySet()) {
                elem.getValue().print(curDepth + 1, maxDepth, curNode);
            }
        }
    }

    private SubsequenceTrieNode root;
    private int totalSize;

    private int observingSeqLength;
    private double seqCountLimitRatio;
    private int splitCount;

    private SubsequenceTrieNode curNode;
    private int curLength;

    public SubsequenceTrie(int observingSeqLength, double seqCountLimitRatio) {
        System.out.println(String.format("[APE_MT_SS] seqLength %d seqCountLimitRatio %.2f", observingSeqLength, seqCountLimitRatio));
        root = new SubsequenceTrieNode(null);
        this.observingSeqLength = observingSeqLength;
        this.seqCountLimitRatio = seqCountLimitRatio;
        curNode = root;
        curLength = 0;
        totalSize = 0;
        splitCount = 0;
    }

    public void clear() {
        root = new SubsequenceTrieNode(null);
        curNode = root;
        curLength = 0;
        totalSize = 0;
        splitCount = 0;
    }

    public void moveForward(StateTransition transition) {
        if (curNode != root && curNode.getState() != transition.getSource()) {
            throw new RuntimeException("State does not match!");
        }
        HashMap<StateTransition, SubsequenceTrieNode> children = curNode.getChildren();
        if (children.containsKey(transition)) {
            curNode = children.get(transition);
        } else {
            curNode = new SubsequenceTrieNode(transition);
            children.put(transition, curNode);
        }
        totalSize++;
        curLength++;
    }

    public boolean checkNextTransition(StateTransition transition) {
        if (curLength >= observingSeqLength)
            return true;
        HashMap<StateTransition, SubsequenceTrieNode> children = curNode.getChildren();
        if (!children.containsKey(transition))
            return true;
        int limit = (int) (splitCount * seqCountLimitRatio);
        if (limit < 3)
            limit = 3;
        if (children.get(transition).getCount() < limit)
            return true;
        return false;
    }

    public boolean checkNextSubsequence(Subsequence subsequence) {
        SubsequenceTrieNode tempCurNode = curNode;
        SubsequenceTrieNode nextNode = null;
        int limit = (int) (splitCount * seqCountLimitRatio);
        if (limit < 3)
            limit = 3;
        for (StateTransition edge : subsequence.getEdges()) {
            if (edge.getSource() != tempCurNode.getState()) {
                System.out.println("[APE_MT_WARNING] checkNextSubsequence(): subsequence does not match on current node");
                return true;
            }
            HashMap<StateTransition, SubsequenceTrieNode> children = tempCurNode.getChildren();
            if (!children.containsKey(edge)) {
                children = root.getChildren();
                if (!children.containsKey(edge)) {
                    System.out.println("[APE_MT_WARNING] checkNextSubsequence(): split but no way to get next node");
                    return true;
                }
                nextNode = children.get(edge);
            } else {
                nextNode = children.get(edge);
            }
            if (children.get(edge).getCount() >= limit)
                return false;
            tempCurNode = nextNode;
        }
        return false;
    }

    public int getTransitionCount() {
        return totalSize;
    }

    public void debug_print() {
        root.print(0, 3, curNode);
    }

    // met TargetState
    public void stateSplit() {
        if (curNode == root) { return; }
        int count = curNode.getCount();
        curNode.incCount();
        curNode = root;
        curLength = 0;
        splitCount++;
    }
}
