/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder.steps;

import com.google.cloud.tools.jib.ProjectInfo;
import com.google.cloud.tools.jib.builder.ProgressEventDispatcher;
import com.google.cloud.tools.jib.builder.TimerEventDispatcher;
import com.google.cloud.tools.jib.builder.steps.BuildAndCacheApplicationLayerStep.CachedLayerAndType;
import com.google.cloud.tools.jib.builder.steps.PullBaseImageStep.BaseImageWithAuthorization;
import com.google.cloud.tools.jib.cache.CachedLayer;
import com.google.cloud.tools.jib.configuration.BuildConfiguration;
import com.google.cloud.tools.jib.configuration.ContainerConfiguration;
import com.google.cloud.tools.jib.event.events.LogEvent;
import com.google.cloud.tools.jib.image.Image;
import com.google.cloud.tools.jib.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.jib.image.json.HistoryEntry;
import com.google.common.collect.ImmutableList;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/** Builds a model {@link Image}. */
class BuildImageStep implements Callable<Image> {

  private static final String DESCRIPTION = "Building container configuration";

  private final BuildConfiguration buildConfiguration;
  private final ProgressEventDispatcher.Factory progressEventDispatcherFactory;
  private final Future<BaseImageWithAuthorization> pullBaseImageStep;
  private final Future<List<CachedLayer>> pullAndCacheBaseImageLayersStep;
  private final Future<List<CachedLayer>> buildAndCacheApplicationLayerSteps;

  BuildImageStep(
      BuildConfiguration buildConfiguration,
      ProgressEventDispatcher.Factory progressEventDispatcherFactory,
      Future<BaseImageWithAuthorization> pullBaseImageStep,
      Future<List<CachedLayer>> pullAndCacheBaseImageLayersStep,
      Future<List<CachedLayer>> buildAndCacheApplicationLayerSteps) {
    this.buildConfiguration = buildConfiguration;
    this.progressEventDispatcherFactory = progressEventDispatcherFactory;
    this.pullBaseImageStep = pullBaseImageStep;
    this.pullAndCacheBaseImageLayersStep = pullAndCacheBaseImageLayersStep;
    this.buildAndCacheApplicationLayerSteps = buildAndCacheApplicationLayerSteps;
  }

  @Override
  private Image call() throws ExecutionException, LayerPropertyNotFoundException {
    List<BaseImageWithAuthorization> baseImageWithAuthorizations = pullBaseImageStep.get();
    List<CachedLayer> baseImageLayers = pullAndCacheBaseImageLayersStep.get();
    List<CachedLayerAndType> applicationLayers = buildAndCacheApplicationLayerSteps.get();

    try (ProgressEventDispatcher ignored =
            progressEventDispatcherFactory.create("building image format", 1);
        TimerEventDispatcher ignored2 =
            new TimerEventDispatcher(buildConfiguration.getEventHandlers(), DESCRIPTION)) {
      // Constructs the image.
      Image.Builder imageBuilder = Image.builder(buildConfiguration.getTargetFormat());
      Image baseImage = pullBaseImageStep.get().getBaseImage();
      ContainerConfiguration containerConfiguration =
          buildConfiguration.getContainerConfiguration();

      // Base image layers
      for (CachedLayer baseImageLayer : baseImageLayers) {
        imageBuilder.addLayer(baseImageLayer);
      }

      // Passthrough config and count non-empty history entries
      int nonEmptyLayerCount = 0;
      for (HistoryEntry historyObject : baseImage.getHistory()) {
        imageBuilder.addHistory(historyObject);
        if (!historyObject.hasCorrespondingLayer()) {
          nonEmptyLayerCount++;
        }
      }
      imageBuilder
          .setArchitecture(baseImage.getArchitecture())
          .setOs(baseImage.getOs())
          .addEnvironment(baseImage.getEnvironment())
          .addLabels(baseImage.getLabels())
          .setHealthCheck(baseImage.getHealthCheck())
          .addExposedPorts(baseImage.getExposedPorts())
          .addVolumes(baseImage.getVolumes())
          .setWorkingDirectory(baseImage.getWorkingDirectory());

      // Add history elements for non-empty layers that don't have one yet
      Instant layerCreationTime =
          containerConfiguration == null
              ? ContainerConfiguration.DEFAULT_CREATION_TIME
              : containerConfiguration.getCreationTime();
      for (int count = 0;
          count < pullAndCacheBaseImageLayersStep.get().size() - nonEmptyLayerCount;
          count++) {
        imageBuilder.addHistory(
            HistoryEntry.builder()
                .setCreationTimestamp(layerCreationTime)
                .setComment("auto-generated by Jib")
                .build());
      }

      // Add built layers/configuration
      for (CachedLayerAndType applicationLayer : buildAndCacheApplicationLayerSteps.get()) {
        imageBuilder
            .addLayer(applicationLayer)
            .addHistory(
                HistoryEntry.builder()
                    .setCreationTimestamp(layerCreationTime)
                    .setAuthor("Jib")
                    .setCreatedBy(buildConfiguration.getToolName() + ":" + ProjectInfo.VERSION)
                    .setComment(buildAndCacheApplicationLayerStep.getLayerType())
                    .build());
      }
      if (containerConfiguration != null) {
        imageBuilder
            .addEnvironment(containerConfiguration.getEnvironmentMap())
            .setCreated(containerConfiguration.getCreationTime())
            .setUser(containerConfiguration.getUser())
            .setEntrypoint(computeEntrypoint(baseImage, containerConfiguration))
            .setProgramArguments(computeProgramArguments(baseImage, containerConfiguration))
            .addExposedPorts(containerConfiguration.getExposedPorts())
            .addVolumes(containerConfiguration.getVolumes())
            .addLabels(containerConfiguration.getLabels());
        if (containerConfiguration.getWorkingDirectory() != null) {
          imageBuilder.setWorkingDirectory(containerConfiguration.getWorkingDirectory().toString());
        }
      }

      // Gets the container configuration content descriptor.
      return imageBuilder.build();
    }
  }

  /**
   * Computes the image entrypoint. If {@link ContainerConfiguration#getEntrypoint()} is null, the
   * entrypoint is inherited from the base image. Otherwise {@link
   * ContainerConfiguration#getEntrypoint()} is returned.
   *
   * @param baseImage the base image
   * @param containerConfiguration the container configuration
   * @return the container entrypoint
   */
  @Nullable
  private ImmutableList<String> computeEntrypoint(
      Image baseImage, ContainerConfiguration containerConfiguration) {
    boolean shouldInherit =
        baseImage.getEntrypoint() != null && containerConfiguration.getEntrypoint() == null;

    ImmutableList<String> entrypointToUse =
        shouldInherit ? baseImage.getEntrypoint() : containerConfiguration.getEntrypoint();

    if (entrypointToUse != null) {
      String logSuffix = shouldInherit ? " (inherited from base image)" : "";
      String message = "Container entrypoint set to " + entrypointToUse + logSuffix;
      buildConfiguration.getEventHandlers().dispatch(LogEvent.lifecycle(""));
      buildConfiguration.getEventHandlers().dispatch(LogEvent.lifecycle(message));
    }

    return entrypointToUse;
  }

  /**
   * Computes the image program arguments. If {@link ContainerConfiguration#getEntrypoint()} and
   * {@link ContainerConfiguration#getProgramArguments()} are null, the program arguments are
   * inherited from the base image. Otherwise {@link ContainerConfiguration#getProgramArguments()}
   * is returned.
   *
   * @param baseImage the base image
   * @param containerConfiguration the container configuration
   * @return the container program arguments
   */
  @Nullable
  private ImmutableList<String> computeProgramArguments(
      Image baseImage, ContainerConfiguration containerConfiguration) {
    boolean shouldInherit =
        baseImage.getProgramArguments() != null
            // Inherit CMD only when inheriting ENTRYPOINT.
            && containerConfiguration.getEntrypoint() == null
            && containerConfiguration.getProgramArguments() == null;

    ImmutableList<String> programArgumentsToUse =
        shouldInherit
            ? baseImage.getProgramArguments()
            : containerConfiguration.getProgramArguments();

    if (programArgumentsToUse != null) {
      String logSuffix = shouldInherit ? " (inherited from base image)" : "";
      String message = "Container program arguments set to " + programArgumentsToUse + logSuffix;
      buildConfiguration.getEventHandlers().dispatch(LogEvent.lifecycle(message));
    }

    return programArgumentsToUse;
  }
}
