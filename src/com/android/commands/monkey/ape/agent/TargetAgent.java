package com.android.commands.monkey.ape.agent;

import static com.android.commands.monkey.ape.utils.Config.defaultEpsilon;
import static com.android.commands.monkey.ape.utils.Config.doBackToTrivialActivity;
import static com.android.commands.monkey.ape.utils.Config.fallbackToGraphTransition;
import static com.android.commands.monkey.ape.utils.Config.fillTransitionsByHistory;
import static com.android.commands.monkey.ape.utils.Config.trivialActivityRankThreshold;
import static com.android.commands.monkey.ape.utils.Config.useActionDiffer;
import static com.android.commands.monkey.ape.utils.Config.stateScoreRatio;
import static com.android.commands.monkey.ape.utils.Config.stateScoreReducRatio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.ape.ActionFilter;
import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.AndroidDevice.Activity;
import com.android.commands.monkey.ape.AndroidDevice.Stack;
import com.android.commands.monkey.ape.AndroidDevice.Task;
import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.BaseActionFilter;
import com.android.commands.monkey.ape.Subsequence;
import com.android.commands.monkey.ape.SubsequenceFilter;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateActionDiffer;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.RandomHelper;

import android.content.ComponentName;

public class TargetAgent extends StatefulAgent {


    SubsequenceFilter unsaturatedActionsFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            State last = path.getLastState();
            return last.firstAction(ActionFilter.ENABLED_VALID_UNSATURATED) != null;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong();
        }
    };

    SubsequenceFilter backtrackSubsequenceFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            State last = path.getLastState();
            if (isGreedyState(last)) {
                return true;
            }
            if (isEntryState(last)) {
                return true;
            }
            return false;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (!edge.action.isBack()) { // only back action
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong();
        }
    };

    SubsequenceFilter greedySubsequenceFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            State last = path.getLastState();
            State prev;
            if (path.size() > 1) {
                prev = path.getLastLastState();
            } else {
                prev = path.getStartState();
            }
            if (isGreedyState(prev, last)) {
                return true;
            }
            return false;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (edge.action.isBack()) {
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong();
        }
    };

    SubsequenceFilter weakActionSubsequenceFilter = new SubsequenceFilter() {

        @Override
        public boolean include(Subsequence path) {
            if (path.isEmpty()) {
                return false;
            }
            StateTransition last = path.getLastStateTransition();
            if (last.getStrength() == 0 && last.getVisitedCount() < 3) {
                return true;
            }
            return false;
        }

        @Override
        public boolean extend(Subsequence path, StateTransition edge) {
            if (path.isClosed()) {
                return false;
            }
            if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                return false;
            }
            return edge.isStrong() || edge.getStrength() == 0;
        }
    };

    static enum TargetEventType {
        TRIVIAL_ACTIVITY,
        SATURATED_STATE, USE_BUFFER, EARLY_STAGE, TARGET,
        EPSILON_GREEDY, RANDOM, NULL, BUFFER_LOSS, FILL_BUFFER, BAD_STATE;
    }

    /**
     * Control the exploration and exploitation. The idea is borrowed from reinforcement learning.
     */
    private double epsilon;
    private static final int MET_TARGET_WEIGHT = 8;

    private StateActionDiffer actionDiffer = new StateActionDiffer();

    private int[] actionCounters;
    private ActivityNode backToActivity;
    private boolean strategyChangeReady = false;
    private boolean strategyChanged = false;
    private final Map<State, Double> stateToScore = new HashMap<>();
    private int earlyStageBuffer;
    private long countLimit;
    private int metTargetCounter;
    private int strategyFailedCounter;
    private int metNoTargetCounter;
    private double currentScoreReducRatio;
    private Map<State, Map<StateTransition, Double>> stateToTransitionToScore = new HashMap<>();
    private StateTransition lastChosenStateTransition;

    public TargetAgent(MonkeySourceApe ape, Graph graph) {
        this(ape, graph, defaultEpsilon);
    }

    public TargetAgent(MonkeySourceApe ape, Graph graph, double epsilon) {
        super(ape, graph);
        this.epsilon = epsilon;
        earlyStageBuffer = 0;
        countLimit = Config.getLong("ape.mt.countlim", 0);
        metTargetCounter = 0;
        strategyFailedCounter = 0;
        metNoTargetCounter = 0;
        currentScoreReducRatio = stateScoreRatio;
        lastChosenStateTransition = null;

        this.actionCounters = new int[TargetEventType.values().length];
    }

    @Override
    public void startNewEpisode() {
        super.startNewEpisode();
        getGraph().splitSubsequenceTrie();
        metTargetCounter = 0;
        strategyFailedCounter = 0;
        metNoTargetCounter = 0;
        currentScoreReducRatio = stateScoreRatio;
        lastChosenStateTransition = null;
        // actionToDoubtPoint.clear();
    }

    @Override
    protected void checkStable() {
        if (!isTargetMode()) {
            super.checkStable();
        } else {
            Logger.format("Graph Stable Counter: metTargetCounter (%d), strategyFailedCounter (%d), metNoTargetCounter (%d)",
                    metTargetCounter, strategyFailedCounter, metNoTargetCounter);
            if (metTargetCounter >= 5) {
                requestRestart();
                metTargetCounter = 0;
                currentScoreReducRatio = stateScoreRatio;
            }
            if (strategyFailedCounter >= 5) {
                requestRestart();
                strategyFailedCounter = 0;
            }
            if (metNoTargetCounter >= 300) {
                requestRestart();
                metNoTargetCounter = 0;
                currentScoreReducRatio = stateScoreRatio;
            }
        }
    }

    protected ModelAction checkFuzzing(ModelAction action) {
        action = super.checkFuzzing(action);
        if (strategyChanged)
            disableFuzzing = false;
        else
            disableFuzzing = true;

        return action;
    }

    public void metTarget() {
        metTargetCounter++;
        metNoTargetCounter--;
        currentScoreReducRatio /= stateScoreReducRatio;
    }

    private Map<ModelAction, Integer> actionToDoubtPoint = new HashMap<>();

    @Override
    public void onVisitStateTransition(StateTransition edge) {
        System.out.println(String.format("[APE_MT_DEBUG] metNonTarget counter %d ratio %.9f", metNoTargetCounter, currentScoreReducRatio));
        super.onVisitStateTransition(edge);
        // metNoTargetCounter++;
        // currentScoreReducRatio *= stateScoreReducRatio;
        // ModelAction action = edge.getAction();
        // if (lastChosenStateTransition != null) {
        //     if (lastChosenStateTransition.getSource() == edge.getSource()
        //         && lastChosenStateTransition.getAction() == action) {
        //         if (lastChosenStateTransition.getTarget() != edge.getTarget()) {
        //             System.out.println(String.format("[APE_MT_WARNING] transition %s chosen but %s was executed", lastChosenStateTransition, edge));
        //             Integer point = actionToDoubtPoint.get(action);
        //             if (point == null) {
        //                 actionToDoubtPoint.put(action, 1);
        //             } else {
        //                 actionToDoubtPoint.put(action, point + 1);
        //             }
        //         } else {
        //             actionToDoubtPoint.put(action, 0);
        //         }
        //     } else {
        //         System.out.println("[APE_MT] rebuild done?");
        //     }
        // }
    }

    protected boolean isEntryState(State state) {
        return this.getGraph().isEntryState(state);
    }

    protected void logActionSelected(Action action, TargetEventType type) {
        Logger.iformat("Select action %s by strategy %s", action, type);
        logEvent(type);
    }

    protected void logEvent(TargetEventType type) {
        int ordinal = type.ordinal();
        actionCounters[ordinal] = actionCounters[ordinal] + 1;
    }

    public void tearDown() {
        super.tearDown();
        printCounters();
    }

    protected void printCounters() {
        TargetEventType[] types = TargetEventType.values();
        for (TargetEventType type : types) {
            Logger.format("%6d  %s", actionCounters[type.ordinal()], type);
        }
    }

    protected ModelAction checkBackTrack() {
        if (newState.isSaturated()) { // no forward unsaturated actions selected by EARLY_STAGE
            Logger.iprintln("State is saturated: try to back track.");
            boolean doBackTrack = false;
            LinkedList<State> queue = new LinkedList<State>();
            Set<State> visited = new HashSet<>();
            queue.add(newState);
            visited.add(newState);
            State state = null;
            outer: while (!queue.isEmpty()) {
                state = queue.removeFirst();
                ModelAction action = state.getBackAction();
                Collection<StateTransition> sts = getGraph().getOutStateTransitions(action);
                for (StateTransition st : sts) {
                    if (st.isCircle()) {
                        continue;
                    }
                    if (st.isStrong()) {
                        State target = st.getTarget();
                        if (!target.isSaturated()) {
                            doBackTrack = true;
                            break outer;
                        } else {
                            if (!visited.contains(state)) {
                                queue.addLast(target);
                                visited.add(state);
                            }
                        }
                    }
                }
            }
            if (doBackTrack && newState != state) {
                ModelAction action = moveToState(newState, state, true);
                if (action != null) {
                    Logger.iprintln("Backtrack to an unsaturated state: " + state);
                    logActionSelected(action, TargetEventType.SATURATED_STATE);
                    return action;
                } else {
                    Logger.iformat("Cannot backtrack to %s", state);
                }
            }
        }
        return null;
    }

    protected boolean isDialogState(State state) {
        Collection<StateTransition> edges = getGraph().getInStateTransitions(state);
        int threshold = 5;
        if (edges.size() <= threshold) {
            return false;
        }
        return hasGreedyActionForward(state);
    }

    protected Action selectNewActionNonnull() {
        {
            // Logging
            printStrategy();
            Logger.iprintln("Check global actions.");
            for (ModelAction action : newState.targetedActions()) {
                if (action.isVisited()) {
                    continue;
                }
                if (getGraph().isNameGlobalAction(action)) {
                    Logger.iformat("- %s", action);
                }
            }
            if (strategyChangeReady) {
                if (!getGraph().hasMetTargetMethod()) {
                    System.out.println("[APE_MT_WARNING] Changing strategy requested but target method is not invoked");
                    earlyStageBuffer = 10;
                } else if (!strategyChanged) {
                    strategyChanged = true;
                    earlyStageBuffer = 30;
                    System.out.println("[APE_MT] TargetAgent: strategy changed");
                }
            }
        }
        Action resolved = null;
        resolved = selectNewActionFromBuffer();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.USE_BUFFER);
            return resolved;
        }
        resolved = selectNewActionBackToActivity();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.TRIVIAL_ACTIVITY);
            return resolved;
        }

        if (isTargetMode() && earlyStageBuffer == 0) {
            resolved = selectNewActionStochasticallyToTarget();
            if (resolved != null) {
                logActionSelected(resolved, TargetEventType.TARGET);
                return resolved;
            }

            lastChosenStateTransition = null;
        }

        if (earlyStageBuffer > 0)
            earlyStageBuffer--;

        resolved = selectNewActionEarlyStageForward();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.EARLY_STAGE);
            return resolved;
        }

        resolved = selectNewActionForTrivialActivity();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.TRIVIAL_ACTIVITY);
            return resolved;
        }

        resolved = selectNewActionEarlyStageBackward();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.EARLY_STAGE);
            return resolved;
        }

        resolved = selectNewActionEpsilonGreedyRandomly();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.EPSILON_GREEDY);
            return resolved;
        }

        resolved = handleNullAction();
        if (resolved != null) {
            logActionSelected(resolved, TargetEventType.NULL);
            return resolved;
        }

        throw new BadStateException("No available action on the current state");
    }

    protected ModelAction selectNewActionEarlyStageBackward() {
        return selectNewActionEarlyStageBackwardGreedy();
    }

    public void onBufferLoss(State actual, State expected) {
        logEvent(TargetEventType.BUFFER_LOSS);
    }

    public Map<StateTransition, Double> getTransitionScore(State state) {
        if (stateToTransitionToScore.containsKey(state)) {
            return stateToTransitionToScore.get(state);
        }
        Map<StateTransition, Double> transitionToScore = new HashMap<>();
        Map<StateTransition, ModelAction> transitionToAction = new HashMap<>();
        fillProbabilityMap(state, transitionToAction, transitionToScore);
        stateToTransitionToScore.put(state, transitionToScore);
        return transitionToScore;
    }

    public void fillProbabilityMap(State state, Map<StateTransition, ModelAction> transitionToAction, Map<StateTransition, Double> transitionToScore) {
        // assume stateToScore is filled
        // This must do not append to stateToTransitionScore. transitionToScore can be modified after this method
        List<ModelAction> actions = state.getActions();
        Set<StateTransition> transitions = getGraph().getOutStateTransitions(state);
        Map<State, Integer> countTransitionOnTarget = new HashMap<>();

        double totalScore = 0.0;
        for (StateTransition transition: transitions) {
            for (ModelAction action: actions) {
                if (transition.getAction() == action) {
                    transitionToAction.put(transition, action);
                    State target = transition.getTarget();
                    Double score = stateToScore.get(target);
                    if (score != null) {
                        transitionToScore.put(transition, score);
                        totalScore += score;
                        if (countTransitionOnTarget.containsKey(target)) {
                            countTransitionOnTarget.put(target, countTransitionOnTarget.get(target)+1);
                        } else {
                            countTransitionOnTarget.put(target, 1);
                        }
                    }
                    break;
                }
            }
        }

        double totalScore2 = 0.0;
        for (Map.Entry<StateTransition, Double> entry: transitionToScore.entrySet()) {
            StateTransition transition = entry.getKey();
            double score = entry.getValue();
            score /= countTransitionOnTarget.get(transition.getTarget());
            double ratio = transition.metTargetRatio();
            score += ratio * (MET_TARGET_WEIGHT * totalScore);
            entry.setValue(score);
            totalScore2 += score;
        }

        // normalzize
        for (Map.Entry<StateTransition, Double> entry : transitionToScore.entrySet()) {
            entry.setValue(entry.getValue() / totalScore2);
        }
    }

    public double evaluateSubsequenceProbability(List<StateTransition> subsequence) {
        if (subsequence == null || subsequence.isEmpty())
            return 1.0;
        State state = subsequence.get(0).getSource();
        double ret = 1.0;

        for (StateTransition transition: subsequence) {
            if (transition.getSource() != state)
                throw new RuntimeException("State miss match");
            ret *= getTransitionScore(state).get(transition);
            state = transition.getTarget();
        }
        return ret;
    }

    public double dupCountToRejectRatio(int count) {
        // 1 - 1/(c+1)
        return ((double)1.0 - ((double)1.0) / (count + 1));
    }

    protected ModelAction selectNewActionStochasticallyToTarget() {
        Graph graph = getGraph();
        Random random = ape.getRandom();
        List<ModelAction> actions = newState.getActions();
        if (actions.size() == 0)
            return null;

        LinkedList<State> stateQueue = new LinkedList<>();
        Set<State> targetStates = graph.getMetTargetMethodStates();
        if (targetStates == null || targetStates.isEmpty()) {
            System.out.println("[APE_MT] targetStates.size = 0");
            return null;
        }
        stateToTransitionToScore.clear();
        stateToScore.clear();
        System.out.println("[APE_MT] targetStates.size = " + targetStates.size());

        // fill scores for target states
        for (State state: targetStates) {
            Set<StateTransition> transitions = graph.getOutStateTransitions(state);
            double score = 0.0;
            for (StateTransition transition: transitions) {
                double new_score = transition.metTargetRatio();
                if (new_score > score)
                    score = new_score;
            }
            if (score == 0.0) { throw new RuntimeException("[APE_MT] score should not be 0"); }
            stateToScore.put(state, score);
            System.out.println(String.format("[APE_MT] targetState %s score %.2f", state, score));
            stateQueue.addLast(state);
        }

        // fill scores all states
        Comparator<State> reversedStateComparator = new Comparator<State>() {
            @Override
            public int compare(State s1, State s2) {
                double diff = stateToScore.get(s1) - stateToScore.get(s2);
                if (diff > 0.0) return -1;
                else if (diff < 0.0) return 1;
                return 0;
            }
        };
        Collections.sort(stateQueue, reversedStateComparator);
        double currentScore = -1.0;
        boolean thisFound = false;
        while (!stateQueue.isEmpty()) {
            State state = stateQueue.removeFirst();
            if (state == newState) {
                currentScore = stateToScore.get(state);
                thisFound = true;
            }
            double score;
            score = stateToScore.get(state) * currentScoreReducRatio;
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
                stateQueue.add(insertion_idx, source);
            }
        }
        // unreachable to target
        if (thisFound == false) {
            System.out.println("[APE_MT_WARNING] There is no way to go to target.");
            earlyStageBuffer += 5;
            strategyFailedCounter ++;
            return null;
        }

        // evaluate next transitions
        Map<StateTransition, ModelAction> transitionToAction = new HashMap<>();
        Map<StateTransition, Double> transitionToScore = new HashMap<>();
        fillProbabilityMap(newState, transitionToAction, transitionToScore);

        if (transitionToAction.isEmpty()) {
            System.out.println("[APE_MT_WARNING] Transition is not found");
            earlyStageBuffer += 5;
            strategyFailedCounter ++;
            return null;
        }

        strategyFailedCounter = 0;
        Map<StateTransition, Double> transitionsToRejectRatio = graph.getTransitionsToRejectRatio(this, newState, countLimit);
        if (transitionsToRejectRatio != null && !transitionsToRejectRatio.isEmpty()) {
            double sum = 0.0;
            for (Map.Entry<StateTransition, Double> entry: transitionToScore.entrySet()) {
                double originalValue = entry.getValue();
                Double rejectRatio = transitionsToRejectRatio.get(entry.getKey());
                if (rejectRatio == null || metNoTargetCounter >= 80) {
                    sum += originalValue;
                } else {
                    // too long non-taget sequence, reduce reject ratio
                    if (metNoTargetCounter >= 30) {
                        rejectRatio *= ((double)(80 - metNoTargetCounter)) / 50;
                    }

                    double newValue = originalValue * (1.0 - rejectRatio);
                    entry.setValue(newValue);
                    sum += newValue;
                }
            }

            if (sum == 0.0) {
                System.out.println("[APE_MT_WARNING] Reject all transitions with probability 1.0");
                earlyStageBuffer += 5;
                return null;
            }
            if (sum > 1.1) {
                throw new RuntimeException();
            }

            // normalize
            for (Map.Entry<StateTransition, Double> entry: transitionToScore.entrySet()) {
                entry.setValue(entry.getValue() / sum);
            }
        }

        double randomValue = random.nextDouble();
        double cur = 0.0;
        StateTransition chosenTransition = null;
        StateTransition lastTransition = null;
        for (Map.Entry<StateTransition, Double> entry: transitionToScore.entrySet()) {
            lastTransition = entry.getKey();
            cur += entry.getValue();
            if (cur > randomValue) {
                chosenTransition = entry.getKey();
                break;
            }
        }
        if (chosenTransition == null) {
            chosenTransition = lastTransition;
        }
        System.out.println(String.format("[APE_MT_DEBUG] candidate transition = %s with prob %.3f", chosenTransition, transitionToScore.get(chosenTransition)));
        lastChosenStateTransition = chosenTransition;
        return transitionToAction.get(chosenTransition);
    }
    protected ModelAction selectNewActionEpsilonGreedyRandomly() {
        ModelAction back = newState.getBackAction();
        if (back.isValid()) {
            if (back.isUnvisited()) {
                Logger.iprintln("Select Back because Back action is unvisited.");
                return back;
            }
        }
        if (egreedy()) { // TODO: this is different from Sarsa.
            Logger.iformat("Try to select the least visited action.");
            return newState.greedyPickLeastVisited(ActionFilter.ENABLED_VALID);
        }
        Logger.iformat("Try to randomly select a visited action.");
        return newState.randomlyPickAction(getRandom(), ActionFilter.ENABLED_VALID);
    }

    public void onRefillBuffer(Subsequence seq) {
        logEvent(TargetEventType.FILL_BUFFER);
    }

    protected ModelAction fillTransition(State[] states) {
        return fillTransition(states, fillTransitionsByHistory, fallbackToGraphTransition);
    }

    protected ModelAction fillTransition(State[] states, boolean byHistory, boolean fallback) {
        if (byHistory) {
            Subsequence path = getGraph().fillTransitionsByHistory(states);
            if (path != null) {
                return refillBuffer(path);
            }
            Logger.println("Fill transitions by history failed!");
            if (!fallback) {
                return null;
            }
        }
        List<Subsequence> selectedPaths = new ArrayList<Subsequence>();
        getGraph().fillTransitions(selectedPaths, states);
        if (!selectedPaths.isEmpty()) {
            int total = selectedPaths.size();
            int index = this.nextInt(total);
            Subsequence path = selectedPaths.get(index);
            refillBuffer(path);
            return path.getFirstAction();
        }
        return null;
    }

    protected ModelAction moveToState(State start, State end, boolean includeBack) {
        List<Subsequence> selectedPaths = new ArrayList<Subsequence>();
        getGraph().moveToState(selectedPaths, start, end, includeBack, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = RandomHelper.randomPick(selectedPaths);
            return refillBuffer(path);
        }
        return null;
    }

    /**
     * ABA is a circle in the model graph, e.g., a path starting from A, B and eventualy ending at A.
     * @param fromA
     * @param toB
     * @param verbose
     * @return
     */
    protected boolean doABA(State fromA, State toB, boolean verbose) {
        if (isDialogState(toB)) {
            if (verbose) {
                Logger.iformat("Never move to a saturated dialog state (%s) in ABA.", toB);
            }
            return false;
        }
        if (fromA.getActivity().equals(toB.getActivity())) {
            if (toB.getVisitedCount() >= fromA.getVisitedCount()) {
                if (verbose) {
                    Logger.iformat("Never move from a cold state (%s) to a hot state (%s) in ABA.", fromA, toB);
                }
                return false;
            }
        } else {
            ActivityNode AA = getGraph().getActivityNode(fromA.getActivity());
            ActivityNode BA = getGraph().getActivityNode(toB.getActivity());
            if (BA.getVisitedCount() >= AA.getVisitedCount()) {
                if (verbose) {
                    Logger.iformat("Never move from a cold activity (%s)(%s) to a hot activity (%s)(%s) in ABA.",
                            fromA, AA, toB, BA);
                }
                return false;
            }
        }
        Logger.iformat("Move from A (%s) to B (%s).", fromA, toB);
        return true;
    }

    protected List<ModelAction> getGreedyActions(State to) {
        return getGreedyActions(null, to);
    }

    protected boolean isGreedyState(State to) {
        return isGreedyState(null, to);
    }

    /**
     * We will visited every ``unvisited action''.
     * The identifier of an ``unvisited action'' may be its target (widget) only or
     * the combination of its state (the set of all widgets) and target (widget).
     * @param from
     * @param to
     * @return
     */
    protected List<ModelAction> getGreedyActions(State from, State to) {
        if (useActionDiffer) {
            return this.actionDiffer.getUnsaturated(from, to);
        }
        return to.collectActions(new BaseActionFilter() {

            @Override
            public boolean include(ModelAction action) {
                if (!ActionFilter.ENABLED_VALID.include(action)) {
                    return false;
                }
                if (!action.requireTarget()) {
                    return false;
                }
                if (action.isScroll()) {
                    return action.isUnvisited();
                }
                return getGraph().isActionUnvisitedByName(action);
            }

        });
    }

    protected boolean isGreedyState(State from, State to) {
        return !getGreedyActions(from, to).isEmpty();
    }

    protected ModelAction selectNewActionEarlyStageForABAInternal() {
        if (currentState == null) {
            return null;
        }
        State A = newState;
        State B = currentState;
        Logger.iprintln("Check A*BA->B, try to move from A to B.");
        Logger.iformat("> - A: %s", A);
        Logger.iformat("> - B: %s", B);
        if (!doABA(A, B, true)) {
            return null;
        }
        List<Subsequence> forwardPaths = getGraph().moveToState(A, B, false);
        if (forwardPaths.isEmpty()) {
            Logger.iprintln("A cannot reach B.");
            return null;
        }
        List<Subsequence> backwardPaths = getGraph().moveToState(B, A, true);
        if (backwardPaths.isEmpty()) {
            Logger.iprintln("B cannot reach A.");
            return null;
        }
        Subsequence path = randomPickShortest(forwardPaths);
        Logger.iformat("Try to find a path (1/%d) to a greedy state that we want to greedily visit in ABA.", forwardPaths.size());
        path.print();
        StateTransition[] edges = path.getEdges();
        int lastIndex = -1;
        State lastTarget = null;
        for (int i  = 0; i < edges.length; i++) {
            State source = edges[i].getSource();
            State target = edges[i].getTarget();
            if (!doABA(source, target, true)) {
                break;
            }
            if (isGreedyState(source, target)) {
                if (lastTarget == null) {
                    lastIndex = i;
                    lastTarget = target;
                } else {
                    if (target.getActivity().equals(lastTarget.getActivity())) {
                        if (target.getVisitedCount() < lastTarget.getVisitedCount()) {
                            lastIndex = i;
                            lastTarget = target; // prefer colder state
                        }
                    } else { // prefer colder activity
                        ActivityNode an1 = getGraph().getActivityNode(target.getActivity());
                        ActivityNode an2 = getGraph().getActivityNode(lastTarget.getActivity());
                        if (an1.getVisitedCount() < an2.getVisitedCount()) {
                            lastIndex = i;
                            lastTarget = target;
                        }
                    }
                }
            }
        }
        if (lastIndex < 0) {
            Logger.iprintln("No state or path that we want to greedily visit in ABA.");
            return null;
        }
        if (lastIndex != edges.length - 1) {
            List<StateTransition> newEdges = new ArrayList<>(lastIndex + 1);
            for (int i = 0; i <= lastIndex; i++) {
                newEdges.add(edges[i]);
            }
            path = new Subsequence(newEdges);
            Logger.iformat("Update B from %s to %s", B, path.getLastState());
            B = path.getLastState();
        }
        ModelAction action = refillBuffer(path);
        if (action != null) {
            Logger.iformat("Move from A (%s) to B (%s) for ABA within %d steps that start from action %s",
                    A, B, path.size(), action);
        }
        return action;
    }

    protected ModelAction selectNewActionBackToActivity() {
        if (this.backToActivity == null) {
            return null;
        }
        Stack stack = AndroidDevice.getFocusedStack();
        if (stack.getTasks().isEmpty()) {
            this.backToActivity = null;
            return null;
        }
        if (stack.getTasks().get(0).getActivities().isEmpty()) {
            this.backToActivity = null;
            return null;
        }
        stack.dump();
        int totalActivities = 0;
        int onStackIndex = -1;
        for (Task task : stack.getTasks()) {
            for (Activity a : task.getActivities()) {
                if (a.activity.getClassName().equals(backToActivity.activity)) {
                    onStackIndex = totalActivities;
                }
                totalActivities ++;
            }
        }
        if (totalActivities == 1 || onStackIndex == -1) {
            this.backToActivity = null;
            return null;
        }
        if (totalActivities > 1) { // not the topmost
            if (onStackIndex != 0) {
                if (newState.isBackEnabled()) {
                    Logger.iformat("Backtrack to %s, total=%d", backToActivity.activity, totalActivities);
                    return newState.getBackAction();
                }
            } else { // top most trivial activity
                Logger.iformat("Backtrack stopped at %s, total=%d", backToActivity.activity, totalActivities);
                this.backToActivity = null;
                return null;
            }

        }
        return null;
    }

    /**
     * Explore connected component first.
     * @return
     */
    protected ModelAction selectNewActionEarlyStageForABA() {
        return selectNewActionEarlyStageForABAInternal();
    }

    @Override
    public String getLoggerName() {
        return "TARGET";
    }

    @Override
    public void onActivityStopped() {
        super.onActivityStopped();
        metTargetCounter = 0;
        strategyFailedCounter = 0;
        currentScoreReducRatio = stateScoreRatio;
    }

    protected boolean egreedy() {
        double v = ape.getRandom().nextDouble();
        Logger.iformat("EGreedy value=%f, epsilon=%f.", v, epsilon);
        if (v < epsilon) {
            return false;
        }
        return true;
    }

    protected ModelAction selectNewActionEarlyStageForward() {
        // A simple Depth First
        ModelAction action = selectNewActionEarlyStageForABA();
        if (action != null) {
            return action;
        }
        return selectNewActionEarlyStageForwardGreedy();
    }

    protected Set<ActivityNode> collectTrivialActivities() {
        ActivityNode[] activities = getGraph().getActivityNodes();
        if (activities.length <= trivialActivityRankThreshold) {
            return Collections.emptySet();
        }
        Arrays.sort(activities, new Comparator<ActivityNode>() {

            @Override
            public int compare(ActivityNode o1, ActivityNode o2) {
                int ret = o1.getVisitedCount() - o2.getVisitedCount();
                if (ret != 0) {
                    return ret;
                }
                return o1.activity.compareTo(o2.activity);
            }

        });
        int median = getMedianVisitedCount(activities);
        int mean = getMeanVisitedCount(activities);
        int threshold = Math.max(median, mean);
        Set<ActivityNode> trivialActivities = new HashSet<>();
        for (int i = 0; i < activities.length; i++) {
            if (activities[i].getVisitedCount() <= threshold) {
                if (isTrivialActivity(activities[i])) {
                    trivialActivities.add(activities[i]);
                }
            } else {
                break;
            }
        }
        return trivialActivities;
    }

    /**
     * A heuristic of identifying trivial activity.
     * @param activityNode
     * @return
     */
    private boolean isTrivialActivity(ActivityNode activityNode) {
        int stateSize = activityNode.getStates().size();
        float visitedRate = activityNode.getVisitedRate();
        int visitCount = activityNode.getVisitedCount();
        if (stateSize < 5) { // simple activity
            if (visitCount > stateSize >> 2) { // hard to visit
                if (visitedRate > 0.8) {
                    return false; // e.g., Login, About, Help, Feedback
                } else {
                    return true;
                }
            } else {
                return true;
            }
        } else {
            if (visitCount > stateSize >> 2) {
                if (visitedRate > 0.5) {
                    return false; // Login
                } else {
                    return true;
                }
            } else { // less visited
                return true;
            }
        }
    }

    protected ModelAction selectNewActionForTrivialActivity() {
        final Set<ActivityNode> trivialActivities = collectTrivialActivities();
        if (trivialActivities.isEmpty()) {
            return null;
        }
        ActivityNode an = getGraph().getActivityNode(newState.getActivity());
        if (trivialActivities.contains(an)) {
            return null;
        }
        Logger.iprintln("List trivial activities.");
        for (ActivityNode a : trivialActivities) {
            Logger.iformat("- %s", a);
        }
        int pathLength = Integer.MAX_VALUE;
        SubsequenceFilter filter = new SubsequenceFilter() {

            @Override
            public boolean include(Subsequence path) {
                if (path.isClosed()) {
                    return false;
                }
                State last = path.getLastState();
                ActivityNode an = getGraph().getActivityNode(last.getActivity());
                if (!trivialActivities.contains(an)) {
                    return false;
                }
                if (isGreedyState(last)) {
                    return true;
                }
                return last.firstEnabledUnvisitedValidAction() != null;
            }

            @Override
            public boolean extend(Subsequence path, StateTransition edge) {
                if (edge.action.isBack()) {
                    return false;
                }
                if (!ActionFilter.ENABLED_VALID.include(edge.action)) {
                    return false;
                }
                return edge.isStrong();
            }

        };
        {
            List<Subsequence> selectedPaths = getGraph().findShortestPaths(newState, filter, pathLength);
            if (!selectedPaths.isEmpty()) {
                Subsequence path = RandomHelper.randomPick(selectedPaths);
                Logger.iformat("Find a path (1/%d) to a trivial activity %s.", selectedPaths.size(),
                        getGraph().getActivityNode(path.getLastState().getActivity()));
                return refillBuffer(path);
            }
        }
        if (doBackToTrivialActivity) {
            return backToTrivialActivity(trivialActivities);
        }
        return null;
    }

    private ModelAction backToTrivialActivity(Set<ActivityNode> trivialActivities) {
        Stack taskStack = AndroidDevice.getFocusedStack();
        taskStack.dump();
        ActivityNode topActivity = null;
        for (Task task : taskStack.getTasks()) {
            for (Activity a : task.getActivities()) {
                Logger.iformat("Checking activity %s", a.activity.getClassName());
                ActivityNode taskAN = getGraph().getActivityNode(a.activity.getClassName());
                if (taskAN != null && trivialActivities.contains(taskAN)) {
                    topActivity = taskAN;
                }
            }
        }
        if (topActivity == null) {
            return null;
        }
        if (newState.isBackEnabled()) {
            Logger.iformat("Try to backtrack to trivial activity %s", topActivity.activity);
            backToActivity = topActivity;
            return newState.getBackAction();
        }
        return null;
    }

    private int getMedianVisitedCount(ActivityNode[] activities) {
        int total = activities.length;
        if (total == 0) {
            return -1;
        }
        return activities[total >> 1].getVisitedCount();
    }

    private int getMeanVisitedCount(ActivityNode[] activities) {
        int total = activities.length;
        if (total == 0) {
            return -1;
        }
        int count = 0;
        for (ActivityNode an : activities) {
            count += an.getVisitedCount();
        }
        return count / total;
    }

    public void alertHalf() {
        System.out.println("[APE_MT] Half time/counter consumed");
        strategyChangeReady = true;
        if (getGraph().hasMetTargetMethod()) {
            strategyChanged = true;
            System.out.println("[APE_MT] TargetAgent: strategy changed");
        }
    }

    public boolean isTargetMode() {
        return strategyChanged;
    }

    protected ModelAction selectNewActionEarlyStageForwardGreedy() {
        assertEmptyActionBuffer();
        ModelAction action = findGreedyActionForward(currentState, newState);
        if (action != null) {
            return action;
        }
        return null;
    }

    protected ModelAction selectNewActionEarlyStageBackwardGreedy() {
        ModelAction action = findGreedyActionBackward(currentState, newState);
        if (action != null) {
            return action;
        }
        return null;
    }

    protected boolean hasGreedyActionForward(State state) {
        List<ModelAction> actions = getGreedyActions(state);
        if (!actions.isEmpty()) {
            return true;
        }
        // 2). Greedy action in the neighbor hood.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(state, greedySubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            return true;
        }
        return false;
    }

    protected Subsequence randomPickShortest(List<Subsequence> subsequences) {
        Collections.sort(subsequences, new Comparator<Subsequence>() {

            @Override
            public int compare(Subsequence o1, Subsequence o2) {
                return o1.size() - o2.size();
            }

        });
        return subsequences.get(0);
    }

    protected ModelAction findGreedyActionForward(State prev, State next) {
        // 0-step
        ModelAction action = null;
        // 1). Greedy action on this state.
        List<ModelAction> actions = getGreedyActions(prev, next); // this.actionDiffer.getUnsaturated(currentState, newState, true);
        if (!actions.isEmpty()) {
            action = RandomHelper.randomPickWithPriority(actions);
            if (action != null) {
                Logger.iprintln("Find a greedy action in 0-step");
                // super.disableRestart();
                checkDisableRestart(action);
                return action;
            }
        }
        for (ModelAction a : next.targetedActions()) {
            if (!ActionFilter.ENABLED_VALID_UNVISITED.include(a)) { // unvisited hence no transitions.
                continue;
            }
            State t = getGraph().getNameGlobalTarget(a);
            if (t != null && isGreedyState(next, t)) {
                Logger.iformat("Find a greedy state %s via a global action %s.", t, a);
                return a;
            }
        }
        // 2). Greedy action in the neighbor hood.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(next, greedySubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = randomPickShortest(selectedPaths);
            Logger.iformat("Find a path (1/%d) to a greedy state %s.", selectedPaths.size(), path.getLastState());
            if (path.size() <= 1) {
                ModelAction firstAction = path.getFirstAction();
                checkDisableRestart(firstAction);
            }
            return refillBuffer(path);
        }
        return null;
    }

    protected void checkDisableRestart(ModelAction action) {
        if (model.getGraph().isActionUnvisitedByName(action)) {
            super.disableRestart();
        }
    }

    protected ModelAction findGreedyActionBackward(State prev, State next) {
        // 3). Back
        if (ActionFilter.ENABLED_VALID_UNVISITED.include(next.getBackAction())) {
            Logger.iprintln("Find a greedy (unvisited) back action.");
            return next.getBackAction();
        }
        // 4). Backtrack to parent.
        List<Subsequence> selectedPaths = getGraph().findShortestPaths(next, backtrackSubsequenceFilter, Integer.MAX_VALUE);
        if (!selectedPaths.isEmpty()) {
            Subsequence path = randomPickShortest(selectedPaths);
            Logger.iformat("Find a path (1/%d) to state %s for backtrack.", selectedPaths.size(), path.getLastState());
            if (path.size() <= 1) {
                checkDisableRestart(path.getFirstAction());
            }
            return refillBuffer(path);
        }
        return null;
    }

    protected void printStrategy() {
        Logger.format("Target Strategy: buffer size (%d)", actionBufferSize());
        printCounters();
        newState.printActions();
    }

    @Override
    public void onActivityBlocked(ComponentName blockedActivity) {

    }

    @Override
    public boolean onVoidGUITree(int retryCounter) {
        return false;
    }

    @Override
    public void onBadState(int lastBadStateCount, int badStateCounter) {
        logEvent(TargetEventType.BAD_STATE);
    }

    protected int getThrottleForNewAction(State state, ModelAction action) {
        int throttle = super.getThrottleForNewAction(state, action);
        return throttle += 200;

        // throttle += 200;
        // Collection<StateTransition> edges = getGraph().getOutStateTransitions(action);
        // int sz = edges.size();
        // if (sz > 1) {
        //     Integer unreliablePoint = actionToDoubtPoint.get(action);
        //     if (unreliablePoint != null && unreliablePoint != 0) {
        //         throttle += (unreliablePoint % 5) * 400;
        //         System.out.println(String.format("[APE_MT_WARNING] action %s (unreliable=%d) set throttle %d", action.toString(), sz, throttle));
        //         if (unreliablePoint == 10) {
        //             System.out.println("[APE_MT_WARNING] request restart");
        //             requestRestart();
        //         }
        //     }
        // }
        // return throttle;
    }

}
