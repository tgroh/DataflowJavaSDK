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
package com.google.cloud.dataflow.sdk.testing;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.Serializable;
import java.util.Collection;

/**
 * A {@link Supplier} that returns a static set of {@link BoundedWindow BoundedWindows}. The
 * supplier is {@link Serializable}, and handles encoding and decoding the windows with a
 * {@link Coder} provided for the windows.
 */
final class WindowSupplier implements Supplier<Collection<BoundedWindow>>, Serializable {
  private final Coder<? extends BoundedWindow> coder;
  private final Collection<byte[]> encodedWindows;

  private transient Collection<BoundedWindow> windows;

  public static <W extends BoundedWindow> WindowSupplier of(Coder<W> coder, Iterable<W> windows) {
    ImmutableSet.Builder<byte[]> windowsBuilder = ImmutableSet.builder();
    for (W window : windows) {
      try {
        windowsBuilder.add(CoderUtils.encodeToByteArray(coder, window));
      } catch (CoderException e) {
        throw new IllegalArgumentException(
            "Could not encode provided windows with the provided window coder", e);
      }
    }
    return new WindowSupplier(coder, windowsBuilder.build());
  }

  private WindowSupplier(Coder<? extends BoundedWindow> coder, Collection<byte[]> encodedWindows) {
    this.coder = coder;
    this.encodedWindows = encodedWindows;
  }

  @Override
  public Collection<BoundedWindow> get() {
    if (windows == null) {
      decodeWindows();
    }
    return windows;
  }

  private synchronized void decodeWindows() {
    if (windows == null) {
      ImmutableList.Builder<BoundedWindow> windowsBuilder = ImmutableList.builder();
      for (byte[] encoded : encodedWindows) {
        try {
          windowsBuilder.add(CoderUtils.decodeFromByteArray(coder, encoded));
        } catch (CoderException e) {
          throw new IllegalArgumentException(
              "Could not decode provided windows with the provided window coder", e);
        }
      }
      this.windows = windowsBuilder.build();
    }
  }
}
