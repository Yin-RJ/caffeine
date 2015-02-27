/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.tracing.async;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;
import com.github.benmanes.caffeine.cache.tracing.Tracer;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * A tracing implementation based on a Disruptor multi-producer/single-consumer event processor.
 * The events are written to a ringbuffer and a background thread writes them in batches to a log
 * file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
public final class AsyncTracer implements Tracer {
  public static final String TRACING_FILE = "caffeine.tracing.file";
  public static final String TRACING_FORMAT = "caffeine.tracing.format";
  public static final String TRACING_BUFFER_SIZE = "caffeine.tracing.bufferSize";

  final EventTranslatorOneArg<CacheEvent, CacheEvent> translator;
  final Disruptor<CacheEvent> disruptor;
  final ExecutorService executor;
  final LogEventHandler handler;

  /**
   * Creates a tracer using the default configuration with optional system property overrides. This
   * constructor is typically called through a {@link java.util.ServiceLoader}.
   */
  public AsyncTracer() {
    this(eventHandler(), ringBufferSize(),
        Executors.newSingleThreadExecutor(DaemonThreadFactory.INSTANCE));
  }

  /**
   * Creates a tracer using the supplied parameters. This constructor is typically called through
   * tests that supply the dependencies.
   *
   * @param handler the event handler that writes to a log
   * @param ringBufferSize the size of the ring buffer
   * @param executor an {@link Executor} to asynchronously processor events
   */
  @SuppressWarnings("unchecked")
  public AsyncTracer(LogEventHandler handler, int ringBufferSize, ExecutorService executor) {
    this.handler = handler;
    this.executor = executor;
    this.translator = (event, seq, template) -> event.copyFrom(template);
    this.disruptor = new Disruptor<>(CacheEvent::new, ringBufferSize, executor);
    this.disruptor.handleEventsWith(handler);
    this.disruptor.start();
  }

  /**
   * Initiates an orderly shutdown in which previously submitted tasks are executed, but no new
   * tasks will be accepted. Invocation has no additional effect if already shut down.
   *
   * @throws IOException if this resource cannot be closed
   */
  public void shutdown() throws IOException {
    disruptor.shutdown();
    executor.shutdown();
    handler.close();
  }

  @Override
  public void recordCreate(Object key, int weight) {
    publish(0, Action.CREATE, key, weight);
  }

  @Override
  public void recordRead(Object key) {
    publish(0, Action.READ, key, 0);
  }

  @Override
  public void recordUpdate(Object key, int weightDifference) {
    publish(0, Action.UPDATE, key, weightDifference);
  }

  @Override
  public void recordDelete(Object key) {
    publish(0, Action.DELETE, key, 0);
  }

  /** Publishes the event onto the ring buffer for asynchronous handling. */
  private void publish(int id, Action action, Object key, int weightDifference) {
    CacheEvent template = new CacheEvent(id, action,
        key.hashCode(), weightDifference, System.nanoTime());
    disruptor.getRingBuffer().publishEvent(translator, template);
  }

  /** Returns the event handler, either the default or specified by a system property. */
  private static LogEventHandler eventHandler() {
    String property = System.getProperty(TRACING_FORMAT, "text").toLowerCase();
    if (property.equals("text")) {
      return new TextLogEventHandler(filePath());
    } else if (property.equals("binary")) {
      return new BinaryLogEventHandler(filePath());
    }
    throw new IllegalStateException("Unknown format:" + property);
  }

  /** Returns the log file path, either the default or specified by a system property. */
  private static Path filePath() {
    String property = System.getProperty(TRACING_FILE, "caffeine.log");
    return Paths.get(property);
  }

  /** Returns the ring buffer size, either the default or specified by a system property. */
  private static int ringBufferSize() {
    String property = System.getProperty(TRACING_BUFFER_SIZE, "256");
    return Integer.parseInt(property);
  }
}