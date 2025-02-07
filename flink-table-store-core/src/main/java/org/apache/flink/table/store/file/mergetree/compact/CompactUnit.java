/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.mergetree.compact;

import org.apache.flink.table.store.file.data.DataFileMeta;
import org.apache.flink.table.store.file.mergetree.LevelSortedRun;

import java.util.ArrayList;
import java.util.List;

/** A files unit for compaction. */
interface CompactUnit {

    int outputLevel();

    List<DataFileMeta> files();

    static CompactUnit fromLevelRuns(int outputLevel, List<LevelSortedRun> runs) {
        List<DataFileMeta> files = new ArrayList<>();
        for (LevelSortedRun run : runs) {
            files.addAll(run.run().files());
        }
        return fromFiles(outputLevel, files);
    }

    static CompactUnit fromFiles(int outputLevel, List<DataFileMeta> files) {
        return new CompactUnit() {
            @Override
            public int outputLevel() {
                return outputLevel;
            }

            @Override
            public List<DataFileMeta> files() {
                return files;
            }
        };
    }
}
