package com.android.commands.monkey.ape;

import java.util.HashMap;
import java.util.Map;

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
                System.out.println(String.format("- %d[children=%d] <- CURRENT", count, children.size()));
            else
                System.out.println(String.format("- %d[children=%d]", count, children.size()));
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
    private int seqCountLimit;

    private SubsequenceTrieNode curNode;
    private int curLength;

    public SubsequenceTrie(int observingSeqLength, int seqCountLimit) {
        System.out.println(String.format("[APE_MT_SS] seqLength %d seqCountLimit %d", observingSeqLength, seqCountLimit));
        root = new SubsequenceTrieNode(null);
        this.observingSeqLength = observingSeqLength;
        this.seqCountLimit = seqCountLimit;
        curNode = root;
        curLength = 0;
        totalSize = 0;
    }

    public void clear() {
        root = new SubsequenceTrieNode(null);
        curNode = root;
        curLength = 0;
        totalSize = 0;
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
        if (children.get(transition).getCount() < seqCountLimit)
            return true;
        return false;
    }

    public int getTransitionCount() {
        return totalSize;
    }

    // met TargetState
    public void stateSplit() {
        if (curNode == root) { return; }
        int count = curNode.getCount();
        System.out.println(String.format("[APE_MT_SS] stateSplit %d -> %d", count, count+1));
        curNode.incCount();
        root.print(0, 3, curNode);
        curNode = root;
        curLength = 0;
    }
}
