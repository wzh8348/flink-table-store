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

package org.apache.flink.table.store.connector.source;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.ProviderContext;
import org.apache.flink.table.connector.source.DataStreamScanProvider;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.table.expressions.ExpressionVisitor;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.TypeLiteralExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.apache.flink.table.store.connector.TableStore;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.predicate.PredicateBuilder;
import org.apache.flink.table.store.file.predicate.PredicateConverter;
import org.apache.flink.table.store.log.LogSourceProvider;
import org.apache.flink.table.store.log.LogStoreTableFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.flink.table.store.connector.TableStoreFactoryOptions.SCAN_PARALLELISM;
import static org.apache.flink.table.store.log.LogOptions.CHANGELOG_MODE;
import static org.apache.flink.table.store.log.LogOptions.CONSISTENCY;
import static org.apache.flink.table.store.log.LogOptions.LogChangelogMode.ALL;
import static org.apache.flink.table.store.log.LogOptions.LogConsistency.TRANSACTIONAL;

/**
 * Table source to create {@link FileStoreSource} under batch mode or change-tracking is disabled.
 * For streaming mode with change-tracking enabled and FULL scan mode, it will create a {@link
 * org.apache.flink.connector.base.source.hybrid.HybridSource} of {@link FileStoreSource} and kafka
 * log source created by {@link LogSourceProvider}.
 */
public class TableStoreSource
        implements ScanTableSource, SupportsFilterPushDown, SupportsProjectionPushDown {

    private final TableStore tableStore;
    private final boolean streaming;
    private final DynamicTableFactory.Context logStoreContext;
    @Nullable private final LogStoreTableFactory logStoreTableFactory;

    @Nullable private Predicate partitionPredicate;
    @Nullable private Predicate fieldPredicate;
    @Nullable private int[][] projectFields;

    public TableStoreSource(
            TableStore tableStore,
            boolean streaming,
            DynamicTableFactory.Context logStoreContext,
            @Nullable LogStoreTableFactory logStoreTableFactory) {
        this.tableStore = tableStore;
        this.streaming = streaming;
        this.logStoreContext = logStoreContext;
        this.logStoreTableFactory = logStoreTableFactory;
    }

    @Override
    public ChangelogMode getChangelogMode() {
        if (!streaming) {
            // batch merge all, return insert only
            return ChangelogMode.insertOnly();
        }

        if (tableStore.valueCountMode()) {
            // no primary key, return all
            return ChangelogMode.all();
        }

        // optimization: transaction consistency and all changelog mode avoid the generation of
        // normalized nodes. See TableStoreSink.getChangelogMode validation.
        Configuration logOptions = tableStore.logOptions();
        return logOptions.get(CONSISTENCY) == TRANSACTIONAL && logOptions.get(CHANGELOG_MODE) == ALL
                ? ChangelogMode.all()
                : ChangelogMode.upsert();
    }

    @Override
    public ScanRuntimeProvider getScanRuntimeProvider(ScanContext scanContext) {
        LogSourceProvider logSourceProvider = null;
        if (logStoreTableFactory != null) {
            logSourceProvider =
                    logStoreTableFactory.createSourceProvider(
                            logStoreContext,
                            new LogStoreTableFactory.SourceContext() {
                                @Override
                                public <T> TypeInformation<T> createTypeInformation(
                                        DataType producedDataType) {
                                    return scanContext.createTypeInformation(producedDataType);
                                }

                                @Override
                                public <T> TypeInformation<T> createTypeInformation(
                                        LogicalType producedLogicalType) {
                                    return scanContext.createTypeInformation(producedLogicalType);
                                }

                                @Override
                                public DataStructureConverter createDataStructureConverter(
                                        DataType producedDataType) {
                                    return scanContext.createDataStructureConverter(
                                            producedDataType);
                                }
                            },
                            projectFields);
        }

        TableStore.SourceBuilder sourceBuilder =
                tableStore
                        .sourceBuilder()
                        .withContinuousMode(streaming)
                        .withLogSourceProvider(logSourceProvider)
                        .withProjection(projectFields)
                        .withPartitionPredicate(partitionPredicate)
                        .withFieldPredicate(fieldPredicate)
                        .withParallelism(tableStore.options().get(SCAN_PARALLELISM));

        return new DataStreamScanProvider() {
            @Override
            public DataStream<RowData> produceDataStream(
                    ProviderContext providerContext, StreamExecutionEnvironment env) {
                return sourceBuilder.withEnv(env).build();
            }

            @Override
            public boolean isBounded() {
                return !streaming;
            }
        };
    }

    @Override
    public DynamicTableSource copy() {
        TableStoreSource copied =
                new TableStoreSource(tableStore, streaming, logStoreContext, logStoreTableFactory);
        copied.partitionPredicate = partitionPredicate;
        copied.fieldPredicate = fieldPredicate;
        copied.projectFields = projectFields;
        return copied;
    }

    @Override
    public String asSummaryString() {
        return "TableStoreSource";
    }

    @Override
    public Result applyFilters(List<ResolvedExpression> filters) {
        List<Predicate> partitionPredicates = new ArrayList<>();
        List<Predicate> fieldPredicates = new ArrayList<>();
        List<ResolvedExpression> notAcceptedFilters = new ArrayList<>();
        for (ResolvedExpression filter : filters) {
            Optional<ResolvedExpression> optionalPartPredicate = extractPartitionFilter(filter);
            if (optionalPartPredicate.isPresent()) {
                ResolvedExpression partitionFilter = optionalPartPredicate.get();
                Optional<Predicate> partitionPredicate =
                        PredicateConverter.convert(partitionFilter);
                if (partitionPredicate.isPresent()) {
                    partitionPredicates.add(partitionPredicate.get());
                } else {
                    notAcceptedFilters.add(partitionFilter);
                }
            } else {
                notAcceptedFilters.add(filter);
                PredicateConverter.convert(filter).ifPresent(fieldPredicates::add);
            }
        }
        partitionPredicate =
                partitionPredicates.isEmpty() ? null : PredicateBuilder.and(partitionPredicates);
        fieldPredicate = fieldPredicates.isEmpty() ? null : PredicateBuilder.and(fieldPredicates);
        return Result.of(
                filters, streaming && logStoreTableFactory != null ? filters : notAcceptedFilters);
    }

    @Override
    public boolean supportsNestedProjection() {
        return false;
    }

    @Override
    public void applyProjection(int[][] projectedFields, DataType producedDataType) {
        this.projectFields = projectedFields;
    }

    private Optional<ResolvedExpression> extractPartitionFilter(ResolvedExpression filter) {
        List<String> fieldNames = tableStore.fieldNames();
        List<String> partitionKeys = tableStore.partitionKeys();
        PartitionIndexVisitor visitor =
                new PartitionIndexVisitor(
                        fieldNames.stream().mapToInt(partitionKeys::indexOf).toArray());
        try {
            return Optional.of(filter.accept(visitor));
        } catch (FoundFieldReference e) {
            return Optional.empty();
        }
    }

    private static class PartitionIndexVisitor implements ExpressionVisitor<ResolvedExpression> {

        private final int[] mapping;

        PartitionIndexVisitor(int[] mapping) {
            this.mapping = mapping;
        }

        @Override
        public ResolvedExpression visit(CallExpression call) {
            return CallExpression.anonymous(
                    call.getFunctionDefinition(),
                    call.getResolvedChildren().stream()
                            .map(e -> e.accept(this))
                            .collect(Collectors.toList()),
                    call.getOutputDataType());
        }

        @Override
        public ResolvedExpression visit(ValueLiteralExpression valueLiteral) {
            return valueLiteral;
        }

        @Override
        public ResolvedExpression visit(FieldReferenceExpression fieldReference) {
            int adjustIndex = mapping[fieldReference.getFieldIndex()];
            if (adjustIndex == -1) {
                // not a partition field
                throw new FoundFieldReference();
            }
            return new FieldReferenceExpression(
                    fieldReference.getName(),
                    fieldReference.getOutputDataType(),
                    fieldReference.getInputIndex(),
                    adjustIndex);
        }

        @Override
        public ResolvedExpression visit(TypeLiteralExpression typeLiteral) {
            return typeLiteral;
        }

        @Override
        public ResolvedExpression visit(Expression other) {
            return (ResolvedExpression) other;
        }
    }

    private static class FoundFieldReference extends RuntimeException {}
}
