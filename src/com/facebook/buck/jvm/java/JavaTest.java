/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.HasPostBuildSteps;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ZipFileTraversal;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class JavaTest
    extends AbstractBuildRule
    implements TestRule, HasClasspathEntries, HasRuntimeDeps, HasPostBuildSteps,
        ExternalTestRunnerRule, ExportDependencies {

  public static final Flavor COMPILED_TESTS_LIBRARY_FLAVOR = ImmutableFlavor.of("testsjar");

  // TODO(#9027062): Migrate this to a PackagedResource so we don't make assumptions
  // about the ant build.
  private static final Path TESTRUNNER_CLASSES =
      Paths.get(
          System.getProperty(
              "buck.testrunner_classes",
              new File("build/testrunner/classes").getAbsolutePath()));

  private final JavaLibrary compiledTestsLibrary;

  private final ImmutableSet<Path> additionalClasspathEntries;
  @AddToRuleKey
  private final JavaRuntimeLauncher javaRuntimeLauncher;

  @AddToRuleKey
  private final ImmutableList<String> vmArgs;

  private final ImmutableMap<String, String> nativeLibsEnvironment;

  @Nullable
  private CompiledClassFileFinder compiledClassFileFinder;

  private final ImmutableSet<Label> labels;

  private final ImmutableSet<String> contacts;

  private final Optional<Level> stdOutLogLevel;
  private final Optional<Level> stdErrLogLevel;

  @AddToRuleKey
  private final TestType testType;

  private final Optional<Long> testRuleTimeoutMs;

  @AddToRuleKey
  private final ImmutableMap<String, String> env;

  private final Path pathToTestLogs;

  private static final int TEST_CLASSES_SHUFFLE_SEED = 0xFACEB00C;

  private static final Logger LOG = Logger.get(JavaTest.class);

  @Nullable
  private ImmutableList<JUnitStep> junits;

  @AddToRuleKey
  private final boolean runTestSeparately;

  @AddToRuleKey
  private final ForkMode forkMode;

  public JavaTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      JavaLibrary compiledTestsLibrary,
      ImmutableSet<Path> additionalClasspathEntries,
      Set<Label> labels,
      Set<String> contacts,
      TestType testType,
      JavaRuntimeLauncher javaRuntimeLauncher,
      List<String> vmArgs,
      Map<String, String> nativeLibsEnvironment,
      Optional<Long> testRuleTimeoutMs,
      ImmutableMap<String, String> env,
      boolean runTestSeparately,
      ForkMode forkMode,
      Optional<Level> stdOutLogLevel,
      Optional<Level> stdErrLogLevel) {
    super(params, resolver);
    this.compiledTestsLibrary = compiledTestsLibrary;
    this.additionalClasspathEntries = additionalClasspathEntries;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.nativeLibsEnvironment = ImmutableMap.copyOf(nativeLibsEnvironment);
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.testType = testType;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.env = env;
    this.runTestSeparately = runTestSeparately;
    this.forkMode = forkMode;
    this.stdOutLogLevel = stdOutLogLevel;
    this.stdErrLogLevel = stdErrLogLevel;
    this.pathToTestLogs = getPathToTestOutputDirectory().resolve("logs.txt");
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  /**
   * @param context That may be useful in producing the bootclasspath entries.
   */
  protected ImmutableSet<Path> getBootClasspathEntries(ExecutionContext context) {
    return ImmutableSet.of();
  }

  private Path getClassPathFile() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/classpath-file");
  }

  private JUnitStep getJUnitStep(
      ExecutionContext executionContext,
      TestRunningOptions options,
      Optional<Path> outDir,
      Optional<Path> robolectricLogPath,
      Set<String> testClassNames) {

    Iterable<String> reorderedTestClasses =
        reorderClasses(testClassNames, options.isShufflingTests());

    ImmutableList<String> properVmArgs = amendVmArgs(
        this.vmArgs,
        executionContext.getTargetDevice());

    BuckEventBus buckEventBus = executionContext.getBuckEventBus();
    BuildId buildId = buckEventBus.getBuildId();
    TestSelectorList testSelectorList = options.getTestSelectorList();
    JUnitJvmArgs args = JUnitJvmArgs.builder()
        .setTestType(testType)
        .setDirectoryForTestResults(outDir)
        .setClasspathFile(getClassPathFile())
        .setTestRunnerClasspath(TESTRUNNER_CLASSES)
        .setCodeCoverageEnabled(executionContext.isCodeCoverageEnabled())
        .setDebugEnabled(executionContext.isDebugEnabled())
        .setPathToJavaAgent(options.getPathToJavaAgent())
        .setBuildId(buildId)
        .setBuckModuleBaseSourceCodePath(getBuildTarget().getBasePath())
        .setStdOutLogLevel(stdOutLogLevel)
        .setStdErrLogLevel(stdErrLogLevel)
        .setRobolectricLogPath(robolectricLogPath)
        .setExtraJvmArgs(properVmArgs)
        .addAllTestClasses(reorderedTestClasses)
        .setDryRun(options.isDryRun())
        .setTestSelectorList(testSelectorList)
        .build();

    return new JUnitStep(
        getProjectFilesystem(),
        nativeLibsEnvironment,
        testRuleTimeoutMs,
        env,
        javaRuntimeLauncher,
        args);
  }

  /**
   * Returns the underlying java library containing the compiled tests.
   */
  public JavaLibrary getCompiledTestsLibrary() {
    return compiledTestsLibrary;
  }

  /**
   * Runs the tests specified by the "srcs" of this class. If this rule transitively depends on
   * other {@code java_test()} rules, then they will be run separately.
   */
  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      TestReportingCallback testReportingCallback) {

    // If no classes were generated, then this is probably a java_test() that declares a number of
    // other java_test() rules as deps, functioning as a test suite. In this case, simply return an
    // empty list of commands.
    Set<String> testClassNames = getClassNamesForSources();
    LOG.debug("Testing these classes: %s", testClassNames.toString());
    if (testClassNames.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path pathToTestOutput = getPathToTestOutputDirectory();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), pathToTestOutput));
    if (forkMode() == ForkMode.PER_TEST) {
      ImmutableList.Builder<JUnitStep> junitsBuilder = ImmutableList.builder();
      for (String testClass: testClassNames) {
        junitsBuilder.add(
          getJUnitStep(
              executionContext,
              options,
              Optional.of(pathToTestOutput),
              Optional.of(pathToTestLogs),
              Collections.singleton(testClass))
        );
      }
      junits = junitsBuilder.build();
    } else {
      junits = ImmutableList.of(
          getJUnitStep(
            executionContext,
            options,
            Optional.of(pathToTestOutput),
            Optional.of(pathToTestLogs),
            testClassNames)
      );
    }
    steps.addAll(junits);
    return steps.build();
  }

  private static Iterable<String> reorderClasses(Set<String> testClassNames, boolean shuffle) {
    Random rng;
    if (shuffle) {
      // This is a runtime-seed reorder, which always produces a new order.
      rng = new Random(System.nanoTime());
    } else {
      // This is fixed-seed reorder, which always produces the same order.
      // We still want to do this in order to decouple the test order from the
      // filesystem/environment.
      rng = new Random(TEST_CLASSES_SHUFFLE_SEED);
    }
    List<String> reorderedClassNames = Lists.newArrayList(testClassNames);
    Collections.shuffle(reorderedClassNames, rng);
    return reorderedClassNames;
  }

  @VisibleForTesting
  ImmutableList<String> amendVmArgs(
      ImmutableList<String> existingVmArgs,
      Optional<TargetDevice> targetDevice) {
    ImmutableList.Builder<String> vmArgs = ImmutableList.builder();
    vmArgs.addAll(existingVmArgs);
    onAmendVmArgs(vmArgs, targetDevice);
    return vmArgs.build();
  }

  /**
   * Override this method if you need to amend vm args. Subclasses are required
   * to call super.onAmendVmArgs(...).
   */
  protected void onAmendVmArgs(ImmutableList.Builder<String> vmArgsBuilder,
                               Optional<TargetDevice> targetDevice) {
    if (!targetDevice.isPresent()) {
      return;
    }

    TargetDevice device = targetDevice.get();
    if (device.isEmulator()) {
      vmArgsBuilder.add("-Dbuck.device=emulator");
    } else {
      vmArgsBuilder.add("-Dbuck.device=device");
    }
    if (device.hasIdentifier()) {
      vmArgsBuilder.add("-Dbuck.device.id=" + device.getIdentifier());
    }
  }

  @Override
  public boolean hasTestResultFiles() {
    // It is possible that this rule was not responsible for running any tests because all tests
    // were run by its deps. In this case, return an empty TestResults.
    Set<String> testClassNames = getClassNamesForSources();
    if (testClassNames.isEmpty()) {
      return true;
    }

    Path outputDirectory = getProjectFilesystem()
        .getPathForRelativePath(getPathToTestOutputDirectory());
    for (String testClass : testClassNames) {
      // We never use cached results when using test selectors, so there's no need to incorporate
      // the .test_selectors suffix here if we are using selectors.
      Path testResultFile = outputDirectory.resolve(testClass + ".xml");
      if (!Files.isRegularFile(testResultFile)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "__java_test_%s_output__");
  }

  /**
   * @return a test case result, named "main", signifying a failure of the entire test class.
   */
  private TestCaseSummary getTestClassFailedSummary(
      String testClass,
      String message,
      long time) {
    return new TestCaseSummary(
        testClass,
        ImmutableList.of(
            new TestResultSummary(
                testClass,
                "main",
                ResultType.FAILURE,
                time,
                message,
                "",
                "",
                "")));
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext context,
      final boolean isUsingTestSelectors,
      final boolean isDryRun) {
    final ImmutableSet<String> contacts = getContacts();
    return () -> {
      // It is possible that this rule was not responsible for running any tests because all tests
      // were run by its deps. In this case, return an empty TestResults.
      Set<String> testClassNames = getClassNamesForSources();
      if (testClassNames.isEmpty()) {
        return TestResults.of(
            getBuildTarget(),
            ImmutableList.of(),
            contacts,
            labels.stream()
                .map(Object::toString)
                .collect(MoreCollectors.toImmutableSet()));
      }

      List<TestCaseSummary> summaries = Lists.newArrayListWithCapacity(testClassNames.size());
      for (String testClass : testClassNames) {
        String testSelectorSuffix = "";
        if (isUsingTestSelectors) {
          testSelectorSuffix += ".test_selectors";
        }
        if (isDryRun) {
          testSelectorSuffix += ".dry_run";
        }
        String path = String.format("%s%s.xml", testClass, testSelectorSuffix);
        Path testResultFile = getProjectFilesystem().getPathForRelativePath(
            getPathToTestOutputDirectory().resolve(path));
        if (!isUsingTestSelectors && !Files.isRegularFile(testResultFile)) {
          String message;
          for (JUnitStep junit: Preconditions.checkNotNull(junits)) {
            if (junit.hasTimedOut()) {
              message = "test timed out before generating results file";
            } else {
              message = "test exited before generating results file";
            }
            summaries.add(
                getTestClassFailedSummary(
                    testClass,
                    message,
                    testRuleTimeoutMs.orElse(0L)));
          }
        // Not having a test result file at all (which only happens when we are using test
        // selectors) is interpreted as meaning a test didn't run at all, so we'll completely
        // ignore it.  This is another result of the fact that JUnit is the only thing that can
        // definitively say whether or not a class should be run.  It's not possible, for example,
        // to filter testClassNames here at the buck end.
        } else if (Files.isRegularFile(testResultFile)) {
          summaries.add(XmlTestResultParser.parse(testResultFile));
        }
      }

      return TestResults.builder()
          .setBuildTarget(getBuildTarget())
          .setTestCases(summaries)
          .setContacts(contacts)
          .setLabels(labels.stream()
              .map(Object::toString)
              .collect(MoreCollectors.toImmutableSet()))
          .addTestLogPaths(getProjectFilesystem().resolve(pathToTestLogs))
          .build();
    };
  }

  private Set<String> getClassNamesForSources() {
    if (compiledClassFileFinder == null) {
      compiledClassFileFinder = new CompiledClassFileFinder(this);
    }
    return compiledClassFileFinder.getClassNamesForSources();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // Nothing to build, this is a test-only rule
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return compiledTestsLibrary.getPathToOutput();
  }

  @Override
  public ImmutableSet<Path> getTransitiveClasspaths() {
    return compiledTestsLibrary.getTransitiveClasspaths();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return compiledTestsLibrary.getTransitiveClasspathDeps();
  }

  @Override
  public ImmutableSet<Path> getImmediateClasspaths() {
    return compiledTestsLibrary.getImmediateClasspaths();
  }

  @Override
  public ImmutableSet<Path> getOutputClasspaths() {
    return compiledTestsLibrary.getOutputClasspaths();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return ImmutableSortedSet.of(compiledTestsLibrary);
  }

  @VisibleForTesting
  static class CompiledClassFileFinder {

    private final Set<String> classNamesForSources;

    CompiledClassFileFinder(JavaTest rule) {
      Path outputPath;
      Path relativeOutputPath = rule.getPathToOutput();
      if (relativeOutputPath != null) {
        outputPath = rule.getProjectFilesystem().resolve(relativeOutputPath);
      } else {
        outputPath = null;
      }
      classNamesForSources = getClassNamesForSources(
          rule.compiledTestsLibrary.getJavaSrcs(),
          outputPath,
          rule.getProjectFilesystem());
    }

    public Set<String> getClassNamesForSources() {
      return classNamesForSources;
    }

    /**
     * When a collection of .java files is compiled into a directory, that directory will have a
     * subfolder structure that matches the package structure of the input .java files. In general,
     * the .java files will be 1:1 with the .class files with two notable exceptions:
     * (1) There will be an additional .class file for each inner/anonymous class generated. These
     *     types of classes are easy to identify because they will contain a '$' in the name.
     * (2) A .java file that defines multiple top-level classes (yes, this can exist:
     *     http://stackoverflow.com/questions/2336692/java-multiple-class-declarations-in-one-file)
     *     will generate multiple .class files that do not have '$' in the name.
     * In this method, we perform a strict check for (1) and use a heuristic for (2). It is possible
     * to filter out the type (2) situation with a stricter check that aligns the package
     * directories of the .java files and the .class files, but it is a pain to implement.
     * If this heuristic turns out to be insufficient in practice, then we can fix it.
     *
     * @param sources paths to .java source files that were passed to javac
     * @param jarFilePath jar where the generated .class files were written
     */
    @VisibleForTesting
    static ImmutableSet<String>  getClassNamesForSources(
        Set<Path> sources,
        @Nullable Path jarFilePath,
        ProjectFilesystem projectFilesystem) {
      if (jarFilePath == null) {
        return ImmutableSet.of();
      }

      final Set<String> sourceClassNames = Sets.newHashSetWithExpectedSize(sources.size());
      for (Path path : sources) {
        // We support multiple languages in this rule - the file extension doesn't matter so long
        // as the language supports filename == classname.
        sourceClassNames.add(MorePaths.getNameWithoutExtension(path));
      }

      final ImmutableSet.Builder<String> testClassNames = ImmutableSet.builder();
      Path jarFile = projectFilesystem.getPathForRelativePath(jarFilePath);
      ZipFileTraversal traversal = new ZipFileTraversal(jarFile) {

        @Override
        public void visit(ZipFile zipFile, ZipEntry zipEntry) {
          final String name = new File(zipEntry.getName()).getName();

          // Ignore non-.class files.
          if (!name.endsWith(".class")) {
            return;
          }

          // As a heuristic for case (2) as described in the Javadoc, make sure the name of the
          // .class file matches the name of a .java/.scala/.xxx file.
          String nameWithoutDotClass = name.substring(0, name.length() - ".class".length());
          if (!sourceClassNames.contains(nameWithoutDotClass)) {
            return;
          }

          // Make sure it is a .class file that corresponds to a top-level .class file and not an
          // inner class.
          if (!name.contains("$")) {
            String fullyQualifiedNameWithDotClassSuffix = zipEntry.getName().replace('/', '.');
            String className = fullyQualifiedNameWithDotClassSuffix
                .substring(0, fullyQualifiedNameWithDotClassSuffix.length() - ".class".length());
            testClassNames.add(className);
          }
        }
      };
      try {
        traversal.traverse();
      } catch (IOException e) {
        // There's nothing sane to do here. The jar file really should exist.
        throw new RuntimeException(e);
      }

      return testClassNames.build();
    }
  }

  @Override
  public boolean runTestSeparately() {
    return runTestSeparately;
  }

  public ForkMode forkMode() {
    return forkMode;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        // By the end of the build, all the transitive Java library dependencies *must* be available
        // on disk, so signal this requirement via the {@link HasRuntimeDeps} interface.
        .addAll(
            compiledTestsLibrary.getTransitiveClasspathDeps().stream()
                .filter(rule -> !this.equals(rule))
                .iterator())
        // It's possible that the user added some tool as a dependency, so make sure we promote
        // this rules first-order deps to runtime deps, so that these potential tools are available
        // when this test runs.
        .addAll(compiledTestsLibrary.getDeps())
        .build();
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions options) {
    JUnitStep jUnitStep =
        getJUnitStep(
            executionContext,
            options,
            Optional.empty(),
            Optional.empty(),
            getClassNamesForSources()
            );
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("junit")
        .setCommand(jUnitStep.getShellCommandInternal(executionContext))
        .setEnv(jUnitStep.getEnvironmentVariables(executionContext))
        .setLabels(getLabels())
        .setContacts(getContacts())
        .build();
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps() {
    return ImmutableList.<Step>builder()
        .add(new MkdirStep(getProjectFilesystem(), getClassPathFile().getParent()))
        .add(
            new AbstractExecutionStep("write classpath file") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) throws IOException {
                ImmutableSet<Path> classpathEntries = ImmutableSet.<Path>builder()
                    .addAll(compiledTestsLibrary.getTransitiveClasspaths())
                    .addAll(additionalClasspathEntries)
                    .addAll(getBootClasspathEntries(context))
                    .build();
                getProjectFilesystem().writeLinesToPath(
                    Iterables.transform(classpathEntries, Object::toString),
                    getClassPathFile());
                return StepExecutionResult.SUCCESS;
              }
            })
        .build();
  }

}
