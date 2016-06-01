/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.runners.inprocess;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertThat;

import com.google.cloud.dataflow.sdk.coders.ByteArrayCoder;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.CommittedBundle;
import com.google.cloud.dataflow.sdk.runners.inprocess.InProcessPipelineRunner.UncommittedBundle;
import com.google.cloud.dataflow.sdk.testing.TestPipeline;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.windowing.IntervalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.util.IllegalMutationException;
import com.google.cloud.dataflow.sdk.util.UserCodeException;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.values.PCollection;

import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ImmutabilityCheckingBundleFactory}.
 */
@RunWith(JUnit4.class)
public class ImmutabilityCheckingBundleFactoryTest {
  @Rule public ExpectedException thrown = ExpectedException.none();
  private ImmutabilityCheckingBundleFactory factory;
  private PCollection<byte[]> created;
  private PCollection<byte[]> transformed;

  @Before
  public void setup() {
    TestPipeline p = TestPipeline.create();
    created = p.apply(Create.<byte[]>of().withCoder(ByteArrayCoder.of()));
    transformed = created.apply(ParDo.of(new IdentityDoFn<byte[]>()));
    factory = ImmutabilityCheckingBundleFactory.create(InProcessBundleFactory.create());
  }

  @Test
  public void noMutationRootBundleSucceeds() {
    UncommittedBundle<byte[]> root = factory.createRootBundle(created);
    byte[] array = new byte[] {0, 1, 2};
    root.add(WindowedValue.valueInGlobalWindow(array));
    CommittedBundle<byte[]> committed = root.commit(Instant.now());

    assertThat(
        committed.getElements(), containsInAnyOrder(WindowedValue.valueInGlobalWindow(array)));
  }

  @Test
  public void noMutationKeyedBundleSucceeds() {
    CommittedBundle<byte[]> root = factory.createRootBundle(created).commit(Instant.now());
    UncommittedBundle<byte[]> keyed = factory.createKeyedBundle(root,
        StructuralKey.of("mykey", StringUtf8Coder.of()),
        transformed);

    WindowedValue<byte[]> windowedArray =
        WindowedValue.of(
            new byte[] {4, 8, 12},
            new Instant(891L),
            new IntervalWindow(new Instant(0), new Instant(1000)),
            PaneInfo.ON_TIME_AND_ONLY_FIRING);
    keyed.add(windowedArray);

    CommittedBundle<byte[]> committed = keyed.commit(Instant.now());
    assertThat(committed.getElements(), containsInAnyOrder(windowedArray));
  }

  @Test
  public void noMutationCreateBundleSucceeds() {
    CommittedBundle<byte[]> root = factory.createRootBundle(created).commit(Instant.now());
    UncommittedBundle<byte[]> intermediate = factory.createBundle(root, transformed);

    WindowedValue<byte[]> windowedArray =
        WindowedValue.of(
            new byte[] {4, 8, 12},
            new Instant(891L),
            new IntervalWindow(new Instant(0), new Instant(1000)),
            PaneInfo.ON_TIME_AND_ONLY_FIRING);
    intermediate.add(windowedArray);

    CommittedBundle<byte[]> committed = intermediate.commit(Instant.now());
    assertThat(committed.getElements(), containsInAnyOrder(windowedArray));
  }

  @Test
  public void mutationBeforeAddRootBundleSucceeds() {
    UncommittedBundle<byte[]> root = factory.createRootBundle(created);
    byte[] array = new byte[] {0, 1, 2};
    array[1] = 2;
    root.add(WindowedValue.valueInGlobalWindow(array));
    CommittedBundle<byte[]> committed = root.commit(Instant.now());

    assertThat(
        committed.getElements(), containsInAnyOrder(WindowedValue.valueInGlobalWindow(array)));
  }

  @Test
  public void mutationBeforeAddKeyedBundleSucceeds() {
    CommittedBundle<byte[]> root = factory.createRootBundle(created).commit(Instant.now());
    UncommittedBundle<byte[]> keyed = factory.createKeyedBundle(root,
        StructuralKey.of("mykey", StringUtf8Coder.of()),
        transformed);

    byte[] array = new byte[] {4, 8, 12};
    array[0] = Byte.MAX_VALUE;
    WindowedValue<byte[]> windowedArray =
        WindowedValue.of(
            array,
            new Instant(891L),
            new IntervalWindow(new Instant(0), new Instant(1000)),
            PaneInfo.ON_TIME_AND_ONLY_FIRING);
    keyed.add(windowedArray);

    CommittedBundle<byte[]> committed = keyed.commit(Instant.now());
    assertThat(committed.getElements(), containsInAnyOrder(windowedArray));
  }

  @Test
  public void mutationBeforeAddCreateBundleSucceeds() {
    CommittedBundle<byte[]> root = factory.createRootBundle(created).commit(Instant.now());
    UncommittedBundle<byte[]> intermediate = factory.createBundle(root, transformed);

    byte[] array = new byte[] {4, 8, 12};
    WindowedValue<byte[]> windowedArray =
        WindowedValue.of(
            array,
            new Instant(891L),
            new IntervalWindow(new Instant(0), new Instant(1000)),
            PaneInfo.ON_TIME_AND_ONLY_FIRING);
    array[2] = -3;
    intermediate.add(windowedArray);

    CommittedBundle<byte[]> committed = intermediate.commit(Instant.now());
    assertThat(committed.getElements(), containsInAnyOrder(windowedArray));
  }

  @Test
  public void mutationAfterAddRootBundleThrows() {
    UncommittedBundle<byte[]> root = factory.createRootBundle(created);
    byte[] array = new byte[] {0, 1, 2};
    root.add(WindowedValue.valueInGlobalWindow(array));

    array[1] = 2;
    thrown.expect(UserCodeException.class);
    thrown.expectCause(isA(IllegalMutationException.class));
    thrown.expectMessage("Values must not be mutated in any way after being output");
    CommittedBundle<byte[]> committed = root.commit(Instant.now());
  }

  @Test
  public void mutationAfterAddKeyedBundleThrows() {
    CommittedBundle<byte[]> root = factory.createRootBundle(created).commit(Instant.now());
    UncommittedBundle<byte[]> keyed = factory.createKeyedBundle(root,
        StructuralKey.of("mykey", StringUtf8Coder.of()),
        transformed);

    byte[] array = new byte[] {4, 8, 12};
    WindowedValue<byte[]> windowedArray =
        WindowedValue.of(
            array,
            new Instant(891L),
            new IntervalWindow(new Instant(0), new Instant(1000)),
            PaneInfo.ON_TIME_AND_ONLY_FIRING);
    keyed.add(windowedArray);

    array[0] = Byte.MAX_VALUE;
    thrown.expect(UserCodeException.class);
    thrown.expectCause(isA(IllegalMutationException.class));
    thrown.expectMessage("Values must not be mutated in any way after being output");
    CommittedBundle<byte[]> committed = keyed.commit(Instant.now());
  }

  @Test
  public void mutationAfterAddCreateBundleThrows() {
    CommittedBundle<byte[]> root = factory.createRootBundle(created).commit(Instant.now());
    UncommittedBundle<byte[]> intermediate = factory.createBundle(root, transformed);

    byte[] array = new byte[] {4, 8, 12};
    WindowedValue<byte[]> windowedArray =
        WindowedValue.of(
            array,
            new Instant(891L),
            new IntervalWindow(new Instant(0), new Instant(1000)),
            PaneInfo.ON_TIME_AND_ONLY_FIRING);
    intermediate.add(windowedArray);

    array[2] = -3;
    thrown.expect(UserCodeException.class);
    thrown.expectCause(isA(IllegalMutationException.class));
    thrown.expectMessage("Values must not be mutated in any way after being output");
    CommittedBundle<byte[]> committed = intermediate.commit(Instant.now());
  }

  private static class IdentityDoFn<T> extends DoFn<T, T> {
    @Override
    public void processElement(DoFn<T, T>.ProcessContext c) throws Exception {
      c.output(c.element());
    }
  }
}
