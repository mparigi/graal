package org.graalvm.compiler.phases.common.inlining.policy;

import static org.graalvm.compiler.core.common.GraalOptions.InlineEverything;
import static org.graalvm.compiler.core.common.GraalOptions.LimitInlinedInvokes;
import static org.graalvm.compiler.core.common.GraalOptions.MaximumDesiredSize;
import static org.graalvm.compiler.core.common.GraalOptions.MaximumInliningSize;
import static org.graalvm.compiler.core.common.GraalOptions.SmallCompiledLowLevelGraphSize;
import static org.graalvm.compiler.core.common.GraalOptions.TraceInlining;
import static org.graalvm.compiler.core.common.GraalOptions.TrivialInliningSize;

import java.util.Map;
import java.util.Vector;
import java.util.Arrays;
import java.util.*;

import org.graalvm.compiler.debug.CounterKey;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.spi.Replacements;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.common.inlining.InliningUtil;
import org.graalvm.compiler.phases.common.inlining.info.InlineInfo;
import org.graalvm.compiler.phases.common.inlining.walker.MethodInvocation;

import org.neuroph.core.NeuralNetwork;

import java.io.*;

public class NEATInliningPolicy extends AbstractInliningPolicy {

  private static final CounterKey inliningStoppedByMaxDesiredSizeCounter = DebugContext.counter("InliningStoppedByMaxDesiredSize");

  private static NeuralNetwork nn = NeuralNetwork.load(System.getProperty("inline.root") + "/graal/compiler/network.nnet");

  public NEATInliningPolicy(Map<Invoke, Double> hints) {
    super(hints);
  }

  @Override
  public boolean continueInlining(StructuredGraph currentGraph) {
    if (InliningUtil.getNodeCount(currentGraph) >= MaximumDesiredSize.getValue(currentGraph.getOptions())) {
      DebugContext debug = currentGraph.getDebug();
      InliningUtil.logInliningDecision(debug, "inlining is cut off by MaximumDesiredSize");
      inliningStoppedByMaxDesiredSizeCounter.increment(debug);
      return false;
    }
    return true;
  }

  @Override
  public Decision isWorthInlining(Replacements replacements, MethodInvocation invocation, InlineInfo calleeInfo, int inliningDepth, boolean fullyProcessed) {
    OptionValues options = calleeInfo.graph().getOptions();
    final boolean isTracing = TraceInlining.getValue(options);
    final InlineInfo info = invocation.callee();

    if (InlineEverything.getValue(options)) {
      InliningUtil.traceInlinedMethod(info, inliningDepth, fullyProcessed, "inline everything");
      return InliningPolicy.Decision.YES.withReason(isTracing, "inline everything");
    }

    if (isIntrinsic(replacements, info)) {
      InliningUtil.traceInlinedMethod(info, inliningDepth, fullyProcessed, "intrinsic");
      return InliningPolicy.Decision.YES.withReason(isTracing, "intrinsic");
    }

    if (info.shouldInline()) {
      InliningUtil.traceInlinedMethod(info, inliningDepth, fullyProcessed, "forced inlining");
      return InliningPolicy.Decision.YES.withReason(isTracing, "forced inlining");
    }

    final double relevance = invocation.relevance();
    int nodes = info.determineNodeCount();
    int lowLevelGraphSize = previousLowLevelGraphSize(info);
    double invokes = determineInvokeProbability(info);

    // run the network
    Vector<Double> input = new Vector<Double>(Arrays.asList((double)lowLevelGraphSize, (double)nodes, fullyProcessed ? 1.0 : 0.0, invokes, relevance));
    nn.reset();
    nn.setInput(input);
    nn.calculate();

    double nn_out_1 = nn.getOutput().get(0);

    if (nn_out_1 > 0.5) {
      return InliningPolicy.Decision.NO.withReason(isTracing, "don't inline because NN said not to");
    }
    return InliningPolicy.Decision.YES.withReason(isTracing, "inline because NN said to");
  }
}
