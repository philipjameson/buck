/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.js;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxProperties;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleAndroidTools;
import com.facebook.buck.shell.GenruleBuildable;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Genrule specialized for the creation of JS bundles. Functionally, it is mostly the same as any
 * other genrule, except that it provides an additional set of environment variables and outputs
 * suitable for production and consumption of JS bundles.
 *
 * <p>JsBundleGenrule produces four outputs:
 *
 * <ul>
 *   <li>1) "source-map", the source map generated by the bundle for minified JavaScript files
 *   <li>2) "misc", a miscellanous folder of stuff (?)
 *   <li>3) "deps-file", a dependency file indicating which bundles this bundle depends on
 *   <li>4) "js", a folder containing the actual minified javascript.
 * </ul>
 *
 * <p>Bundles can rewrite the misc, source map, and deps file outputs with the <code>
 * rewriteSourcemap</code> <code>rewriteMisc</code>, and <code>rewriteDepsFile</code> arguments.
 * Doing so causes this genrule to create these files and folders in the output directory; otherwise
 * the outputs will be exported verbatim from the bundles that this bundle depends on.
 */
public class JsBundleGenrule extends Genrule
    implements AndroidPackageable, HasRuntimeDeps, JsBundleOutputs, JsDependenciesOutputs {

  /** Output label for the #source-map output. */
  private static final OutputLabel SOURCE_MAP = OutputLabel.of("source-map");

  /** Output label for the #misc output. */
  private static final OutputLabel MISC = OutputLabel.of("misc");

  /** Output label for the #dependencies output. */
  private static final OutputLabel DEPS_FILE = OutputLabel.of("deps-file");

  /** Output label for the JS output. */
  private static final OutputLabel JS = OutputLabel.of("js");

  private final JsBundleOutputs jsBundle;
  private final JsDependenciesOutputs jsDependencies;

  public JsBundleGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      JsBundleGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> environmentExpansionSeparator,
      Optional<AndroidTools> androidTools,
      JsBundleOutputs jsBundle,
      JsDependenciesOutputs jsDependencies,
      String bundleName) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        new Buildable(
            buildTarget,
            projectFilesystem,
            sandboxExecutionStrategy,
            args.getSrcs(),
            cmd,
            bash,
            cmdExe,
            args.getType(),
            Optional.empty(),
            Optional.of(
                generateOuts(
                    buildTarget,
                    projectFilesystem,
                    args.getRewriteSourcemap(),
                    args.getRewriteMisc(),
                    args.getRewriteDepsFile(),
                    bundleName)),
            false,
            true,
            environmentExpansionSeparator.orElse(" "),
            Optional.empty(),
            androidTools.map(tools -> GenruleAndroidTools.of(tools, buildTarget, resolver)),
            false,
            jsBundle.getSourcePathToOutput(),
            jsBundle.getSourcePathToMisc(),
            jsBundle.getSourcePathToResources(),
            jsBundle.getSourcePathToSourceMap(),
            jsDependencies.getSourcePathToDepsFile(),
            jsBundle.getBundleName(),
            bundleName,
            JsFlavors.PLATFORM_DOMAIN
                .getFlavor(buildTarget.getFlavors())
                .map(flavor -> flavor.getName())
                .orElse(""),
            buildTarget.getFlavors().contains(JsFlavors.RELEASE),
            args.getRewriteSourcemap(),
            args.getRewriteMisc(),
            args.getRewriteDepsFile(),
            args.getSkipResources()));
    this.jsBundle = jsBundle;
    this.jsDependencies = jsDependencies;
  }

  /** Buildable implementation for {@link JsBundleGenrule}. */
  private static class Buildable extends GenruleBuildable {

    /** SourcePath to the dependent JS bundle's output. */
    @AddToRuleKey private final SourcePath jsBundleOutput;

    /** SourcePath to the dependent JS bundle's misc folder. */
    @AddToRuleKey private final SourcePath jsBundleMisc;

    /** SourcePath to the dependent JS bundle's resources folder. */
    @AddToRuleKey private final SourcePath jsBundleResources;

    /** SourcePath to the dependent JS bundle's source map. */
    @AddToRuleKey private final SourcePath jsBundleSourceMap;

    /** SourcePath to the dependent JS bundle's dependency file. */
    @AddToRuleKey private final SourcePath jsBundleDepsFile;

    /** Name of the JS bundle that this bundle depends on. */
    @AddToRuleKey private final String jsBundleName;

    /** Name of this JS bundle. */
    @AddToRuleKey private final String jsBundleNameOut;

    /** Platform to build this JS bundle for. */
    @AddToRuleKey private final String platform;

    /** Whether or not we are doing a release build of this bundle. */
    @AddToRuleKey private final boolean isRelease;

    /** Whether or not this genrule intends to rewrite the sourcemap. */
    @AddToRuleKey private final boolean rewriteSourcemap;

    /** Whether or not this genrule intends to rewrite the misc folder. */
    @AddToRuleKey private final boolean rewriteMisc;

    /** Whether or not this genrule intends to rewrite the deps file. */
    @AddToRuleKey private final boolean rewriteDepsFile;

    /** Whether or not this genrule should skip resources when packaging this bundle. */
    @AddToRuleKey private final boolean skipResources;

    public Buildable(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        SandboxExecutionStrategy sandboxExecutionStrategy,
        SourceSet srcs,
        Optional<Arg> cmd,
        Optional<Arg> bash,
        Optional<Arg> cmdExe,
        Optional<String> type,
        Optional<String> out,
        Optional<ImmutableMap<String, ImmutableSet<String>>> outs,
        boolean enableSandboxingInGenrule,
        boolean isCacheable,
        String environmentExpansionSeparator,
        Optional<SandboxProperties> sandboxProperties,
        Optional<GenruleAndroidTools> androidTools,
        boolean executeRemotely,
        SourcePath jsBundleOutput,
        SourcePath jsBundleMisc,
        SourcePath jsBundleResources,
        SourcePath jsBundleSourceMap,
        SourcePath jsBundleDepsFile,
        String jsBundleName,
        String jsBundleNameOut,
        String platform,
        boolean isRelease,
        boolean rewriteSourcemap,
        boolean rewriteMisc,
        boolean rewriteDepsFile,
        boolean skipResources) {
      super(
          buildTarget,
          filesystem,
          sandboxExecutionStrategy,
          srcs,
          cmd,
          bash,
          cmdExe,
          type,
          out,
          outs,
          enableSandboxingInGenrule,
          isCacheable,
          environmentExpansionSeparator,
          sandboxProperties,
          androidTools,
          executeRemotely);
      this.jsBundleOutput = jsBundleOutput;
      this.jsBundleMisc = jsBundleMisc;
      this.jsBundleResources = jsBundleResources;
      this.jsBundleSourceMap = jsBundleSourceMap;
      this.jsBundleDepsFile = jsBundleDepsFile;
      this.jsBundleName = jsBundleName;
      this.jsBundleNameOut = jsBundleNameOut;
      this.platform = platform;
      this.isRelease = isRelease;
      this.rewriteSourcemap = rewriteSourcemap;
      this.rewriteMisc = rewriteMisc;
      this.rewriteDepsFile = rewriteDepsFile;
      this.skipResources = skipResources;
    }

    @Override
    public void addEnvironmentVariables(
        SourcePathResolverAdapter pathResolver,
        OutputPathResolver outputPathResolver,
        ProjectFilesystem filesystem,
        Path srcPath,
        Path tmpPath,
        ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
      ImmutableMap.Builder<String, String> parentBuilder = ImmutableMap.builder();
      super.addEnvironmentVariables(
          pathResolver, outputPathResolver, filesystem, srcPath, tmpPath, parentBuilder);

      // An important difference between this buildable and GenruleBuildable is that, when a rule
      // has multiple outputs, GenruleBuildable sets the OUT environment variable to the root of the
      // output directory. For legacy reasons, this rule must point OUT at the JS folder.
      OutputPath outJsFolder = Iterables.getOnlyElement(getOutputs(JS));
      environmentVariablesBuilder
          .put(
              "OUT",
              filesystem
                  .getPathForRelativePath(outputPathResolver.resolvePath(outJsFolder))
                  .toString())
          .put("JS_DIR", pathResolver.getAbsolutePath(jsBundleOutput).toString())
          .put("JS_BUNDLE_NAME", jsBundleName)
          .put("JS_BUNDLE_NAME_OUT", jsBundleNameOut)
          .put("MISC_DIR", pathResolver.getAbsolutePath(jsBundleMisc).toString())
          .put("PLATFORM", platform)
          .put("RELEASE", isRelease ? "1" : "")
          .put("RES_DIR", pathResolver.getAbsolutePath(jsBundleResources).toString())
          .put("SOURCEMAP", pathResolver.getAbsolutePath(jsBundleSourceMap).toString())
          .put("DEPENDENCIES", pathResolver.getAbsolutePath(jsBundleDepsFile).toString());

      if (rewriteSourcemap) {
        OutputPath outSourceMapPath = Iterables.getOnlyElement(getOutputs(SOURCE_MAP));
        environmentVariablesBuilder.put(
            "SOURCEMAP_OUT",
            filesystem
                .getPathForRelativePath(outputPathResolver.resolvePath(outSourceMapPath))
                .toString());
      }
      if (rewriteMisc) {
        OutputPath outMiscPath = Iterables.getOnlyElement(getOutputs(MISC));
        environmentVariablesBuilder.put(
            "MISC_OUT",
            filesystem
                .getPathForRelativePath(outputPathResolver.resolvePath(outMiscPath))
                .toString());
      }
      if (rewriteDepsFile) {
        OutputPath outDepsPath = Iterables.getOnlyElement(getOutputs(DEPS_FILE));
        environmentVariablesBuilder.put(
            "DEPENDENCIES_OUT",
            filesystem
                .getPathForRelativePath(outputPathResolver.resolvePath(outDepsPath))
                .toString());
      }

      // Combine our env map with GenruleBuildable's env map, with the OUT mapping from the parent
      // filtered out (we have overridden it).
      environmentVariablesBuilder.putAll(
          parentBuilder.build().entrySet().stream()
              .filter(entry -> !entry.getKey().equals("OUT"))
              .collect(Collectors.toSet()));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableList<Step> buildSteps =
          super.getBuildSteps(context, filesystem, outputPathResolver, buildCellPathFactory);
      OptionalInt lastRmStep =
          IntStream.range(0, buildSteps.size())
              .map(x -> buildSteps.size() - 1 - x)
              .filter(i -> buildSteps.get(i) instanceof RmStep)
              .findFirst();

      Preconditions.checkState(
          lastRmStep.isPresent(), "Genrule is expected to have at least on RmDir step");

      OutputPath output = Iterables.getOnlyElement(getOutputs(JS));
      ImmutableList.Builder<Step> builder =
          ImmutableList.<Step>builder()
              // First, all Genrule steps including the last RmDir step are added
              .addAll(buildSteps.subList(0, lastRmStep.getAsInt() + 1))
              // Our MkdirStep must run after all RmSteps created by Genrule to prevent immediate
              // deletion of the directory. It must, however, run before the genrule command itself
              // runs.
              .add(
                  MkdirStep.of(
                      BuildCellRelativePath.fromCellRelativePath(
                          context.getBuildCellRootPath(),
                          filesystem,
                          outputPathResolver.resolvePath(output))));

      if (rewriteSourcemap) {
        // If the genrule rewrites the source map, we have to create the parent dir, and record
        // the build artifact
        OutputPath outputPathToSourceMap = Iterables.getOnlyElement(getOutputs(SOURCE_MAP));
        builder.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    filesystem,
                    outputPathResolver.resolvePath(outputPathToSourceMap).getParent())));
      }

      if (rewriteMisc) {
        // If the genrule rewrites the misc folder, we have to create the corresponding dir, and
        // record its contents
        OutputPath miscDirPath = Iterables.getOnlyElement(getOutputs(MISC));
        builder.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    filesystem,
                    outputPathResolver.resolvePath(miscDirPath))));
      }

      // Last, we add all remaining genrule commands after the last RmStep
      return builder
          .addAll(buildSteps.subList(lastRmStep.getAsInt() + 1, buildSteps.size()))
          .build();
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return JsBundleOutputs.super.getSourcePathToOutput();
  }

  @Override
  public SourcePath getSourcePathToSourceMap() {
    Optional<OutputPath> sourceMap = getOptionalOutput(SOURCE_MAP);
    if (!sourceMap.isPresent()) {
      return jsBundle.getSourcePathToSourceMap();
    }

    return getSourcePath(sourceMap.get());
  }

  @Override
  public SourcePath getSourcePathToResources() {
    return ((Buildable) getBuildable()).jsBundleResources;
  }

  @Override
  public SourcePath getSourcePathToMisc() {
    Optional<OutputPath> misc = getOptionalOutput(MISC);
    if (!misc.isPresent()) {
      return jsBundle.getSourcePathToMisc();
    }

    return getSourcePath(misc.get());
  }

  @Override
  public SourcePath getSourcePathToDepsFile() {
    Optional<OutputPath> deps = getOptionalOutput(DEPS_FILE);
    if (!deps.isPresent()) {
      return jsDependencies.getSourcePathToDepsFile();
    }

    return getSourcePath(deps.get());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    boolean skipResources = ((Buildable) getBuildable()).skipResources;
    return !skipResources && jsBundle instanceof AndroidPackageable
        ? ((AndroidPackageable) jsBundle).getRequiredPackageables(ruleResolver)
        : ImmutableList.of();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addAssetsDirectory(getBuildTarget(), getSourcePathToOutput());
  }

  @Override
  public String getBundleName() {
    return ((Buildable) getBuildable()).jsBundleNameOut;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.of(jsBundle.getBuildTarget());
  }

  @Override
  public JsDependenciesOutputs getJsDependenciesOutputs(ActionGraphBuilder graphBuilder) {
    return this;
  }

  /**
   * Helper method for extracting an optional output, returning the OutputPath if one exists.
   *
   * @param label The output label for an output that may exist
   * @return OutputPath to the output if the output exists, Optional.empty() otherwise.
   */
  private Optional<OutputPath> getOptionalOutput(OutputLabel label) {
    ImmutableSet<OutputLabel> allLabels = getBuildable().getOutputLabels();
    if (allLabels.contains(label)) {
      return Optional.of(Iterables.getOnlyElement(getBuildable().getOutputs(label)));
    }
    return Optional.empty();
  }

  /**
   * Helper method for generating the <code>outs</code> map to give to {@link GenruleBuildable}. If
   * this genrule rewrites the source map, misc, or deps file, this method returns a corresponding
   * output for the mutated file.
   *
   * @param target This build target
   * @param filesystem The current filesystem
   * @param rewriteSourcemap Whether or not we will rewrite the sourcemap
   * @param rewriteMisc Whether or not we will rewrite the misc folder
   * @param rewriteDepsFile Whether or not we will rewrite the deps file
   * @param bundleName The name of the bundle we are building
   * @return An output map for this genrule
   */
  private static ImmutableMap<String, ImmutableSet<String>> generateOuts(
      BuildTarget target,
      ProjectFilesystem filesystem,
      boolean rewriteSourcemap,
      boolean rewriteMisc,
      boolean rewriteDepsFile,
      String bundleName) {
    ImmutableMap.Builder<String, ImmutableSet<String>> builder = ImmutableMap.builder();
    if (rewriteSourcemap) {
      Path sourceMap = filesystem.getPath(JsUtil.getSourcemapPath(bundleName));
      builder.put(SOURCE_MAP.toString(), ImmutableSet.of(sourceMap.toString()));
    }

    if (rewriteMisc) {
      Path miscPath = filesystem.getPath("misc");
      builder.put(MISC.toString(), ImmutableSet.of(miscPath.toString()));
    }

    if (rewriteDepsFile) {
      Path depsPath = filesystem.getPath(String.format("%s.deps", target.getShortName()));
      builder.put(DEPS_FILE.toString(), ImmutableSet.of(depsPath.toString()));
    }

    builder.put(JS.toString(), ImmutableSet.of(JsBundleOutputs.JS_DIR_NAME));
    return builder.build();
  }
}
