package com.android.commands.monkey.ape;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.agent.SataAgent;
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

        // dfs
        public void collectCount(List<Integer> counts) {
            if (count != 0) {
                counts.add(count);
            }
            for (SubsequenceTrieNode node: children.values()) {
                node.collectCount(counts);
            }
        }

        public void collectSubsequenceAboveCount(List<List<StateTransition>> subsequences, List<StateTransition> curSubsequence, int countLimit) {
            if (count > countLimit) {
                subsequences.add(curSubsequence);
                return;
            }
            for (Map.Entry<StateTransition, SubsequenceTrieNode> elem: children.entrySet()) {
                List<StateTransition> newSubsequence = new ArrayList<>(curSubsequence);
                newSubsequence.add(elem.getKey());
                elem.getValue().collectSubsequenceAboveCount(subsequences, newSubsequence, countLimit);
            }
        }

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
            for (SubsequenceTrieNode node: children.values()) {
                node.print(curDepth + 1, maxDepth, curNode);
            }
        }
    }

    private SubsequenceTrieNode root;
    private int totalSize;
    private int splitCount;

    private SubsequenceTrieNode curNode;
    private int curLength;

    private SubsequenceTrieNode lastNode;

    public SubsequenceTrie() {
        root = new SubsequenceTrieNode(null);
        curNode = root;
        curLength = 0;
        totalSize = 0;
        splitCount = 0;
        lastNode = null;
    }

    public void clear() {
        root = new SubsequenceTrieNode(null);
        curNode = root;
        curLength = 0;
        totalSize = 0;
        splitCount = 0;
        lastNode = null;
    }

    public void moveForward(StateTransition transition) {
        if (curNode != root && curNode.getState() != transition.getSource()) {
            throw new RuntimeException("State does not match!");
        }
        lastNode = curNode;
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


    // evaluate map: transitions to be rejected -> probability to be executed
    public Map<StateTransition, Double> getTransitionsToRejectRatio(SataAgent agent, State newState, long countLimit) {
        if (countLimit == 0 || splitCount == 0) {
            return null;
        }

        if (newState == null) {
            stateSplit(false);
            return null;
        }

        HashMap<StateTransition, SubsequenceTrieNode> children;
        if (curNode != root) {
            children = curNode.getChildren();
        } else {
            children = new HashMap<>();
            for (Map.Entry<StateTransition, SubsequenceTrieNode> entry: root.getChildren().entrySet()) {
                if (entry.getKey().getSource() == newState) {
                    children.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (curNode != root && newState != curNode.getState()) {
            throw new RuntimeException();
        }

        if (children.isEmpty())
            return null;

        Map<StateTransition, Double> ret = new HashMap<>();
        State curState = curNode.getState();
        for (Map.Entry<StateTransition, SubsequenceTrieNode> elem: children.entrySet()) {
            if (curNode == root) {
                if (elem.getKey().getSource() != newState) {
                    continue;
                }
            } else if (elem.getKey().getSource() != curState) {
                throw new RuntimeException("source target miss match!");
            }

            List<List<StateTransition>> subsequences = new ArrayList<>();
            List<StateTransition> curSubsequence = new ArrayList<>();

            elem.getValue().collectSubsequenceAboveCount(subsequences, curSubsequence, (int) countLimit);
            if (subsequences.isEmpty()) {
                continue;
            }

            // evaluate probability for each routes
            double probability = 0.0;
            for (List<StateTransition> subsequence: subsequences) {
                probability += agent.evaluateSubsequenceProbability(subsequence);
            }

            ret.put(elem.getKey(), probability);
        }
        return ret;
    }

    public int getTransitionCount() {
        return totalSize;
    }

    public void debug_print() {
        root.print(0, 3, curNode);
    }

    public void markLastNode(StateTransition checkLastTransition) {
        if (lastNode.getTransition() != checkLastTransition) {
            throw new RuntimeException();
        }
        lastNode.incCount();
    }

    // met TargetState
    public void stateSplit(boolean hasMet) {
        if (curNode == root) { return; }
        if (hasMet)
            curNode.incCount();
        lastNode = curNode;
        curNode = root;
        curLength = 0;
        splitCount++;
    }
}
