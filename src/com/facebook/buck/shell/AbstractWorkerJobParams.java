/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Optional;

@Value.Immutable
@BuckStyleTuple
interface AbstractWorkerJobParams {
  Path getTempDir();
  ImmutableList<String> getStartupCommand();
  String getStartupArgs();
  ImmutableMap<String, String> getStartupEnvironment();
  String getJobArgs();
  int getMaxWorkers();
  Optional<String> getPersistentWorkerKey();
  Optional<HashCode> getWorkerHash();
}
