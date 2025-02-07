/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.mapred;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.data.binary.BinaryRowData;
import org.apache.flink.table.store.RowDataContainer;
import org.apache.flink.table.store.SearchArgumentToPredicateConverter;
import org.apache.flink.table.store.TableStoreJobConf;
import org.apache.flink.table.store.file.FileStoreImpl;
import org.apache.flink.table.store.file.FileStoreOptions;
import org.apache.flink.table.store.file.data.DataFileMeta;
import org.apache.flink.table.store.file.operation.FileStoreRead;
import org.apache.flink.table.store.file.operation.FileStoreScan;
import org.apache.flink.table.store.file.operation.FileStoreScanImpl;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.util.Preconditions;

import org.apache.hadoop.hive.ql.exec.SerializationUtilities;
import org.apache.hadoop.hive.ql.io.sarg.ConvertAstToSearchArg;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.TableScanDesc;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link InputFormat} for table store. It divides all files into {@link InputSplit}s (one split per
 * bucket) and creates {@link RecordReader} for each split.
 */
public class TableStoreInputFormat implements InputFormat<Void, RowDataContainer> {

    @Override
    public InputSplit[] getSplits(JobConf jobConf, int numSplits) throws IOException {
        FileStoreWrapper wrapper = new FileStoreWrapper(jobConf);
        FileStoreScan scan = wrapper.newScan();
        List<TableStoreInputSplit> result = new ArrayList<>();
        for (Map.Entry<BinaryRowData, Map<Integer, List<DataFileMeta>>> pe :
                scan.plan().groupByPartFiles().entrySet()) {
            for (Map.Entry<Integer, List<DataFileMeta>> be : pe.getValue().entrySet()) {
                BinaryRowData partition = pe.getKey();
                int bucket = be.getKey();
                String bucketPath =
                        wrapper.store
                                .pathFactory()
                                .createDataFilePathFactory(partition, bucket)
                                .bucketPath()
                                .toString();
                TableStoreInputSplit split =
                        new TableStoreInputSplit(partition, bucket, be.getValue(), bucketPath);
                result.add(split);
            }
        }
        return result.toArray(new TableStoreInputSplit[0]);
    }

    @Override
    public RecordReader<Void, RowDataContainer> getRecordReader(
            InputSplit inputSplit, JobConf jobConf, Reporter reporter) throws IOException {
        FileStoreWrapper wrapper = new FileStoreWrapper(jobConf);
        FileStoreRead read = wrapper.store.newRead();
        TableStoreInputSplit split = (TableStoreInputSplit) inputSplit;
        org.apache.flink.table.store.file.utils.RecordReader wrapped =
                read.withDropDelete(true)
                        .createReader(split.partition(), split.bucket(), split.files());
        long splitLength = split.getLength();
        return new TableStoreRecordReader(
                wrapped,
                !new TableStoreJobConf(jobConf).getPrimaryKeyNames().isPresent(),
                splitLength);
    }

    private static class FileStoreWrapper {

        private List<String> columnNames;
        private List<LogicalType> columnTypes;

        private FileStoreImpl store;
        private boolean valueCountMode;
        @Nullable private Predicate predicate;

        private FileStoreWrapper(JobConf jobConf) {
            createFileStore(jobConf);
            createPredicate(jobConf);
        }

        private void createFileStore(JobConf jobConf) {
            TableStoreJobConf wrapper = new TableStoreJobConf(jobConf);

            String dbName = wrapper.getDbName();
            String tableName = wrapper.getTableName();

            Configuration options = new Configuration();
            String tableLocation = wrapper.getLocation();
            wrapper.updateFileStoreOptions(options);

            String user = wrapper.getFileStoreUser();

            columnNames = wrapper.getColumnNames();
            columnTypes = wrapper.getColumnTypes();

            List<String> partitionColumnNames = wrapper.getPartitionColumnNames();

            RowType rowType =
                    RowType.of(
                            columnTypes.toArray(new LogicalType[0]),
                            columnNames.toArray(new String[0]));
            LogicalType[] partitionLogicalTypes =
                    partitionColumnNames.stream()
                            .map(s -> columnTypes.get(columnNames.indexOf(s)))
                            .toArray(LogicalType[]::new);
            RowType partitionType =
                    RowType.of(partitionLogicalTypes, partitionColumnNames.toArray(new String[0]));

            Optional<List<String>> optionalPrimaryKeyNames = wrapper.getPrimaryKeyNames();
            if (optionalPrimaryKeyNames.isPresent()) {
                Function<String, RowType.RowField> rowFieldMapper =
                        s -> {
                            int idx = columnNames.indexOf(s);
                            Preconditions.checkState(
                                    idx >= 0,
                                    "Primary key column "
                                            + s
                                            + " not found in table "
                                            + dbName
                                            + "."
                                            + tableName);
                            return new RowType.RowField(s, columnTypes.get(idx));
                        };
                RowType primaryKeyType =
                        new RowType(
                                optionalPrimaryKeyNames.get().stream()
                                        .map(rowFieldMapper)
                                        .collect(Collectors.toList()));
                store =
                        FileStoreImpl.createWithPrimaryKey(
                                tableLocation,
                                0, // TODO
                                new FileStoreOptions(options),
                                user,
                                partitionType,
                                primaryKeyType,
                                rowType,
                                options.get(FileStoreOptions.MERGE_ENGINE));
                valueCountMode = false;
            } else {
                store =
                        FileStoreImpl.createWithValueCount(
                                tableLocation,
                                0, // TODO
                                new FileStoreOptions(options),
                                user,
                                partitionType,
                                rowType);
                valueCountMode = true;
            }
        }

        private void createPredicate(JobConf jobConf) {
            String hiveFilter = jobConf.get(TableScanDesc.FILTER_EXPR_CONF_STR);
            if (hiveFilter == null) {
                return;
            }

            ExprNodeGenericFuncDesc exprNodeDesc =
                    SerializationUtilities.deserializeObject(
                            hiveFilter, ExprNodeGenericFuncDesc.class);
            SearchArgument sarg = ConvertAstToSearchArg.create(jobConf, exprNodeDesc);
            SearchArgumentToPredicateConverter converter =
                    new SearchArgumentToPredicateConverter(sarg, columnNames, columnTypes);
            predicate = converter.convert().orElse(null);
        }

        private FileStoreScanImpl newScan() {
            FileStoreScanImpl scan = store.newScan();
            if (predicate != null) {
                if (valueCountMode) {
                    scan.withKeyFilter(predicate);
                } else {
                    scan.withValueFilter(predicate);
                }
            }
            return scan;
        }
    }
}
