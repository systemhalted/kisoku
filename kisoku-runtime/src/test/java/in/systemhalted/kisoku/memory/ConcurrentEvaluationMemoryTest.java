package in.systemhalted.kisoku.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import in.systemhalted.kisoku.testutil.MemoryTestUtils;
import in.systemhalted.kisoku.testutil.MemoryTestUtils.MemorySnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests verifying memory constraints under concurrent evaluation.
 *
 * <p>LoadedRuleset must be thread-safe and memory-efficient when accessed by multiple threads
 * simultaneously. These tests verify heap stays bounded during parallel execution.
 */
class ConcurrentEvaluationMemoryTest {

  private static final long ONE_GB = 1024L * 1024 * 1024;
  private static final int INPUT_COLUMNS = 20;
  private static final int OUTPUT_COLUMNS = 10;

  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  /**
   * Verifies heap stays bounded with 200 concurrent evaluations.
   *
   * <p>Spawns 200 threads, each running 100 evaluations. Tracks peak heap usage during execution to
   * ensure memory doesn't exceed budget.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void heapStaysBoundedUnder200ConcurrentEvaluations(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests") || Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runMemoryTests=true or -Dkisoku.runScaleTests=true to enable this test.");

    long rows = 500_000L;
    int threadCount = 200;
    int evalsPerThread = 100;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, INPUT_COLUMNS, OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      // Pre-create inputs to avoid allocation during measurement
      List<DecisionInput> inputs = new ArrayList<>(evalsPerThread);
      for (int i = 0; i < evalsPerThread; i++) {
        inputs.add(DecisionTableFixtures.createTestInput(i, INPUT_COLUMNS));
      }

      // Warmup
      for (DecisionInput input : inputs) {
        ruleset.evaluate(input);
      }

      MemorySnapshot baseline = MemoryTestUtils.stableSnapshot();
      System.out.printf(
          "Before concurrent execution (%d threads × %d evals): %s%n",
          threadCount, evalsPerThread, baseline.format());

      AtomicLong peakHeap = new AtomicLong(baseline.heapBytes());
      AtomicInteger completedEvals = new AtomicInteger(0);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);

      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      // Submit all evaluation tasks
      for (int t = 0; t < threadCount; t++) {
        final int threadId = t;
        executor.submit(
            () -> {
              try {
                startLatch.await(); // Wait for all threads to be ready

                for (int i = 0; i < evalsPerThread; i++) {
                  DecisionInput input = inputs.get((threadId + i) % inputs.size());
                  DecisionOutput output = ruleset.evaluate(input);

                  // Consume output to prevent dead code elimination
                  if (output.ruleId() != null) {
                    completedEvals.incrementAndGet();
                  }

                  // Periodically sample heap usage
                  if (i % 20 == 0) {
                    long currentHeap = MemoryTestUtils.usedHeapBytes();
                    peakHeap.updateAndGet(peak -> Math.max(peak, currentHeap));
                  }
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      // Start all threads simultaneously
      startLatch.countDown();

      // Wait for completion with timeout
      boolean completed = doneLatch.await(5, TimeUnit.MINUTES);
      executor.shutdownNow();

      assertTrue(completed, "Concurrent evaluations timed out");

      int totalExpected = threadCount * evalsPerThread;
      System.out.printf("Completed %d/%d evaluations%n", completedEvals.get(), totalExpected);
      assertEquals(totalExpected, completedEvals.get(), "Not all evaluations completed");

      MemorySnapshot afterExecution = MemoryTestUtils.stableSnapshot();
      System.out.printf("After concurrent execution: %s%n", afterExecution.format());
      System.out.printf(
          "Peak heap during execution: %s%n", MemoryTestUtils.formatBytes(peakHeap.get()));

      // Verify peak heap stayed under 1GB
      assertTrue(
          peakHeap.get() < ONE_GB,
          String.format(
              "Peak heap %s exceeded 1GB during concurrent execution",
              MemoryTestUtils.formatBytes(peakHeap.get())));

      // Verify heap delta is bounded (allows for thread stacks and evaluation contexts)
      long heapDelta = afterExecution.heapBytes() - baseline.heapBytes();
      long maxAcceptableGrowth = 200 * 1024 * 1024L; // 200MB

      System.out.printf("Heap delta: %s%n", MemoryTestUtils.formatBytes(heapDelta));

      assertTrue(
          heapDelta < maxAcceptableGrowth,
          String.format(
              "Heap grew by %s after concurrent execution, exceeds %s threshold",
              MemoryTestUtils.formatBytes(heapDelta),
              MemoryTestUtils.formatBytes(maxAcceptableGrowth)));
    }
  }

  /**
   * Verifies no memory leaks with repeated concurrent batches.
   *
   * <p>Runs multiple rounds of concurrent evaluations, checking that memory stabilizes rather than
   * growing unboundedly.
   */
  @Tag("memory")
  @Test
  void noMemoryLeakWithRepeatedConcurrentBatches(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests"),
        "Set -Dkisoku.runMemoryTests=true to enable this test.");

    long rows = 100_000L;
    int threadCount = 50;
    int evalsPerThread = 50;
    int rounds = 5;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, INPUT_COLUMNS, OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      List<DecisionInput> inputs = new ArrayList<>(evalsPerThread);
      for (int i = 0; i < evalsPerThread; i++) {
        inputs.add(DecisionTableFixtures.createTestInput(i, INPUT_COLUMNS));
      }

      long[] heapAfterRounds = new long[rounds];

      for (int round = 0; round < rounds; round++) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
          final int threadId = t;
          executor.submit(
              () -> {
                try {
                  for (int i = 0; i < evalsPerThread; i++) {
                    DecisionInput input = inputs.get((threadId + i) % inputs.size());
                    ruleset.evaluate(input);
                  }
                } finally {
                  doneLatch.countDown();
                }
              });
        }

        doneLatch.await(1, TimeUnit.MINUTES);
        executor.shutdownNow();

        MemorySnapshot snapshot = MemoryTestUtils.stableSnapshot();
        heapAfterRounds[round] = snapshot.heapBytes();
        System.out.printf(
            "Round %d: %s%n", round + 1, MemoryTestUtils.formatBytes(heapAfterRounds[round]));
      }

      // Check that heap usage stabilizes (last round not significantly higher than middle rounds)
      long firstRound = heapAfterRounds[0];
      long lastRound = heapAfterRounds[rounds - 1];
      double growthRatio = (double) lastRound / firstRound;

      System.out.printf("Growth ratio (round %d / round 1): %.2fx%n", rounds, growthRatio);

      assertTrue(
          growthRatio < 1.5,
          String.format(
              "Memory grew %.2fx from round 1 to round %d, suggesting a leak. "
                  + "Round 1: %s, Round %d: %s",
              growthRatio,
              rounds,
              MemoryTestUtils.formatBytes(firstRound),
              rounds,
              MemoryTestUtils.formatBytes(lastRound)));
    }
  }
}
