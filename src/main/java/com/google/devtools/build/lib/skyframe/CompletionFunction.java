// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.analysis.AspectCompleteEvent;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TargetCompleteEvent;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper.ArtifactsToBuild;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.AspectCompletionValue.AspectCompletionKey;
import com.google.devtools.build.lib.skyframe.AspectValue.AspectKey;
import com.google.devtools.build.lib.skyframe.TargetCompletionValue.TargetCompletionKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException2;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * CompletionFunction builds the artifactsToBuild collection of a {@link ConfiguredTarget}.
 */
public final class CompletionFunction<TValue extends SkyValue, TResult extends SkyValue>
    implements SkyFunction {

  /** A strategy for completing the build. */
  interface Completor<TValue, TResult extends SkyValue> {

    /** Obtains an analysis result value from environment. */
    TValue getValueFromSkyKey(SkyKey skyKey, Environment env) throws InterruptedException;

    /**
     * Returns the options which determine the artifacts to build for the top-level targets.
     * <p>
     * For the Top level targets we made a conscious decision to include the TopLevelArtifactContext
     * within the SkyKey as an argument to the CompletionFunction rather than a separate SkyKey.
     * As a result we do have <num top level targets> extra SkyKeys for every unique
     * TopLevelArtifactContexts used over the lifetime of Blaze. This is a minor tradeoff,
     * since it significantly improves null build times when we're switching the
     * TopLevelArtifactContexts frequently (common for IDEs), by reusing existing SkyKeys
     * from earlier runs, instead of causing an eager invalidation
     * were the TopLevelArtifactContext modeled as a separate SkyKey.
     */
    TopLevelArtifactContext getTopLevelArtifactContext(SkyKey skyKey);

    /**
     * Returns all artifacts that need to be built to complete the {@code value}
     */
    ArtifactsToBuild getAllArtifactsToBuild(TValue value, TopLevelArtifactContext context);

    /** Creates an event reporting an absent input artifact. */
    Event getRootCauseError(TValue value, Cause rootCause, Environment env);

    /** Creates an error message reporting {@code missingCount} missing input files. */
    MissingInputFileException getMissingFilesException(
        TValue value, int missingCount, Environment env);

    /**
     * Creates a successful completion value.
     */
    TResult createResult(TValue value);

    /** Creates a failed completion value. */
    ExtendedEventHandler.Postable createFailed(TValue value, NestedSet<Cause> rootCauses);

    /** Creates a succeeded completion value. */
    ExtendedEventHandler.Postable createSucceeded(
        SkyKey skyKey, TValue value, TopLevelArtifactContext topLevelArtifactContext);

    /**
     * Extracts a tag given the {@link SkyKey}.
     */
    String extractTag(SkyKey skyKey);
  }

  private static class TargetCompletor
      implements Completor<ConfiguredTargetValue, TargetCompletionValue> {
    @Override
    public ConfiguredTargetValue getValueFromSkyKey(SkyKey skyKey, Environment env)
        throws InterruptedException {
      TargetCompletionKey tcKey = (TargetCompletionKey) skyKey.argument();
      return (ConfiguredTargetValue) env.getValue(tcKey.configuredTargetKey());
    }

    @Override
    public TopLevelArtifactContext getTopLevelArtifactContext(SkyKey skyKey) {
      TargetCompletionKey tcKey = (TargetCompletionKey) skyKey.argument();
      return tcKey.topLevelArtifactContext();
    }

    @Override
    public ArtifactsToBuild getAllArtifactsToBuild(
        ConfiguredTargetValue value, TopLevelArtifactContext topLevelContext) {
      return TopLevelArtifactHelper.getAllArtifactsToBuild(
          value.getConfiguredTarget(), topLevelContext);
    }

    @Override
    public Event getRootCauseError(
        ConfiguredTargetValue ctValue, Cause rootCause, Environment env) {
      Target target = null;
      try {
        target =
            ((PackageValue)
                    env.getValue(
                        PackageValue.key(
                            ctValue.getConfiguredTarget().getLabel().getPackageIdentifier())))
                .getPackage()
                .getTarget(ctValue.getConfiguredTarget().getLabel().getName());
      } catch (NoSuchTargetException | InterruptedException e) {
        throw new IllegalStateException("Failed to retrieve target to get a root cause error.");
      }
      return Event.error(
          target.getLocation(),
          String.format(
              "%s: missing input file '%s'", ctValue.getConfiguredTarget().getLabel(), rootCause));
    }

    @Override
    public MissingInputFileException getMissingFilesException(
        ConfiguredTargetValue value, int missingCount, Environment env) {
      Target target = null;
      try {
        target =
            ((PackageValue)
                    env.getValue(
                        PackageValue.key(
                            value.getConfiguredTarget().getLabel().getPackageIdentifier())))
                .getPackage()
                .getTarget(value.getConfiguredTarget().getLabel().getName());
      } catch (NoSuchTargetException | InterruptedException e) {
        throw new IllegalStateException(
            "Failed to retrieve target to create MissingFilesException.");
      }
      return new MissingInputFileException(
          target.getLocation() + " " + missingCount + " input file(s) do not exist",
          target.getLocation());
    }

    @Override
    public TargetCompletionValue createResult(ConfiguredTargetValue value) {
      return new TargetCompletionValue(value.getConfiguredTarget());
    }

    @Override
    public ExtendedEventHandler.Postable createFailed(
        ConfiguredTargetValue value, NestedSet<Cause> rootCauses) {
      return TargetCompleteEvent.createFailed(value.getConfiguredTarget(), rootCauses);
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return Label.print(
          ((TargetCompletionKey) skyKey.argument()).configuredTargetKey().getLabel());
    }

    @Override
    public ExtendedEventHandler.Postable createSucceeded(
        SkyKey skyKey,
        ConfiguredTargetValue value,
        TopLevelArtifactContext topLevelArtifactContext) {
      ConfiguredTarget target = value.getConfiguredTarget();
      if (((TargetCompletionKey) skyKey.argument()).willTest()) {
        return TargetCompleteEvent.successfulBuildSchedulingTest(target);
      } else {
        ArtifactsToBuild artifactsToBuild =
            TopLevelArtifactHelper.getAllArtifactsToBuild(target, topLevelArtifactContext);
        return TargetCompleteEvent.successfulBuild(
            target, artifactsToBuild.getAllArtifactsByOutputGroup());
      }
    }
  }

  private static class AspectCompletor implements Completor<AspectValue, AspectCompletionValue> {
    @Override
    public AspectValue getValueFromSkyKey(SkyKey skyKey, Environment env)
        throws InterruptedException {
      AspectCompletionKey acKey = (AspectCompletionKey) skyKey.argument();
      AspectKey aspectKey = acKey.aspectKey();
      return (AspectValue) env.getValue(aspectKey);
    }

    @Override
    public TopLevelArtifactContext getTopLevelArtifactContext(SkyKey skyKey) {
      AspectCompletionKey acKey = (AspectCompletionKey) skyKey.argument();
      return acKey.topLevelArtifactContext();
    }

    @Override
    public ArtifactsToBuild getAllArtifactsToBuild(
        AspectValue value, TopLevelArtifactContext topLevelArtifactContext) {
      return TopLevelArtifactHelper.getAllArtifactsToBuild(value, topLevelArtifactContext);
    }

    @Override
    public Event getRootCauseError(AspectValue value, Cause rootCause, Environment env) {
      return Event.error(
          value.getLocation(),
          String.format(
              "%s, aspect %s: missing input file '%s'",
              value.getLabel(),
              value.getConfiguredAspect().getName(),
              rootCause));
    }

    @Override
    public MissingInputFileException getMissingFilesException(
        AspectValue value, int missingCount, Environment env) {
      return new MissingInputFileException(
          value.getLabel()
              + ", aspect "
              + value.getConfiguredAspect().getName()
              + missingCount
              + " input file(s) do not exist",
          value.getLocation());
    }

    @Override
    public AspectCompletionValue createResult(AspectValue value) {
      return new AspectCompletionValue(value);
    }

    @Override
    public ExtendedEventHandler.Postable createFailed(
        AspectValue value, NestedSet<Cause> rootCauses) {
      return AspectCompleteEvent.createFailed(value, rootCauses);
    }

    @Override
    public String extractTag(SkyKey skyKey) {
      return Label.print(((AspectCompletionKey) skyKey.argument()).aspectKey().getLabel());
    }

    @Override
    public ExtendedEventHandler.Postable createSucceeded(
        SkyKey skyKey, AspectValue value, TopLevelArtifactContext topLevelArtifactContext) {
      ArtifactsToBuild artifacts =
          TopLevelArtifactHelper.getAllArtifactsToBuild(value, topLevelArtifactContext);
      return AspectCompleteEvent.createSuccessful(value, artifacts);
    }
  }

  public static SkyFunction targetCompletionFunction() {
    return new CompletionFunction<>(new TargetCompletor());
  }

  public static SkyFunction aspectCompletionFunction() {
    return new CompletionFunction<>(new AspectCompletor());
  }

  private final Completor<TValue, TResult> completor;

  private CompletionFunction(Completor<TValue, TResult> completor) {
    this.completor = completor;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws CompletionFunctionException, InterruptedException {
    TValue value = completor.getValueFromSkyKey(skyKey, env);
    TopLevelArtifactContext topLevelContext = completor.getTopLevelArtifactContext(skyKey);
    if (env.valuesMissing()) {
      return null;
    }

    Map<SkyKey, ValueOrException2<MissingInputFileException, ActionExecutionException>> inputDeps =
        env.getValuesOrThrow(
            ArtifactSkyKey.mandatoryKeys(
                completor.getAllArtifactsToBuild(value, topLevelContext).getAllArtifacts()),
            MissingInputFileException.class,
            ActionExecutionException.class);

    int missingCount = 0;
    ActionExecutionException firstActionExecutionException = null;
    MissingInputFileException missingInputException = null;
    NestedSetBuilder<Cause> rootCausesBuilder = NestedSetBuilder.stableOrder();
    for (Map.Entry<SkyKey, ValueOrException2<MissingInputFileException, ActionExecutionException>>
        depsEntry : inputDeps.entrySet()) {
      Artifact input = ArtifactSkyKey.artifact(depsEntry.getKey());
      try {
        depsEntry.getValue().get();
      } catch (MissingInputFileException e) {
        missingCount++;
        final Label inputOwner = input.getOwner();
        if (inputOwner != null) {
          Cause cause = new LabelCause(inputOwner);
          rootCausesBuilder.add(cause);
          env.getListener().handle(completor.getRootCauseError(value, cause, env));
        }
      } catch (ActionExecutionException e) {
        rootCausesBuilder.addTransitive(e.getRootCauses());
        // Prefer a catastrophic exception as the one we propagate.
        if (firstActionExecutionException == null
            || !firstActionExecutionException.isCatastrophe() && e.isCatastrophe()) {
          firstActionExecutionException = e;
        }
      }
    }

    if (missingCount > 0) {
      missingInputException = completor.getMissingFilesException(value, missingCount, env);
    }

    NestedSet<Cause> rootCauses = rootCausesBuilder.build();
    if (!rootCauses.isEmpty()) {
      env.getListener().post(completor.createFailed(value, rootCauses));
      if (firstActionExecutionException != null) {
        throw new CompletionFunctionException(firstActionExecutionException);
      } else {
        throw new CompletionFunctionException(missingInputException);
      }
    }

    // Only check for missing values *after* reporting errors: if there are missing files in a build
    // with --nokeep_going, there may be missing dependencies during error bubbling, we still need
    // to report the error.
    if (env.valuesMissing()) {
      return null;
    }
    env.getListener().post(completor.createSucceeded(skyKey, value, topLevelContext));
    return completor.createResult(value);
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return completor.extractTag(skyKey);
  }

  private static final class CompletionFunctionException extends SkyFunctionException {

    private final ActionExecutionException actionException;

    public CompletionFunctionException(ActionExecutionException e) {
      super(e, Transience.PERSISTENT);
      this.actionException = e;
    }

    public CompletionFunctionException(MissingInputFileException e) {
      super(e, Transience.TRANSIENT);
      this.actionException = null;
    }

    @Override
    public boolean isCatastrophic() {
      return actionException != null && actionException.isCatastrophe();
    }
  }
}
