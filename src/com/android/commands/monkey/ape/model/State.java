package com.android.commands.monkey.ape.model;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;

import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.agent.StatefulAgent;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.NamerComparator;
import com.android.commands.monkey.ape.naming.NamerFactory;
import com.android.commands.monkey.ape.naming.Naming;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.tree.GUITreeTransition;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.Utils;
import com.android.commands.monkey.ape.model.Graph;

import android.content.ComponentName;

public class State extends GraphElement {



    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    private StateKey stateKey;
    private ModelAction[] actions;
    private ModelAction backAction;

    private List<GUITree> treeHistory;
    private final Map<State, Double> stateToScore = new HashMap<>();

    public State(StateKey stateKey) {
        this.stateKey = stateKey;
        List<ModelAction> c = new ArrayList<ModelAction>();
        for (Name widget : stateKey.getWidgets()) {
            buildActions(c, widget);
        }
        backAction = new ModelAction(this, ActionType.MODEL_BACK);
        c.add(backAction);
        actions = c.toArray(new ModelAction[c.size()]);
    }

    /**
     * GUI actions, i.e., action with a target
     * @return
     */
    public List<ModelAction> targetedActions() {
        List<ModelAction> results = new ArrayList<ModelAction> (actions.length);
        collectActions(results, ActionFilter.WITH_TARGET);
        return results;
    }

    public List<ModelAction> collectActions(ActionFilter filter) {
        List<ModelAction> results = new ArrayList<ModelAction> (actions.length);
        collectActions(results, filter);
        return results;
    }

    public void collectActions(Collection<ModelAction> results, ActionFilter filter) {
        for (ModelAction action : actions) {
            if (filter.include(action)) {
                results.add(action);
            }
        }
    }

    public int countActions(ActionFilter filter, boolean includeBack) {
        int count = 0;
        for (ModelAction action : actions) {
            if (!includeBack && action.isBack()) {
                continue;
            }
            if (filter.include(action)) {
                count++;
            }
        }
        return count;
    }

    private int countActionPriority(ActionFilter filter, boolean includeBack) {
        int totalPriority = 0;
        for (ModelAction action : actions) {
            if (!includeBack && action.getType().equals(ActionType.MODEL_BACK)) {
                continue;
            }
            if (filter.include(action)) {
                if (action.getPriority() <= 0) {
                    throw new IllegalStateException(
                            "Action should has a positive priority, but we get " + action.getPriority());
                }
                totalPriority += action.getPriority();
            }
        }
        return totalPriority;
    }

    public ModelAction pickWithTargetMethod(Graph graph, Random random) {
        // no one uses that.... then,
        int actionCount = actions.length;
        Set<State> targetStates = graph.getMetTargetMethodStates();
        if (targetStates == null || targetStates.isEmpty()) {
            return null;
        }
        if (actionCount == 0)
            return null;

        // if there were same transitions >= 2 in a row, don't choose it anymore
        List<GUITreeTransition> history = graph.getTreeHistory();
        int history_length = history.size();
        StateTransition transition_to_avoid = null;
        if (history_length >= 3) {
            transition_to_avoid = history.get(history_length-1).getCurrentStateTransition();
            for (int i=1; i<3; i++) {
                if (!transition_to_avoid.equals(history.get(history_length-1-i).getCurrentStateTransition())) {
                    transition_to_avoid = null;
                    break;
                }
            }
        }

        // choose from state transition history
        // @TODO if transitions with ratio > 0.0 is more than two.. then..?
        List<ModelAction> actionCandidates = new ArrayList<>();
        Set<StateTransition> transitions = graph.getOutStateTransitions(this);
        double maxRatio = 0.0;
        ModelAction chosen = null;
        for (StateTransition transition : transitions) {
            if (transition.equals(transition_to_avoid))
                continue;
            double ratio = transition.metTargetRatio();
            if (ratio > maxRatio) {
                ModelAction candidate = transition.getAction();
                Name widget = candidate.getTarget();
                ActionType type = candidate.getType();
                boolean found = false;
                if (widget != null) {
                    for (ModelAction action : actions) {
                        if (widget.equals(action.getTarget()) && type.equals(action.getType())) {
                            chosen = action;
                            found = true;
                            break;
                        }
                    }
                } else {
                    for (ModelAction action : actions) {
                        if (action.requireTarget() == false && type.equals(action.getType())) {
                            chosen = action;
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    System.out.println(String.format("[APE_MT] Failed to find action with name %s type %s",
                            widget, type));
                } else {
                    maxRatio = ratio;
                }
            }
        }

        // double maxRatio = 0.0;
        // for (ModelAction action : actions) {

        //     Set<StateTransition> stateTransitions = graph.getTransitionSetByName(action);
        //     double sum = 0.0; int cnt = 0;
        //     for (StateTransition transition: stateTransitions) {
        //         double ratio = transition.metTargetRatio();
        //         if (ratio > 0.0) {
        //             sum += ratio; cnt += 1;
        //         }
        //     }

        //     if (cnt == 0)
        //         continue;

        //     double ratio = sum / cnt;
        //     if (maxRatio < ratio) {
        //         chosen = action;
        //         maxRatio = ratio;
        //     }
        // }
        if (chosen != null) {
            if (random.nextDouble() < maxRatio * 0.9) {
                System.out.println("[APE_MT] Accept MET_TARGET action " + chosen +  " ratio " + maxRatio + " among size = " + actionCount);
            } else {
                System.out.println("[APE_MT] Reject MET_TARGET action " + chosen +  " ratio " + maxRatio + " among size = " + actionCount);
            }
        }
        return chosen;
    }

    public ModelAction pickWithTargetMethodNear(Graph graph, Random random) {
        /*
        if target met transition exists, choose it, otherwise choose nearest states,
        */

        /* make score to target */
        LinkedList<State> stateQueue = new LinkedList<>();
        Set<State> targetStates = graph.getMetTargetMethodStates();
        if (targetStates == null || targetStates.isEmpty()) {
            System.out.println("[APE_MT] targetStates.size = 0");
            return null;
        }
        if (actions.length == 0)
            return null;

        System.out.println("[APE_MT] targetStates.size = " + targetStates.size());
        for (State state: targetStates) {
            Set<StateTransition> transitions = graph.getOutStateTransitions(state);
            double score = 0.0;
            for (StateTransition transition: transitions) {
                double new_score = transition.metTargetRatio();
                if (new_score > score)
                    score = new_score;
            }
            if (score == 0.0) {
                throw new RuntimeException("[APE_MT] score should not be 0");
            }
            stateToScore.put(state, score);
            System.out.println(String.format("[APE_MT] targetState %s score %.2f", state, score));
            stateQueue.addLast(state);
        }

        Comparator<State> reversedStateComparator = new Comparator<State>() {
            @Override
            public int compare(State s1, State s2) {
                double diff = stateToScore.get(s1) - stateToScore.get(s2);
                if (diff > 0.0) return -1;
                else if (diff < 0.0) return 1;
                return 0;
            }
        };
        double currentScore = -1.0;

        // Candidate should have score bigger than epsilon
        double chosenEpsilon = random.nextDouble();
        Collections.sort(stateQueue, reversedStateComparator);
        boolean thisFound = false;
        while (!stateQueue.isEmpty()) {
            State state = stateQueue.removeFirst();
            if (state == this) {
                currentScore = stateToScore.get(state);
                thisFound = true;
            }
            if (thisFound)
                continue;
            double score = stateToScore.get(state) * 0.9;
            if (score < chosenEpsilon)
                continue;
            Set<StateTransition> transitions = graph.getInStateTransitions(state);
            int insertion_idx = -1;
            for (StateTransition transition: transitions) {
                State source = transition.getSource();
                if (source == null || stateToScore.containsKey(source))
                    continue;
                stateToScore.put(source, score);
                if (insertion_idx == -1) {
                    insertion_idx = Collections.binarySearch(stateQueue, source, reversedStateComparator);
                    if (insertion_idx < 0)
                        insertion_idx = ~insertion_idx;
                }
                if (!graph.isEntryState(source)) {
                    stateQueue.add(insertion_idx, source);
                }
            }
        }

        if (currentScore < 0.0) { // target is unreachable now
            stateToScore.clear();
            return null;
        }

        // choose from state transition history
        List<ModelAction> actionCandidates = new ArrayList<>();
        Set<StateTransition> transitions = graph.getOutStateTransitions(this);
        for (StateTransition transition : transitions) {
            if (!graph.checkNextTransition(transition))
                continue;
            State target = transition.getTarget();
            if (target == null)
                continue;

            Double targetScore = stateToScore.get(target);
            if (targetScore != null && targetScore > currentScore) {
                System.out.println("[APE_MT] Transition candidate " + transition);
                System.out.println(String.format("[APE_MT] State %s score %.2f", target, targetScore));
                ModelAction candidate = transition.getAction();
                Name widget = candidate.getTarget();
                ActionType type = candidate.getType();
                boolean found = false;
                if (widget != null) {
                    for (ModelAction action : actions) {
                        if (widget.equals(action.getTarget()) && type.equals(action.getType())) {
                            actionCandidates.add(action);
                            found = true;
                            break;
                        }
                    }
                } else {
                    for (ModelAction action : actions) {
                        if (action.requireTarget() == false && type.equals(action.getType())) {
                            actionCandidates.add(action);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    System.out.println(String.format("[APE_MT] Failed to find action with name %s type %s",
                            widget, type));
                }
            }
        }

        /*
        // explore
        // currently, do NOP action is the best
        System.out.println("[APE_MT] Current state " + this + " has score " + currentScore);
        List<ModelAction> actionCandidates = new ArrayList<>();

        // current states' score;
        for (ModelAction action : actions) {
            Set<StateTransition> stateTransitions = graph.getTransitionSetByName(action);
            for (StateTransition transition: stateTransitions) {
                State target = transition.getTarget();
                if (target != null) {
                    Integer score = stateToScore.get(target);
                    if (score == null)
                        System.out.println("[APE_MT] target " + target + " is unreachable to target");
                    else {
                        System.out.println("[APE_MT] target " + target + " has score " + score);
                        if (score < currentScore) {
                            actionCandidates.add(action);
                            break;
                        }
                    }
                } else {
                    System.out.println("[APE_MT] target of transition " + transition + " is null" );
                }
            }
        }
        */

        if (!actionCandidates.isEmpty()) {
            ModelAction chosen = RandomHelper.randomPick(actionCandidates);
            System.out.println("[APE_MT] MET_TARGET_NEAR action " + chosen +  " currentScore " + currentScore + " among actions.size = " + actions.length);
            stateToScore.clear();
            return chosen;
        }

        stateToScore.clear();
        return null;
    }

    public ModelAction greedyPickLeastVisited(ActionFilter filter) {
        ModelAction minAction = null;
        int minValue = Integer.MAX_VALUE;
        for (ModelAction action : actions) {
            if (!filter.include(action)) {
                continue;
            }
            if (action.getVisitedCount() < minValue) {
                minValue = action.getVisitedCount();
                minAction = action;
            }
        }
        return minAction;
    }

    public ModelAction randomlyPickAction(Random random, ActionFilter filter) {
        return randomlyPickAction(random, filter, true);
    }

    public ModelAction randomlyPickAction(Random random, ActionFilter filter, boolean includeBack) {
        int total = countActionPriority(filter, includeBack);
        if (total == 0) {
            return null;
        }

        int index = random.nextInt(total);
        return pickAction(index, filter, includeBack);
    }

    public boolean containsTarget(Name target) {
        return stateKey.containsTarget(target);
    }

    private ModelAction pickAction(int index, ActionFilter filter, boolean includeBack) {
        for (ModelAction action : actions) {
            if (!includeBack && action.getType().equals(ActionType.MODEL_BACK)) {
                continue;
            }
            if (filter.include(action)) {
                int priority = action.getPriority();
                if (priority > index) {
                    return action;
                } else {
                    index = index - priority;
                }
            }
        }
        Logger.println("*** WARNING: You have a non-stable action filter...");
        return null; // this may happen if you filter is not stable.
    }

    public static StateKey buildStateKey(Naming naming, ComponentName activity, Name[] widgets) {
        return NamerFactory.buildStateKey(naming, activity, widgets);
    }

    public static State buildState(StateKey stateKey) {
        State state = new State(stateKey);
        return state;
    }

    public int getCountOfActions() {
        return this.actions.length;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((stateKey == null) ? 0 : stateKey.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        State other = (State) obj;
        if (stateKey == null) {
            if (other.stateKey != null)
                return false;
        } else if (!stateKey.equals(other.stateKey))
            return false;
        return true;
    }

    private void buildActions(List<ModelAction> actions, Name widget) {
        List<ActionType> actionTypes = NamerFactory.decodeActions(widget);
        for (ActionType actionType : actionTypes) {
            ModelAction action = new ModelAction(this, widget, actionType);
            actions.add(action);
        }
    }

    public List<ModelAction> getActions() {
        return Arrays.asList(actions);
    }

    public String getActivity() {
        return stateKey.getActivity();
    }

    public ModelAction getBackAction() {
        return this.backAction;
    }

    public String toString() {
        return super.toString() + this.stateKey.toString() + "[A=" + this.actions.length + "]";
    }

    public StateKey getStateKey() {
        return this.stateKey;
    }

    public void dumpState() {
        Logger.format("Dumpping state %s", this);
        stateKey.dumpState();
    }

    public void saveState(PrintWriter pw) {
        stateKey.saveState(pw);
        pw.println();
        for (int i = 0; i < actions.length; i++) {
            pw.format("%3d %s\n", i, actions[i]);
        }
    }

    public ModelAction firstEnabledUnvisitedValidAction() {
        return firstAction(ActionFilter.ENABLED_VALID_UNVISITED);
    }

    public ModelAction firstAction(ActionFilter filter) {
        for (ModelAction action : actions) {
            if (filter.include(action)) {
                return action;
            }
        }
        return null;
    }

    public int getCountOfWidgets() {
        return getWidgets().length;
    }

    public Name[] getWidgets() {
        return stateKey.getWidgets();
    }

    public void printActions() {
        int count = 1;
        for (ModelAction e : actions) {
            Logger.format("%5d %s", count++, e.toFullString());
        }
    }

    public ModelAction randomlyPickUnvisitedAction(Random random) {
        ModelAction action = randomlyPickAction(random, ActionFilter.ENABLED_VALID_UNVISITED, false);
        if (action == null && ActionFilter.ENABLED_VALID_UNVISITED.include(getBackAction())) {
            action = getBackAction();
        }
        return action;
    }

    public ModelAction randomlyPickUnsaturatedAction(Random random) {
        ModelAction action = randomlyPickAction(random, ActionFilter.ENABLED_VALID_UNSATURATED, false);
        if (action == null && ActionFilter.ENABLED_VALID_UNSATURATED.include(getBackAction())) {
            action = getBackAction();
        }
        return action;
    }

    public ModelAction randomlyPickAction(Random random) {
        return randomlyPickAction(random, ActionFilter.ALL);
    }

    public ModelAction randomlyPickValidAction(Random random) {
        return randomlyPickAction(random, ActionFilter.VALID);
    }

    public void append(GUITree tree) {
        Naming naming = stateKey.getNaming();
        if (!tree.getCurrentNaming().equals(naming)) {
            Logger.wprintln("Different naming results in the same state.");
            Logger.wprintln("Expected " + naming);
            if (naming != null)
                naming.dump();
            Naming get = tree.getCurrentNaming();
            Logger.wprintln("Get: " + get);
            if (get != null) {
                get.dump();
            }
            if (naming != null && get != null) {
                Logger.wprintln("Equivalent? " + naming.equivalent(get));
            }
            // throw new RuntimeException("Different naming results in the same
            // state.");
        }
        if (tree.getCurrentState() == this) {
            Logger.println(this);
            throw new RuntimeException("Cannot be appended twice.");
        }
        tree.setCurrentState(this);
        if (treeHistory == null) {
            treeHistory = new ArrayList<GUITree>();
        }
        treeHistory.add(tree);
    }

    public List<GUITree> getGUITrees() {
        if (this.treeHistory == null) {
            return Collections.emptyList();
        }
        return this.treeHistory;
    }

    public GUITree getLatestGUITree() {
        if (treeHistory == null) {
            return null;
        }
        return treeHistory.get(treeHistory.size() - 1);
    }

    public ModelAction resolveAction(StatefulAgent agent, ModelAction action, int throttle) {
        if (treeHistory == null) {
            throw new IllegalStateException("Empty GUI tree history");
        }
        GUITree latest = getLatestGUITree();
        if (!action.getType().requireTarget()) {
            action.resolveAt(agent.getTimestamp(), throttle, latest, null, null);
            return action;
        }
        GUITreeNode[] nodes = latest.pickNodes(action);
        GUITreeNode node = RandomHelper.randomPick(Arrays.asList(nodes));
        action.resolveAt(agent.getTimestamp(), throttle, latest, node, nodes);
        return action;
    }

    /**
     * 
     * @param action
     * @return
     */
    public ModelAction relocate(ModelAction action) {
        if (action.getState().equals(this)) {
            return action;
        }
        Name target = action.getTarget();
        ActionType type = action.getType();
        if (target == null) {
            for (ModelAction a : getActions()) {
                if (type.equals(a.getType()) && a.getTarget() == null) {
                    return a;
                }
            }
            return null;
        }
        List<ModelAction> candidates = new ArrayList<>();
        for (ModelAction a : getActions()) {
            if (!a.requireTarget()) {
                continue;
            }
            if (type.equals(a.getType())) {
                if (target.refinesTo(a.getTarget())
                        || a.getTarget().refinesTo(target)) {
                    candidates.add(a);
                }
            }
        }
        if (!candidates.isEmpty()) {
            Logger.iformat("Relocating finds %d candidates", candidates.size());
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            Collections.sort(candidates, new Comparator<ModelAction>() {
                @Override
                public int compare(ModelAction o1, ModelAction o2) {
                    return - NamerComparator.INSTANCE.compare(o1.getTarget().getNamer(), o2.getTarget().getNamer());
                }
            });
            for (ModelAction c : candidates) {
                Logger.iformat("- %s", c);
            }
            return candidates.get(0);
        }
        return null;
    }

    public int getCountOfTargetNodes(String target) {
        if (treeHistory == null) {
            throw new IllegalStateException("Empty GUI tree history");
        }
        GUITree latest = getLatestGUITree();
        return latest.getCountOfTargetNodes(target);
    }

    public boolean isTrivialState() {
        return this.stateKey.isTrivialState();
    }

    public void printWidgets() {
        stateKey.printWidgets();
    }

    public ModelAction getAction(Name widget, ActionType type) {
        if (!containsTarget(widget)) {
            this.dumpState();
            throw new IllegalStateException("No such widget [" + widget + "]");
        }
        for (ModelAction action : actions) {
            if (widget.equals(action.getTarget()) && type.equals(action.getType())) {
                return action;
            }
        }
        this.dumpState();
        throw new IllegalStateException("No such action [" + type + "@" + widget + "]");
    }

    public ModelAction getAction(ActionType type) {
        for (ModelAction action : actions) {
            if (action.requireTarget() == false && type.equals(action.getType())) {
                return action;
            }
        }
        throw new IllegalStateException("No such action [" + type + "]");
    }

    public Naming getCurrentNaming() {
        return stateKey.getNaming();
    }

    public float getSaturation() {
        float saturation = 0.0f;
        if (actions.length == 0) {
            return 1.0F;
        }
        for (ModelAction action : actions) {
            saturation += action.getResolvedSaturation();
        }
        return saturation/actions.length;
    }

    public boolean isSaturated() {
        for (ModelAction action : actions) {
            if (!ActionFilter.ENABLED_VALID.include(action)) {
                continue;
            }
            if (!action.isSaturated()) {
                return false;
            }
        }
        return true;
    }

    public GUITree removeLastLastGUITree() {
        if (treeHistory == null || treeHistory.size() <=1) {
            return null;
        }
        return treeHistory.remove(treeHistory.size() - 2);
    }

    public void dumpActions() {
        Utils.dump(actions);
    }

    public List<ModelAction> getUnsaturatedActions() {
        List<ModelAction> actions = new ArrayList<ModelAction>(this.actions.length);
        collectActions(actions, ActionFilter.ENABLED_VALID_UNSATURATED);
        return actions;
    }

    public boolean isBackEnabled() {
        return ActionFilter.ENABLED_VALID.include(this.backAction);
    }

    public boolean hasMetTargetMethod() {
        for (GUITree tree: treeHistory) {
            if (tree.hasMetTargetMethod())
                return true;
        }
        return false;
    }
}
